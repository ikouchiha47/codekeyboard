#!/usr/bin/env python3
"""
Durable, bounded-RAM n-gram extractor (order N >= 2).

Stages (all resumable under --work-dir):

    count  — multi-process byte ranges; spill sorted TSV when unique entries
             hit --spill-entries (no per-ngram full-map scans)
    merge  — exact sum of partials → merged/n{k}.tsv  (then runs/ can be dropped)
    index  — cont_p + offset index into n2.tsv (file-backed bigram store)
    score  — stream top order; bigram backoff via seek+read one context;
             optional parallel trigram workers → JSONL shards → final JSON

Bigram KN is NEVER fully loaded as a Python nested dict at score time.
Parallel score only shards the trigram stream; all workers share a readonly
file-backed bigram store.

Output shapes (production):
    --output                 trigrams: {"w1 w2":{"followers":[[w,s],...],"support":N}}
    --output-bigrams         bigrams:  {"w1":[[w2,s],...]}
    --output-bigrams-support support:  {"w1":N}

Usage:
    python3 scripts/extract_ngrams_durable.py \\
        --input android/scripts/corpus_raw/swiftkey/build/swiftkey_all.txt \\
        --output android/scripts/corpus_raw/swiftkey/build/trigrams.json \\
        --output-bigrams android/scripts/corpus_raw/swiftkey/build/bigrams.json \\
        --output-bigrams-support android/scripts/corpus_raw/swiftkey/build/bigrams_support.json \\
        --order 3 \\
        --work-dir android/scripts/corpus_raw/swiftkey/build/ngrams_work \\
        --workers 4 \\
        --score-workers 4 \\
        --min-ngram-count 3 \\
        --min-bigram-count 2
"""
from __future__ import annotations

import argparse
import heapq
import json
import multiprocessing as mp
import os
import re
import sqlite3
import sys
import time
from collections import defaultdict
from pathlib import Path
from typing import Iterator

DISCOUNT = 0.75
MAX_FOLLOWERS = 10
MIN_NGRAM_COUNT = 1
MIN_BIGRAM_COUNT = 1
DEFAULT_SPILL_ENTRIES = 800_000  # unique (ctx,follower) pairs per run before spill
READ_BUF = 1 << 20

WORD_RE = re.compile(r"^[a-z']+$")


def is_clean(w: str) -> bool:
    return bool(WORD_RE.match(w)) and 1 <= len(w) <= 30


def tokenize(line: str) -> list[str]:
    return [w for w in (t.lower() for t in line.split()) if is_clean(w)]


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------------------
# Manifest
# ---------------------------------------------------------------------------


def _input_fingerprint(path: Path) -> dict:
    st = path.stat()
    return {"path": str(path.resolve()), "size": st.st_size, "mtime_ns": st.st_mtime_ns}


def _config_fingerprint(args: argparse.Namespace) -> dict:
    return {
        "order": args.order,
        "discount": args.discount,
        "max_followers": args.max_followers,
        "min_ngram_count": args.min_ngram_count,
        "min_bigram_count": args.min_bigram_count,
        "workers": args.workers,
        "score_workers": args.score_workers,
        "spill_entries": args.spill_entries,
        "output_bigrams": bool(args.output_bigrams),
    }


def load_manifest(work_dir: Path) -> dict:
    p = work_dir / "manifest.json"
    if not p.exists():
        return {}
    with p.open(encoding="utf-8") as f:
        return json.load(f)


def save_manifest(work_dir: Path, manifest: dict) -> None:
    work_dir.mkdir(parents=True, exist_ok=True)
    tmp = work_dir / "manifest.json.tmp"
    with tmp.open("w", encoding="utf-8") as f:
        json.dump(manifest, f, indent=2, sort_keys=True)
        f.write("\n")
    tmp.replace(work_dir / "manifest.json")


def _clear_dir(d: Path) -> None:
    if not d.exists():
        return
    for p in sorted(d.rglob("*"), reverse=True):
        if p.is_file():
            p.unlink()
        elif p.is_dir():
            try:
                p.rmdir()
            except OSError:
                pass


def ensure_manifest(work_dir: Path, args: argparse.Namespace, input_path: Path) -> dict:
    fp_in = _input_fingerprint(input_path)
    fp_cfg = _config_fingerprint(args)
    m = load_manifest(work_dir)
    if not m:
        m = {
            "input": fp_in,
            "config": fp_cfg,
            "stages": {"count": None, "merge": None, "index": None, "score": None},
        }
        save_manifest(work_dir, m)
        return m

    dirty = None
    if m.get("input") != fp_in:
        log("Input changed — invalidate all stages.")
        dirty = "count"
        m["input"] = fp_in
    else:
        old = m.get("config") or {}
        if old.get("order") != fp_cfg["order"] or old.get("spill_entries") != fp_cfg["spill_entries"]:
            dirty = "count"
        elif any(old.get(k) != fp_cfg[k] for k in (
            "discount", "max_followers", "min_ngram_count", "min_bigram_count",
            "output_bigrams", "score_workers",
        )):
            dirty = "score"

    if dirty == "count":
        m["stages"] = {"count": None, "merge": None, "index": None, "score": None}
        for sub in ("runs", "merged", "index", "score"):
            _clear_dir(work_dir / sub)
    elif dirty == "score":
        m["stages"]["score"] = None
        _clear_dir(work_dir / "score")
        # index reusable if merge unchanged
        if not (work_dir / "index" / "DONE").exists():
            m["stages"]["index"] = None

    m["config"] = fp_cfg
    # ensure all stage keys exist
    m.setdefault("stages", {})
    for k in ("count", "merge", "index", "score"):
        m["stages"].setdefault(k, None)
    save_manifest(work_dir, m)
    return m


def stage_done(manifest: dict, name: str) -> bool:
    return bool((manifest.get("stages") or {}).get(name))


def mark_stage(work_dir: Path, manifest: dict, name: str, meta: dict) -> None:
    manifest.setdefault("stages", {})[name] = {"done_at": time.time(), **meta}
    save_manifest(work_dir, manifest)


# ---------------------------------------------------------------------------
# I/O helpers
# ---------------------------------------------------------------------------


def byte_range_boundaries(path: Path, workers: int) -> list[tuple[int, int]]:
    size = path.stat().st_size
    if workers <= 1 or size == 0:
        return [(0, size)]
    raw = [size * i // workers for i in range(1, workers)]
    bounds = [0]
    with path.open("rb") as f:
        for b in raw:
            f.seek(b)
            f.readline()
            bounds.append(f.tell())
    bounds.append(size)
    return [(bounds[i], bounds[i + 1]) for i in range(len(bounds) - 1) if bounds[i] < bounds[i + 1]]


def iter_lines_byte_range(path: str | Path, start: int, end: int) -> Iterator[str]:
    with open(path, "rb") as f:
        f.seek(start)
        remaining = end - start
        buf = b""
        while remaining > 0 or buf:
            if remaining > 0:
                chunk = f.read(min(READ_BUF, remaining))
                if not chunk:
                    remaining = 0
                else:
                    remaining -= len(chunk)
                    buf += chunk
            nl = buf.find(b"\n")
            if nl < 0:
                if remaining <= 0:
                    if buf:
                        yield buf.decode("utf-8", errors="ignore")
                    break
                continue
            line, buf = buf[:nl], buf[nl + 1 :]
            yield line.decode("utf-8", errors="ignore")


def _parse_ng_line(line: str) -> tuple[str, str, int] | None:
    line = line.rstrip("\n")
    if not line:
        return None
    ctx, w, c = line.split("\t")
    return ctx, w, int(c)


def _parse_uni_line(line: str) -> tuple[str, int] | None:
    line = line.rstrip("\n")
    if not line:
        return None
    w, c = line.split("\t")
    return w, int(c)


def iter_grouped_ngrams(path: Path) -> Iterator[tuple[str, dict[str, int]]]:
    if not path.exists() or path.stat().st_size == 0:
        return
        yield  # pragma: no cover
    cur_ctx: str | None = None
    cur: dict[str, int] = {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            parsed = _parse_ng_line(line)
            if not parsed:
                continue
            ctx, w, c = parsed
            if cur_ctx is None:
                cur_ctx, cur = ctx, {w: c}
            elif ctx == cur_ctx:
                cur[w] = cur.get(w, 0) + c
            else:
                yield cur_ctx, cur
                cur_ctx, cur = ctx, {w: c}
        if cur_ctx is not None:
            yield cur_ctx, cur


def iter_grouped_ngrams_range(path: Path, start: int, end: int) -> Iterator[tuple[str, dict[str, int]]]:
    """Group n-gram lines in a byte range. Range must start at a line boundary.
    Does not split a context group across workers: if the range starts mid-file,
    skip until the first context change after reading any partial first group
    only when start==0; for start>0 caller must align to a context boundary.
    """
    cur_ctx: str | None = None
    cur: dict[str, int] = {}
    with path.open("rb") as f:
        f.seek(start)
        # If not at BOF, we assume start is already on a line + context boundary
        # (see split_grouped_ranges).
        pos = start
        buf = b""
        while pos < end or buf:
            if pos < end:
                chunk = f.read(min(READ_BUF, end - pos))
                if not chunk:
                    pos = end
                else:
                    pos += len(chunk)
                    buf += chunk
            nl = buf.find(b"\n")
            if nl < 0:
                if pos >= end:
                    if buf:
                        line = buf.decode("utf-8", errors="ignore")
                        buf = b""
                        parsed = _parse_ng_line(line)
                        if parsed:
                            ctx, w, c = parsed
                            if cur_ctx is None:
                                cur_ctx, cur = ctx, {w: c}
                            elif ctx == cur_ctx:
                                cur[w] = cur.get(w, 0) + c
                            else:
                                yield cur_ctx, cur
                                cur_ctx, cur = ctx, {w: c}
                    break
                continue
            raw, buf = buf[:nl], buf[nl + 1 :]
            parsed = _parse_ng_line(raw.decode("utf-8", errors="ignore"))
            if not parsed:
                continue
            ctx, w, c = parsed
            if cur_ctx is None:
                cur_ctx, cur = ctx, {w: c}
            elif ctx == cur_ctx:
                cur[w] = cur.get(w, 0) + c
            else:
                yield cur_ctx, cur
                cur_ctx, cur = ctx, {w: c}
        if cur_ctx is not None:
            yield cur_ctx, cur


def split_grouped_ranges(path: Path, workers: int) -> list[tuple[int, int]]:
    """Split a sorted nK.tsv on context boundaries (never mid-group)."""
    size = path.stat().st_size
    if workers <= 1 or size == 0:
        return [(0, size)]
    raw = [size * i // workers for i in range(1, workers)]
    bounds = [0]
    with path.open("rb") as f:
        for b in raw:
            f.seek(b)
            if b > 0:
                f.readline()  # finish partial line
            # advance to next context boundary
            line = f.readline()
            if not line:
                bounds.append(size)
                continue
            cur_ctx = line.split(b"\t", 1)[0]
            while True:
                pos = f.tell()
                nxt = f.readline()
                if not nxt:
                    bounds.append(size)
                    break
                ctx = nxt.split(b"\t", 1)[0]
                if ctx != cur_ctx:
                    bounds.append(pos)
                    break
                cur_ctx = ctx
    bounds.append(size)
    # unique increasing
    out = []
    prev = 0
    for b in bounds:
        if b > prev:
            if prev > 0 or b > 0:
                pass
            out.append(b)
            prev = b
    # rebuild pairs from unique bounds starting with 0
    uniq = sorted(set([0] + out + [size]))
    return [(uniq[i], uniq[i + 1]) for i in range(len(uniq) - 1) if uniq[i] < uniq[i + 1]]


# ---------------------------------------------------------------------------
# Count
# ---------------------------------------------------------------------------


def _spill_counts(runs_dir, worker_id, run_id, order, unigrams, counts) -> None:
    with (runs_dir / f"w{worker_id:03d}_r{run_id:04d}.uni.tsv").open("w", encoding="utf-8") as f:
        for w, c in sorted(unigrams.items()):
            f.write(f"{w}\t{c}\n")
    for k in range(2, order + 1):
        path = runs_dir / f"w{worker_id:03d}_r{run_id:04d}.n{k}.tsv"
        with path.open("w", encoding="utf-8") as f:
            items = sorted(
                ((" ".join(ctx), w, c)
                 for ctx, fol in counts[k].items()
                 for w, c in fol.items()),
                key=lambda t: (t[0], t[1]),
            )
            for ctx, w, c in items:
                f.write(f"{ctx}\t{w}\t{c}\n")


def count_worker(task: tuple) -> dict:
    path, start, end, order, worker_id, runs_dir_s, spill_entries = task
    runs_dir = Path(runs_dir_s)
    done_marker = runs_dir / f"w{worker_id:03d}.done"
    if done_marker.exists():
        return {"worker_id": worker_id, "skipped": True, "runs": 0}

    unigrams: dict[str, int] = defaultdict(int)
    counts: dict[int, dict] = {
        k: defaultdict(lambda: defaultdict(int)) for k in range(2, order + 1)
    }
    unique_entries = 0  # unigram keys + all (ctx,follower) pairs
    run_id = 0
    lines = 0

    def spill() -> None:
        nonlocal unigrams, counts, unique_entries, run_id
        if unique_entries == 0:
            return
        _spill_counts(runs_dir, worker_id, run_id, order, unigrams, counts)
        run_id += 1
        unigrams = defaultdict(int)
        counts = {k: defaultdict(lambda: defaultdict(int)) for k in range(2, order + 1)}
        unique_entries = 0
        if run_id % 5 == 0:
            log(f"count w{worker_id:03d}: spilled run {run_id}, lines={lines}")

    for line in iter_lines_byte_range(path, start, end):
        lines += 1
        words = tokenize(line)
        if not words:
            continue
        for w in words:
            if w not in unigrams:
                unique_entries += 1
            unigrams[w] += 1
        for k in range(2, order + 1):
            for i in range(len(words) - k + 1):
                ctx = tuple(words[i : i + k - 1])
                w = words[i + k - 1]
                fol = counts[k][ctx]
                if w not in fol:
                    unique_entries += 1
                fol[w] += 1
        if unique_entries >= spill_entries:
            spill()

    spill()
    tmp = done_marker.with_suffix(".done.tmp")
    tmp.write_text(json.dumps({"worker_id": worker_id, "runs": run_id, "lines": lines}) + "\n")
    tmp.replace(done_marker)
    log(f"count w{worker_id:03d}: done lines={lines} runs={run_id}")
    return {"worker_id": worker_id, "skipped": False, "runs": run_id, "lines": lines}


def stage_count(args, input_path: Path, work_dir: Path, manifest: dict) -> None:
    if stage_done(manifest, "count") and not args.force:
        log("count: already done — skip")
        return
    runs_dir = work_dir / "runs"
    if args.force:
        for sub in ("runs", "merged", "index", "score"):
            _clear_dir(work_dir / sub)
        manifest["stages"] = {k: None for k in ("count", "merge", "index", "score")}
        save_manifest(work_dir, manifest)

    runs_dir.mkdir(parents=True, exist_ok=True)
    ranges = byte_range_boundaries(input_path, args.workers)
    log(f"count: {input_path.stat().st_size} bytes → {len(ranges)} ranges, "
        f"workers={args.workers}, spill_entries={args.spill_entries}")

    tasks = [
        (str(input_path), s, e, args.order, i, str(runs_dir), args.spill_entries)
        for i, (s, e) in enumerate(ranges)
    ]
    t0 = time.time()
    if len(tasks) == 1:
        results = [count_worker(t) for t in tasks]
    else:
        ctx = mp.get_context("spawn")
        with ctx.Pool(processes=min(args.workers, len(tasks))) as pool:
            results = pool.map(count_worker, tasks)
    total_lines = sum(r.get("lines", 0) for r in results if not r.get("skipped"))
    log(f"count: finished in {time.time() - t0:.1f}s lines≈{total_lines}")
    mark_stage(work_dir, manifest, "count", {"results": results})
    for k in ("merge", "index", "score"):
        manifest["stages"][k] = None
    save_manifest(work_dir, manifest)


# ---------------------------------------------------------------------------
# Merge
# ---------------------------------------------------------------------------


def _merge_sorted_uni(paths: list[Path], out: Path) -> int:
    files = [p.open(encoding="utf-8") for p in paths if p.exists() and p.stat().st_size > 0]
    if not files:
        out.write_text("")
        return 0

    def keyed(f):
        for line in f:
            p = _parse_uni_line(line)
            if p:
                yield p[0], p

    merged = heapq.merge(*(keyed(f) for f in files), key=lambda x: x[0])
    n = 0
    with out.open("w", encoding="utf-8") as dest:
        cur_w, cur_c = None, 0
        for w, (_w, c) in merged:
            if cur_w is None:
                cur_w, cur_c = w, c
            elif w == cur_w:
                cur_c += c
            else:
                dest.write(f"{cur_w}\t{cur_c}\n")
                n += 1
                cur_w, cur_c = w, c
        if cur_w is not None:
            dest.write(f"{cur_w}\t{cur_c}\n")
            n += 1
    for f in files:
        f.close()
    return n


def _merge_sorted_ng(paths: list[Path], out: Path) -> int:
    files = [p.open(encoding="utf-8") for p in paths if p.exists() and p.stat().st_size > 0]
    if not files:
        out.write_text("")
        return 0

    def keyed(f):
        for line in f:
            p = _parse_ng_line(line)
            if p:
                yield (p[0], p[1]), p

    merged = heapq.merge(*(keyed(f) for f in files), key=lambda x: x[0])
    n = 0
    with out.open("w", encoding="utf-8") as dest:
        cur, cur_c = None, 0
        for key, (ctx, w, c) in merged:
            if cur is None:
                cur, cur_c = key, c
            elif key == cur:
                cur_c += c
            else:
                dest.write(f"{cur[0]}\t{cur[1]}\t{cur_c}\n")
                n += 1
                cur, cur_c = key, c
        if cur is not None:
            dest.write(f"{cur[0]}\t{cur[1]}\t{cur_c}\n")
            n += 1
    for f in files:
        f.close()
    return n


def stage_merge(args, work_dir: Path, manifest: dict) -> None:
    if stage_done(manifest, "merge") and not args.force:
        log("merge: already done — skip")
        return
    if not stage_done(manifest, "count"):
        sys.exit("merge: need count first")

    runs_dir = work_dir / "runs"
    merged_dir = work_dir / "merged"
    if args.force:
        _clear_dir(merged_dir)
        _clear_dir(work_dir / "index")
        _clear_dir(work_dir / "score")
        for k in ("merge", "index", "score"):
            manifest["stages"][k] = None
        save_manifest(work_dir, manifest)

    merged_dir.mkdir(parents=True, exist_ok=True)
    t0 = time.time()
    stats = {}
    stats["unigrams"] = _merge_sorted_uni(
        sorted(runs_dir.glob("w*_r*.uni.tsv")), merged_dir / "unigrams.tsv"
    )
    log(f"merge: unigrams → {stats['unigrams']}")
    for k in range(2, args.order + 1):
        n = _merge_sorted_ng(sorted(runs_dir.glob(f"w*_r*.n{k}.tsv")), merged_dir / f"n{k}.tsv")
        stats[f"n{k}"] = n
        log(f"merge: order-{k} pairs → {n}")

    # free disk: drop run shards after successful merge
    if not args.keep_runs:
        log("merge: deleting runs/ to free disk...")
        _clear_dir(runs_dir)

    (merged_dir / "DONE").write_text(json.dumps(stats, indent=2) + "\n")
    log(f"merge: done in {time.time() - t0:.1f}s")
    mark_stage(work_dir, manifest, "merge", stats)
    for k in ("index", "score"):
        manifest["stages"][k] = None
    save_manifest(work_dir, manifest)


# ---------------------------------------------------------------------------
# Index — file-backed bigram store (offset index + cont_p)
# ---------------------------------------------------------------------------


def stage_index(args, work_dir: Path, manifest: dict) -> None:
    if stage_done(manifest, "index") and not args.force:
        log("index: already done — skip")
        return
    if not stage_done(manifest, "merge") and not (work_dir / "merged" / "DONE").exists():
        sys.exit("index: need merge first")

    index_dir = work_dir / "index"
    if args.force:
        _clear_dir(index_dir)
        manifest["stages"]["index"] = None
        manifest["stages"]["score"] = None
        _clear_dir(work_dir / "score")
        save_manifest(work_dir, manifest)
    index_dir.mkdir(parents=True, exist_ok=True)

    n2_path = work_dir / "merged" / "n2.tsv"
    db_path = index_dir / "bigram_off.sqlite"
    cont_path = index_dir / "cont_p.tsv"
    t0 = time.time()

    if db_path.exists():
        db_path.unlink()

    log("index: building offset index + continuation counts from n2.tsv...")
    conn = sqlite3.connect(str(db_path))
    conn.execute("PRAGMA journal_mode=OFF")
    conn.execute("PRAGMA synchronous=OFF")
    conn.execute(
        "CREATE TABLE bigram_off ("
        "ctx TEXT PRIMARY KEY NOT NULL,"
        "offset INTEGER NOT NULL,"
        "end_offset INTEGER NOT NULL,"
        "total INTEGER NOT NULL,"
        "distinct_n INTEGER NOT NULL"
        ")"
    )

    # cont: word -> set of predecessors would be huge; stream two-pass style:
    # first pass: write offsets + accumulate predecessor counts via temp
    # For cont_p we need |{w1: (w1,w2) exists}| per w2.
    # Use a second sqlite table pred_count(w2, n) updated with distinct w1 —
    # but distinct needs set. Simpler: temp file of "w2\tw1" unique via sort.
    # Bounded approach: as we scan each ctx group, for each follower w2 add w1
    # to a running spill of pairs, then external unique count.
    #
    # Practical: maintain dict[w2] -> count of distinct preds by streaming
    # sorted n2 (ctx=w1). For each w1 group, each w2 gets +1 predecessor (this w1).
    # That's exact N1+(*, w2) without sets of strings long-term — just int counts.
    pred_n: dict[str, int] = defaultdict(int)
    total_bigram_types = 0

    insert = []
    batch = 50_000
    with n2_path.open("rb") as f:
        cur_ctx: str | None = None
        group_off = 0
        group_total = 0
        group_distinct = 0
        line_off = 0

        def flush_group(end_off: int) -> None:
            nonlocal insert
            if cur_ctx is None:
                return
            insert.append((cur_ctx, group_off, end_off, group_total, group_distinct))
            if len(insert) >= batch:
                conn.executemany(
                    "INSERT INTO bigram_off(ctx,offset,end_offset,total,distinct_n) VALUES (?,?,?,?,?)",
                    insert,
                )
                conn.commit()
                insert.clear()

        while True:
            line_off = f.tell()
            raw = f.readline()
            if not raw:
                flush_group(line_off)
                break
            line = raw.decode("utf-8", errors="ignore").rstrip("\n")
            if not line:
                continue
            ctx, w, c_s = line.split("\t")
            c = int(c_s)
            if cur_ctx is None:
                cur_ctx = ctx
                group_off = line_off
                group_total = c
                group_distinct = 1
                pred_n[w] += 1
                total_bigram_types += 1
            elif ctx == cur_ctx:
                group_total += c
                group_distinct += 1
                pred_n[w] += 1
                total_bigram_types += 1
            else:
                flush_group(line_off)
                cur_ctx = ctx
                group_off = line_off
                group_total = c
                group_distinct = 1
                pred_n[w] += 1
                total_bigram_types += 1

        if insert:
            conn.executemany(
                "INSERT INTO bigram_off(ctx,offset,end_offset,total,distinct_n) VALUES (?,?,?,?,?)",
                insert,
            )
            conn.commit()

    conn.close()

    log(f"index: writing cont_p ({len(pred_n)} words, types={total_bigram_types})...")
    with cont_path.open("w", encoding="utf-8") as f:
        if total_bigram_types > 0:
            for w, n in sorted(pred_n.items()):
                f.write(f"{w}\t{n / total_bigram_types:.12g}\n")

    # free pred_n
    del pred_n

    meta = {
        "total_bigram_types": total_bigram_types,
        "db": str(db_path),
        "cont_p": str(cont_path),
        "n2": str(n2_path),
    }
    (index_dir / "DONE").write_text(json.dumps(meta, indent=2) + "\n")
    log(f"index: done in {time.time() - t0:.1f}s")
    mark_stage(work_dir, manifest, "index", meta)
    manifest["stages"]["score"] = None
    save_manifest(work_dir, manifest)


# ---------------------------------------------------------------------------
# File-backed bigram reader + KN for one context
# ---------------------------------------------------------------------------


class BigramStore:
    """Readonly: sqlite offsets + seek into n2.tsv. Loads one context at a time."""

    def __init__(self, n2_path: Path, db_path: Path, cont_path: Path):
        self.n2_path = n2_path
        self._f = n2_path.open("rb")
        self._conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True)
        self.cont_p: dict[str, float] = {}
        with cont_path.open(encoding="utf-8") as cf:
            for line in cf:
                line = line.rstrip("\n")
                if not line:
                    continue
                w, p = line.split("\t")
                self.cont_p[w] = float(p)

    def close(self) -> None:
        self._f.close()
        self._conn.close()

    def get_counts(self, ctx: str) -> tuple[dict[str, int], int, int] | None:
        row = self._conn.execute(
            "SELECT offset, end_offset, total, distinct_n FROM bigram_off WHERE ctx = ?",
            (ctx,),
        ).fetchone()
        if row is None:
            return None
        off, end_off, total, distinct_n = row
        self._f.seek(off)
        followers: dict[str, int] = {}
        while self._f.tell() < end_off:
            raw = self._f.readline()
            if not raw:
                break
            line = raw.decode("utf-8", errors="ignore").rstrip("\n")
            if not line:
                continue
            c_ctx, w, c_s = line.split("\t")
            if c_ctx != ctx:
                break
            followers[w] = int(c_s)
        return followers, total, distinct_n

    def kn_dist(self, ctx: str, discount: float) -> dict[str, float]:
        """P_KN(w|ctx) for observed followers only (plus used with cont for missing)."""
        got = self.get_counts(ctx)
        if not got:
            return {}
        followers, total, distinct = got
        if total <= 0:
            return {}
        lam = (discount * distinct) / total
        dist = {}
        for w, count in followers.items():
            dist[w] = max(count - discount, 0) / total + lam * self.cont_p.get(w, 0.0)
        # stash lambda on a special key? better return tuple
        self._last_lam = lam
        self._last_followers = followers
        self._last_total = total
        return dist


def score_ctx_order2(
    followers: dict[str, int],
    cont_p: dict[str, float],
    discount: float,
    max_followers: int,
) -> dict | None:
    total = sum(followers.values())
    if total <= 0:
        return None
    distinct = len(followers)
    lam = (discount * distinct) / total
    # candidates = observed only for bare bigrams production (matches extract_bigrams_v2
    # conservative scope) — still KN-smoothed among observed.
    scored = []
    for w, count in followers.items():
        scored.append((w, max(count - discount, 0) / total + lam * cont_p.get(w, 0.0)))
    scored.sort(key=lambda p: (-p[1], p[0]))
    top = scored[:max_followers]
    if not top:
        return None
    max_score = top[0][1] or 1.0
    return {
        "followers": [[w, round(s / max_score, 4)] for w, s in top],
        "support": total,
    }


def score_ctx_order3(
    followers: dict[str, int],
    store: BigramStore,
    backoff_word: str,
    discount: float,
    max_followers: int,
) -> dict | None:
    """Trigam KN with candidate union = observed ∪ bigram followers of w2."""
    total = sum(followers.values())
    if total <= 0:
        return None
    distinct = len(followers)
    lam = (discount * distinct) / total

    lower = store.get_counts(backoff_word)
    if lower:
        low_fol, low_total, low_dist_n = lower
        low_lam = (discount * low_dist_n) / low_total if low_total else 0.0
        lower_dist = {
            w: max(c - discount, 0) / low_total + low_lam * store.cont_p.get(w, 0.0)
            for w, c in low_fol.items()
        }
    else:
        lower_dist = {}

    candidates = set(followers.keys()) | set(lower_dist.keys())
    scored = []
    for w in candidates:
        count = followers.get(w, 0)
        discounted = max(count - discount, 0) / total
        backoff = lower_dist.get(w, store.cont_p.get(w, 0.0))
        scored.append((w, discounted + lam * backoff))
    scored.sort(key=lambda p: (-p[1], p[0]))
    top = scored[:max_followers]
    if not top:
        return None
    max_score = top[0][1] or 1.0
    return {
        "followers": [[w, round(s / max_score, 4)] for w, s in top],
        "support": total,
    }


def _write_json_object_streaming(out_path: Path, items: Iterator[tuple[str, object]]) -> int:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(out_path.suffix + ".tmp")
    n = 0
    with tmp.open("w", encoding="utf-8") as f:
        f.write("{")
        first = True
        for key, val in items:
            if not first:
                f.write(",")
            first = False
            f.write(json.dumps(key, ensure_ascii=False))
            f.write(":")
            f.write(json.dumps(val, ensure_ascii=False, separators=(",", ":")))
            n += 1
            if n % 200_000 == 0:
                log(f"score: wrote {n}...")
        f.write("}")
    tmp.replace(out_path)
    return n


def _pack_jsonl_shards(shard_paths: list[Path], out_path: Path) -> int:
    """Each shard line: JSON array [key, value]. Pack into one object."""
    out_path.parent.mkdir(parents=True, exist_ok=True)
    tmp = out_path.with_suffix(out_path.suffix + ".tmp")
    n = 0
    with tmp.open("w", encoding="utf-8") as out:
        out.write("{")
        first = True
        for sp in shard_paths:
            if not sp.exists():
                continue
            with sp.open(encoding="utf-8") as inp:
                for line in inp:
                    line = line.strip()
                    if not line:
                        continue
                    key, val = json.loads(line)
                    if not first:
                        out.write(",")
                    first = False
                    out.write(json.dumps(key, ensure_ascii=False))
                    out.write(":")
                    out.write(json.dumps(val, ensure_ascii=False, separators=(",", ":")))
                    n += 1
        out.write("}")
    tmp.replace(out_path)
    return n


# ---------------------------------------------------------------------------
# Score workers (trigrams)
# ---------------------------------------------------------------------------

_score_init: dict = {}


def _init_tri_worker(n2: str, db: str, cont: str, discount: float, max_followers: int, min_count: int):
    store = BigramStore(Path(n2), Path(db), Path(cont))
    _score_init.update(
        store=store,
        discount=discount,
        max_followers=max_followers,
        min_count=min_count,
    )


def _score_tri_range(task: tuple) -> dict:
    """task = (n3_path, start, end, shard_path)"""
    n3_path, start, end, shard_path = task
    store: BigramStore = _score_init["store"]
    discount = _score_init["discount"]
    max_followers = _score_init["max_followers"]
    min_count = _score_init["min_count"]
    n = 0
    with open(shard_path, "w", encoding="utf-8") as out:
        for ctx_s, followers in iter_grouped_ngrams_range(Path(n3_path), start, end):
            if min_count > 1 and sum(followers.values()) < min_count:
                continue
            parts = ctx_s.split()
            if len(parts) < 1:
                continue
            backoff_word = parts[-1]  # w2 for trigram (w1,w2)
            entry = score_ctx_order3(
                followers, store, backoff_word, discount, max_followers
            )
            if entry is None:
                continue
            out.write(json.dumps([ctx_s, entry], ensure_ascii=False))
            out.write("\n")
            n += 1
            if n % 100_000 == 0:
                log(f"score shard {Path(shard_path).name}: {n} contexts")
    return {"shard": shard_path, "contexts": n}


def stage_score(args, work_dir: Path, manifest: dict, output_path: Path) -> None:
    outs_ok = output_path.exists()
    if args.output_bigrams:
        outs_ok = outs_ok and Path(args.output_bigrams).exists()
    if stage_done(manifest, "score") and outs_ok and not args.force:
        log("score: already done — skip")
        return
    if not stage_done(manifest, "index") and not (work_dir / "index" / "DONE").exists():
        sys.exit("score: need index first")

    score_dir = work_dir / "score"
    if args.force:
        _clear_dir(score_dir)
    score_dir.mkdir(parents=True, exist_ok=True)

    n2 = work_dir / "merged" / "n2.tsv"
    n3 = work_dir / "merged" / f"n{args.order}.tsv"
    db = work_dir / "index" / "bigram_off.sqlite"
    cont = work_dir / "index" / "cont_p.tsv"
    t0 = time.time()
    meta: dict = {}

    # --- bigrams: stream n2 groups, O(1) contexts in RAM ---
    if args.output_bigrams or args.order == 2:
        bi_out = Path(args.output_bigrams) if args.output_bigrams else output_path
        sup_out = Path(args.output_bigrams_support) if args.output_bigrams_support else None
        log(f"score: bigrams (stream n2) → {bi_out}")
        store = BigramStore(n2, db, cont)

        def gen_bi():
            for ctx_s, followers in iter_grouped_ngrams(n2):
                if args.min_bigram_count > 1 and sum(followers.values()) < args.min_bigram_count:
                    continue
                entry = score_ctx_order2(
                    followers, store.cont_p, args.discount, args.max_followers
                )
                if entry:
                    yield ctx_s, entry["followers"]

        n_bi = _write_json_object_streaming(bi_out, gen_bi())
        meta["bigram_contexts"] = n_bi
        log(f"score: bigrams {n_bi} ({bi_out.stat().st_size/1024:.0f} KB)")

        if sup_out is not None:
            def gen_sup():
                for ctx_s, followers in iter_grouped_ngrams(n2):
                    total = sum(followers.values())
                    if args.min_bigram_count > 1 and total < args.min_bigram_count:
                        continue
                    yield ctx_s, total
            n_s = _write_json_object_streaming(sup_out, gen_sup())
            meta["bigram_support"] = n_s
            log(f"score: bigrams_support {n_s} → {sup_out}")
        store.close()

    # --- trigrams+: parallel shards over n3, file-backed bigram backoff ---
    if args.order >= 3:
        log(f"score: order-{args.order} parallel workers={args.score_workers}")
        ranges = split_grouped_ranges(n3, args.score_workers)
        shard_paths = [score_dir / f"tri_part{i:03d}.jsonl" for i in range(len(ranges))]
        tasks = [
            (str(n3), s, e, str(sp))
            for (s, e), sp in zip(ranges, shard_paths)
        ]
        initargs = (str(n2), str(db), str(cont), args.discount, args.max_followers, args.min_ngram_count)

        if len(tasks) == 1:
            _init_tri_worker(*initargs)
            results = [_score_tri_range(tasks[0])]
            _score_init.get("store") and _score_init["store"].close()
        else:
            ctx = mp.get_context("spawn")
            with ctx.Pool(
                processes=min(args.score_workers, len(tasks)),
                initializer=_init_tri_worker,
                initargs=initargs,
            ) as pool:
                results = pool.map(_score_tri_range, tasks)

        log(f"score: packing {len(shard_paths)} shards → {output_path}")
        n_top = _pack_jsonl_shards(shard_paths, output_path)
        meta["top_contexts"] = n_top
        meta["shard_results"] = results
        log(f"score: order-{args.order} {n_top} contexts "
            f"({output_path.stat().st_size/1024:.0f} KB) in {time.time()-t0:.1f}s")

        if not args.keep_shards:
            for sp in shard_paths:
                if sp.exists():
                    sp.unlink()

    (score_dir / "DONE").write_text(json.dumps(meta, indent=2) + "\n")
    mark_stage(work_dir, manifest, "score", meta)


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv=None) -> argparse.Namespace:
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--output-bigrams", default=None)
    p.add_argument("--output-bigrams-support", default=None)
    p.add_argument("--order", type=int, required=True)
    p.add_argument("--work-dir", default=None)
    p.add_argument("--discount", type=float, default=DISCOUNT)
    p.add_argument("--max-followers", type=int, default=MAX_FOLLOWERS)
    p.add_argument("--min-ngram-count", type=int, default=MIN_NGRAM_COUNT)
    p.add_argument("--min-bigram-count", type=int, default=MIN_BIGRAM_COUNT)
    p.add_argument("--workers", type=int, default=max(1, min(4, (os.cpu_count() or 2) - 1)))
    p.add_argument("--score-workers", type=int, default=max(1, min(4, (os.cpu_count() or 2) - 1)))
    p.add_argument("--spill-entries", type=int, default=DEFAULT_SPILL_ENTRIES)
    p.add_argument("--stage", choices=("all", "count", "merge", "index", "score"), default="all")
    p.add_argument("--force", action="store_true")
    p.add_argument("--keep-runs", action="store_true", help="Do not delete runs/ after merge")
    p.add_argument("--keep-shards", action="store_true", help="Keep score JSONL shards")
    return p.parse_args(argv)


def main(argv=None) -> None:
    args = parse_args(argv)
    if args.order < 2:
        sys.exit("--order must be >= 2")
    input_path = Path(args.input)
    if not input_path.is_file():
        sys.exit(f"input not found: {input_path}")
    output_path = Path(args.output)
    work_dir = Path(args.work_dir) if args.work_dir else Path(str(output_path) + ".work")
    work_dir.mkdir(parents=True, exist_ok=True)

    free_gb = os.statvfs(work_dir).f_bavail * os.statvfs(work_dir).f_frsize / (1024**3)
    log(f"work-dir: {work_dir} (≈{free_gb:.1f} GiB free on volume)")
    if free_gb < 8:
        log("WARNING: <8 GiB free — merged n-gram TSVs may need several GiB; monitor df")

    manifest = ensure_manifest(work_dir, args, input_path)
    stages = (
        ["count", "merge", "index", "score"] if args.stage == "all" else [args.stage]
    )
    for name in stages:
        if name == "count":
            stage_count(args, input_path, work_dir, manifest)
        elif name == "merge":
            stage_merge(args, work_dir, manifest)
        elif name == "index":
            stage_index(args, work_dir, manifest)
        elif name == "score":
            stage_score(args, work_dir, manifest, output_path)
        manifest = load_manifest(work_dir)
    log("done.")


if __name__ == "__main__":
    main()
