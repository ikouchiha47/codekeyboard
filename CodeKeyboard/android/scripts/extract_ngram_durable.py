#!/usr/bin/env python3
"""
Durable, staged n-gram extractor (order N >= 2) — replacement path for
extract_ngrams.py when corpus size makes in-memory multiprocessing crash the
machine.

Same true interpolated Kneser-Ney family as extract_ngrams.py /
extract_trigrams.py (not Stupid Backoff). Stages spill to a work directory so
a kill mid-run is resumable.

## Why "exact count merge"?

Not a smoothing choice. Workers each see a slice of the corpus and emit
*partial* counts. Merge must SUM those partials per key so every n-gram ends
up with its true corpus count. Both KN and Stupid Backoff need that; sketches
/ sampling would be the alternative (we do not do that here).

## Stages

    count  — byte-range workers, local dicts with periodic spill to sorted TSV
    merge  — heap-merge runs → one sorted TSV per order (+ unigrams)
    score  — stream merged counts, KN backoff, write output JSON
    all    — run missing stages in order (default); skip completed ones

## Resume

State lives under --work-dir (default: <output>.work or /tmp/...):

    work_dir/manifest.json     input identity + stage flags
    work_dir/runs/wXXX_rYYY.*  count spills (sorted TSV)
    work_dir/runs/wXXX.done    worker finished
    work_dir/merged/*.tsv      merged counts
    work_dir/merged/DONE
    work_dir/score/DONE

Re-run the same command after a kill; completed stages are skipped unless
--force. Changing --input / --order / discount-related knobs invalidates
downstream stages via manifest fingerprint.

## Usage

    python3 scripts/extract_ngrams_durable.py \\
        --input android/scripts/corpus_raw/swiftkey_all.txt \\
        --output /tmp/trigrams.json \\
        --order 3 \\
        --work-dir /tmp/ngrams_work \\
        --workers 4 \\
        --min-ngram-count 3

    # single stage / resume
    python3 scripts/extract_ngrams_durable.py ... --stage count
    python3 scripts/extract_ngrams_durable.py ... --stage merge
    python3 scripts/extract_ngrams_durable.py ... --stage score
"""

from __future__ import annotations

import argparse
import heapq
import json
import multiprocessing as mp
import os
import re
import sys
import time
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator

DISCOUNT = 0.75
MAX_FOLLOWERS = 10
MIN_NGRAM_COUNT = 1
# Spill when this many (ctx, follower) pairs are held in a worker (~RAM knob).
DEFAULT_SPILL_ENTRIES = 1_500_000
READ_BUF = 1 << 20  # 1 MiB

WORD_RE = re.compile(r"^[a-z']+$")


def is_clean(w: str) -> bool:
    return bool(WORD_RE.match(w)) and 1 <= len(w) <= 30


def tokenize(line: str) -> list[str]:
    return [w for w in (t.lower() for t in line.split()) if is_clean(w)]


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


# ---------------------------------------------------------------------------
# Manifest / resume
# ---------------------------------------------------------------------------


def _input_fingerprint(path: Path) -> dict:
    st = path.stat()
    return {
        "path": str(path.resolve()),
        "size": st.st_size,
        "mtime_ns": st.st_mtime_ns,
    }


def _config_fingerprint(args: argparse.Namespace) -> dict:
    return {
        "order": args.order,
        "discount": args.discount,
        "max_followers": args.max_followers,
        "min_ngram_count": args.min_ngram_count,
        "workers": args.workers,
        "spill_entries": args.spill_entries,
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


def ensure_manifest(work_dir: Path, args: argparse.Namespace, input_path: Path) -> dict:
    """Create or reconcile manifest; invalidate stages when inputs/config drift."""
    fp_in = _input_fingerprint(input_path)
    fp_cfg = _config_fingerprint(args)
    m = load_manifest(work_dir)

    if not m:
        m = {
            "input": fp_in,
            "config": fp_cfg,
            "stages": {"count": None, "merge": None, "score": None},
        }
        save_manifest(work_dir, m)
        return m

    dirty_from = None
    if m.get("input") != fp_in:
        log("Input file changed since last run — invalidating all stages.")
        dirty_from = "count"
        m["input"] = fp_in
    else:
        old_cfg = m.get("config") or {}
        # Count depends on order + spill/workers only for layout; order change
        # must recount. discount/max_followers/min_ngram_count only affect score.
        count_keys = ("order",)
        merge_keys = ("order",)
        score_keys = ("order", "discount", "max_followers", "min_ngram_count")
        if any(old_cfg.get(k) != fp_cfg.get(k) for k in count_keys):
            dirty_from = "count"
        elif any(old_cfg.get(k) != fp_cfg.get(k) for k in merge_keys):
            dirty_from = "merge"
        elif any(old_cfg.get(k) != fp_cfg.get(k) for k in score_keys):
            dirty_from = "score"

    if dirty_from == "count":
        m["stages"] = {"count": None, "merge": None, "score": None}
        _clear_dir(work_dir / "runs")
        _clear_dir(work_dir / "merged")
        _clear_dir(work_dir / "score")
    elif dirty_from == "merge":
        m["stages"]["merge"] = None
        m["stages"]["score"] = None
        _clear_dir(work_dir / "merged")
        _clear_dir(work_dir / "score")
    elif dirty_from == "score":
        m["stages"]["score"] = None
        _clear_dir(work_dir / "score")

    m["config"] = fp_cfg
    save_manifest(work_dir, m)
    return m


def _clear_dir(d: Path) -> None:
    if not d.exists():
        return
    for p in d.rglob("*"):
        if p.is_file():
            p.unlink()
    # leave empty dirs


def stage_done(manifest: dict, name: str) -> bool:
    return bool((manifest.get("stages") or {}).get(name))


def mark_stage(work_dir: Path, manifest: dict, name: str, meta: dict) -> None:
    manifest.setdefault("stages", {})[name] = {"done_at": time.time(), **meta}
    save_manifest(work_dir, manifest)


# ---------------------------------------------------------------------------
# Byte ranges + line streaming
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
    return [
        (bounds[i], bounds[i + 1])
        for i in range(len(bounds) - 1)
        if bounds[i] < bounds[i + 1]
    ]


def iter_lines_byte_range(path: str | Path, start: int, end: int) -> Iterator[str]:
    """Yield text lines in [start, end) without loading the whole range."""
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
            line = buf[:nl]
            buf = buf[nl + 1 :]
            yield line.decode("utf-8", errors="ignore")


# ---------------------------------------------------------------------------
# Count stage (map + spill)
# ---------------------------------------------------------------------------


def _run_paths(runs_dir: Path, worker_id: int, run_id: int) -> dict[str, Path]:
    base = runs_dir / f"w{worker_id:03d}_r{run_id:04d}"
    return {
        "uni": Path(str(base) + ".uni.tsv"),
        **{
            f"n{k}": Path(str(base) + f".n{k}.tsv") for k in range(2, 32)
        },  # trimmed below
    }


def _spill_counts(
    runs_dir: Path,
    worker_id: int,
    run_id: int,
    order: int,
    unigrams: dict[str, int],
    counts: dict[int, dict[tuple[str, ...], dict[str, int]]],
) -> list[Path]:
    """Write sorted TSV runs; return paths written."""
    written: list[Path] = []
    uni_path = runs_dir / f"w{worker_id:03d}_r{run_id:04d}.uni.tsv"
    with uni_path.open("w", encoding="utf-8") as f:
        for w, c in sorted(unigrams.items()):
            f.write(f"{w}\t{c}\n")
    written.append(uni_path)

    for k in range(2, order + 1):
        path = runs_dir / f"w{worker_id:03d}_r{run_id:04d}.n{k}.tsv"
        with path.open("w", encoding="utf-8") as f:
            # sort by context then follower for heap-merge
            items = sorted(
                (
                    (" ".join(ctx), w, c)
                    for ctx, followers in counts[k].items()
                    for w, c in followers.items()
                ),
                key=lambda t: (t[0], t[1]),
            )
            for ctx, w, c in items:
                f.write(f"{ctx}\t{w}\t{c}\n")
        written.append(path)
    return written


def count_worker(task: tuple) -> dict:
    """
    task = (path, start, end, order, worker_id, runs_dir, spill_entries)
    Streams the byte range, spills sorted runs when the in-memory map grows.
    """
    path, start, end, order, worker_id, runs_dir_s, spill_entries = task
    runs_dir = Path(runs_dir_s)
    done_marker = runs_dir / f"w{worker_id:03d}.done"
    if done_marker.exists():
        return {"worker_id": worker_id, "skipped": True, "runs": 0}

    unigrams: dict[str, int] = defaultdict(int)
    counts: dict[int, dict] = {
        k: defaultdict(lambda: defaultdict(int)) for k in range(2, order + 1)
    }
    entries = 0
    run_id = 0
    lines = 0

    def entry_count() -> int:
        # approximate: unigram keys + all follower entries
        n = len(unigrams)
        for k in range(2, order + 1):
            for fol in counts[k].values():
                n += len(fol)
        return n

    def spill() -> None:
        nonlocal unigrams, counts, entries, run_id
        if not unigrams and all(len(counts[k]) == 0 for k in range(2, order + 1)):
            return
        _spill_counts(runs_dir, worker_id, run_id, order, unigrams, counts)
        run_id += 1
        unigrams = defaultdict(int)
        counts = {k: defaultdict(lambda: defaultdict(int)) for k in range(2, order + 1)}
        entries = 0

    for line in iter_lines_byte_range(path, start, end):
        lines += 1
        words = tokenize(line)
        if not words:
            continue
        for w in words:
            unigrams[w] += 1
        for k in range(2, order + 1):
            lim = len(words) - k + 1
            for i in range(lim):
                ctx = tuple(words[i : i + k - 1])
                counts[k][ctx][words[i + k - 1]] += 1
                entries += 1
        if entries >= spill_entries:
            # recompute true size occasionally; entries is a cheap upper signal
            if entry_count() >= spill_entries:
                spill()

    spill()
    # atomic-ish done marker
    tmp = done_marker.with_suffix(".done.tmp")
    tmp.write_text(
        json.dumps({"worker_id": worker_id, "runs": run_id, "lines": lines}) + "\n"
    )
    tmp.replace(done_marker)
    return {"worker_id": worker_id, "skipped": False, "runs": run_id, "lines": lines}


def stage_count(
    args: argparse.Namespace, input_path: Path, work_dir: Path, manifest: dict
) -> None:
    if stage_done(manifest, "count") and not args.force:
        log("count: already done — skip")
        return

    runs_dir = work_dir / "runs"
    if args.force:
        _clear_dir(runs_dir)
        manifest["stages"]["count"] = None
        manifest["stages"]["merge"] = None
        manifest["stages"]["score"] = None
        _clear_dir(work_dir / "merged")
        _clear_dir(work_dir / "score")
        save_manifest(work_dir, manifest)

    runs_dir.mkdir(parents=True, exist_ok=True)
    ranges = byte_range_boundaries(input_path, args.workers)
    log(
        f"count: {input_path.stat().st_size} bytes → {len(ranges)} ranges, "
        f"workers={args.workers}, spill_entries={args.spill_entries}"
    )

    tasks = [
        (
            str(input_path),
            start,
            end,
            args.order,
            i,
            str(runs_dir),
            args.spill_entries,
        )
        for i, (start, end) in enumerate(ranges)
    ]

    t0 = time.time()
    if args.workers == 1 or len(tasks) == 1:
        results = [count_worker(t) for t in tasks]
    else:
        # spawn: no COW surprise on macOS; each worker is isolated
        ctx = mp.get_context("spawn")
        with ctx.Pool(processes=min(args.workers, len(tasks))) as pool:
            results = pool.map(count_worker, tasks)

    total_lines = sum(r.get("lines", 0) for r in results if not r.get("skipped"))
    log(
        f"count: finished in {time.time() - t0:.1f}s (lines≈{total_lines}, results={results})"
    )
    mark_stage(work_dir, manifest, "count", {"workers": len(tasks), "results": results})


# ---------------------------------------------------------------------------
# Merge stage (exact sum of partials)
# ---------------------------------------------------------------------------


def _parse_uni_line(line: str) -> tuple[str, int] | None:
    line = line.rstrip("\n")
    if not line:
        return None
    w, c = line.split("\t")
    return w, int(c)


def _parse_ng_line(line: str) -> tuple[str, str, int] | None:
    line = line.rstrip("\n")
    if not line:
        return None
    ctx, w, c = line.split("\t")
    return ctx, w, int(c)


def _merge_sorted_uni(paths: list[Path], out: Path) -> int:
    """Exact merge: sum counts for equal keys. Inputs must be sorted by word."""
    files = [
        p.open(encoding="utf-8") for p in paths if p.exists() and p.stat().st_size > 0
    ]
    if not files:
        out.write_text("")
        return 0

    def keyed(f):
        for line in f:
            parsed = _parse_uni_line(line)
            if parsed:
                yield parsed[0], parsed

    merged = heapq.merge(*(keyed(f) for f in files), key=lambda x: x[0])
    n_keys = 0
    with out.open("w", encoding="utf-8") as dest:
        cur_w = None
        cur_c = 0
        for w, (_w, c) in merged:
            if cur_w is None:
                cur_w, cur_c = w, c
            elif w == cur_w:
                cur_c += c
            else:
                dest.write(f"{cur_w}\t{cur_c}\n")
                n_keys += 1
                cur_w, cur_c = w, c
        if cur_w is not None:
            dest.write(f"{cur_w}\t{cur_c}\n")
            n_keys += 1
    for f in files:
        f.close()
    return n_keys


def _merge_sorted_ng(paths: list[Path], out: Path) -> int:
    """Exact merge on (context, follower). Inputs sorted by ctx, follower."""
    files = [
        p.open(encoding="utf-8") for p in paths if p.exists() and p.stat().st_size > 0
    ]
    if not files:
        out.write_text("")
        return 0

    def keyed(f):
        for line in f:
            parsed = _parse_ng_line(line)
            if parsed:
                yield (parsed[0], parsed[1]), parsed

    merged = heapq.merge(*(keyed(f) for f in files), key=lambda x: x[0])
    n_keys = 0
    with out.open("w", encoding="utf-8") as dest:
        cur = None  # (ctx, w)
        cur_c = 0
        for key, (ctx, w, c) in merged:
            if cur is None:
                cur, cur_c = key, c
            elif key == cur:
                cur_c += c
            else:
                dest.write(f"{cur[0]}\t{cur[1]}\t{cur_c}\n")
                n_keys += 1
                cur, cur_c = key, c
        if cur is not None:
            dest.write(f"{cur[0]}\t{cur[1]}\t{cur_c}\n")
            n_keys += 1
    for f in files:
        f.close()
    return n_keys


def stage_merge(args: argparse.Namespace, work_dir: Path, manifest: dict) -> None:
    if stage_done(manifest, "merge") and not args.force:
        log("merge: already done — skip")
        return
    if not stage_done(manifest, "count"):
        sys.exit("merge: count stage not done — run count first")

    runs_dir = work_dir / "runs"
    merged_dir = work_dir / "merged"
    if args.force:
        _clear_dir(merged_dir)
        manifest["stages"]["merge"] = None
        manifest["stages"]["score"] = None
        _clear_dir(work_dir / "score")
        save_manifest(work_dir, manifest)

    merged_dir.mkdir(parents=True, exist_ok=True)
    t0 = time.time()

    uni_runs = sorted(runs_dir.glob("w*_r*.uni.tsv"))
    uni_out = merged_dir / "unigrams.tsv"
    n_uni = _merge_sorted_uni(uni_runs, uni_out)
    log(f"merge: unigrams → {n_uni} keys")

    stats = {"unigrams": n_uni}
    for k in range(2, args.order + 1):
        runs = sorted(runs_dir.glob(f"w*_r*.n{k}.tsv"))
        out = merged_dir / f"n{k}.tsv"
        n = _merge_sorted_ng(runs, out)
        stats[f"n{k}"] = n
        log(f"merge: order-{k} pairs → {n} keys")

    (merged_dir / "DONE").write_text(json.dumps(stats, indent=2) + "\n")
    log(f"merge: finished in {time.time() - t0:.1f}s")
    mark_stage(work_dir, manifest, "merge", stats)
    # merge done ⇒ score must re-run
    manifest["stages"]["score"] = None
    save_manifest(work_dir, manifest)


# ---------------------------------------------------------------------------
# Score stage (KN) — stream top-level; hold lower orders in memory
# ---------------------------------------------------------------------------


@dataclass
class CtxFollowers:
    """Mutable follower map for one context while streaming a sorted n-gram file."""

    followers: dict[str, int]


def iter_grouped_ngrams(path: Path) -> Iterator[tuple[str, dict[str, int]]]:
    """Stream merged nK.tsv (sorted by ctx) → (context_str, {follower: count})."""
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
                cur[w] = cur.get(w, 0) + c  # should already be unique post-merge
            else:
                yield cur_ctx, cur
                cur_ctx, cur = ctx, {w: c}
        if cur_ctx is not None:
            yield cur_ctx, cur


def load_ngrams_nested(path: Path) -> dict[tuple[str, ...], dict[str, int]]:
    """Load full order-k file into memory (used for k < order)."""
    out: dict[tuple[str, ...], dict[str, int]] = {}
    for ctx_s, followers in iter_grouped_ngrams(path):
        ctx = tuple(ctx_s.split())
        out[ctx] = followers
    return out


def build_unigram_continuation_p(
    bigrams: dict[tuple[str, ...], dict[str, int]],
) -> dict[str, float]:
    distinct_predecessors: dict[str, set[str]] = defaultdict(set)
    total_bigram_types = 0
    for ctx, followers in bigrams.items():
        w1 = ctx[0]
        for w2 in followers:
            distinct_predecessors[w2].add(w1)
            total_bigram_types += 1
    if total_bigram_types == 0:
        return {}
    return {
        w: len(preds) / total_bigram_types for w, preds in distinct_predecessors.items()
    }


def build_level_p(
    counts_k: dict[tuple[str, ...], dict[str, int]],
    lower_p: dict,
    lower_is_flat: bool,
    discount: float,
) -> dict:
    level_p: dict = {}
    for ctx, followers in counts_k.items():
        total = sum(followers.values())
        if total <= 0:
            continue
        distinct = len(followers)
        lam = (discount * distinct) / total
        lower_dist = lower_p if lower_is_flat else lower_p.get(ctx[1:], {})
        dist = {}
        for w, count in followers.items():
            discounted = max(count - discount, 0) / total
            dist[w] = discounted + lam * lower_dist.get(w, 0.0)
        level_p[ctx] = dist
    return level_p


def score_one(
    ctx: tuple[str, ...],
    followers: dict[str, int],
    order: int,
    lower_p: dict,
    unigram_cont_p: dict[str, float],
    discount: float,
    max_followers: int,
) -> dict | None:
    total = sum(followers.values())
    if total <= 0:
        return None
    distinct = len(followers)
    lam = (discount * distinct) / total
    lower_dist = lower_p if order == 2 else lower_p.get(ctx[1:], {})

    candidates = set(followers.keys()) | set(lower_dist.keys())
    scored: list[tuple[str, float]] = []
    for w in candidates:
        count = followers.get(w, 0)
        discounted = max(count - discount, 0) / total
        backoff = lower_dist.get(w, unigram_cont_p.get(w, 0.0))
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


def _write_json_object_streaming(
    out_path: Path, items: Iterator[tuple[str, dict]]
) -> int:
    """Write a single JSON object incrementally: {"k":{...},"k2":{...}}."""
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
            # key and value via json for correct escaping
            f.write(json.dumps(key, ensure_ascii=False))
            f.write(":")
            f.write(json.dumps(val, ensure_ascii=False, separators=(",", ":")))
            n += 1
            if n % 100_000 == 0:
                log(f"score: wrote {n} contexts...")
        f.write("}")
    tmp.replace(out_path)
    return n


def stage_score(
    args: argparse.Namespace, work_dir: Path, manifest: dict, output_path: Path
) -> None:
    if stage_done(manifest, "score") and output_path.exists() and not args.force:
        log("score: already done — skip")
        return
    if (
        not stage_done(manifest, "merge")
        and not (work_dir / "merged" / "DONE").exists()
    ):
        sys.exit("score: merge stage not done — run merge first")

    score_dir = work_dir / "score"
    score_dir.mkdir(parents=True, exist_ok=True)
    merged_dir = work_dir / "merged"
    order = args.order
    t0 = time.time()

    # Lower orders fully in RAM (needed for KN backoff graphs).
    # Top order is streamed from TSV so peak RAM ≈ bigrams + mid levels, not all N-grams.
    log("score: loading orders 2..N-1 into memory for KN backoff...")
    counts_lower: dict[int, dict] = {}
    for k in range(2, order):
        path = merged_dir / f"n{k}.tsv"
        log(f"score: load {path.name}...")
        counts_lower[k] = load_ngrams_nested(path)
        log(f"score: order-{k} contexts: {len(counts_lower[k])}")

    # order==2: top is bigrams; still need cont_p from bigrams themselves
    if order == 2:
        bigrams = load_ngrams_nested(merged_dir / "n2.tsv")
        log(f"score: order-2 contexts: {len(bigrams)}")
        unigram_cont_p = build_unigram_continuation_p(bigrams)
        lower_p: dict = unigram_cont_p

        def gen_items():
            for ctx, followers in bigrams.items():
                if (
                    args.min_ngram_count > 1
                    and sum(followers.values()) < args.min_ngram_count
                ):
                    continue
                entry = score_one(
                    ctx,
                    followers,
                    2,
                    lower_p,
                    unigram_cont_p,
                    args.discount,
                    args.max_followers,
                )
                if entry:
                    yield " ".join(ctx), entry

        n = _write_json_object_streaming(output_path, gen_items())
    else:
        bigrams = counts_lower[2]
        log("score: building unigram continuation P (KN)...")
        unigram_cont_p = build_unigram_continuation_p(bigrams)
        level_p: dict = unigram_cont_p
        for k in range(2, order):
            log(f"score: building order-{k} KN backoff distribution...")
            level_p = build_level_p(
                counts_lower[k], level_p, lower_is_flat=(k == 2), discount=args.discount
            )
            # free raw counts for this level once converted (keep bigrams only if needed — not)
            if k > 2:
                del counts_lower[k]

        top_path = merged_dir / f"n{order}.tsv"
        log(f"score: streaming {top_path.name} → {output_path}...")

        def gen_items():
            for ctx_s, followers in iter_grouped_ngrams(top_path):
                if (
                    args.min_ngram_count > 1
                    and sum(followers.values()) < args.min_ngram_count
                ):
                    continue
                ctx = tuple(ctx_s.split())
                entry = score_one(
                    ctx,
                    followers,
                    order,
                    level_p,
                    unigram_cont_p,
                    args.discount,
                    args.max_followers,
                )
                if entry:
                    yield ctx_s, entry

        n = _write_json_object_streaming(output_path, gen_items())

    size_kb = output_path.stat().st_size / 1024
    log(
        f"score: {n} contexts → {output_path} ({size_kb:.0f} KB) in {time.time() - t0:.1f}s"
    )
    (score_dir / "DONE").write_text(
        json.dumps({"contexts": n, "output": str(output_path)}) + "\n"
    )
    mark_stage(work_dir, manifest, "score", {"contexts": n, "output": str(output_path)})


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    p = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--order", type=int, required=True, help="N-gram order >= 2")
    p.add_argument(
        "--work-dir", default=None, help="Durable stage dir (default: <output>.work)"
    )
    p.add_argument("--discount", type=float, default=DISCOUNT)
    p.add_argument("--max-followers", type=int, default=MAX_FOLLOWERS)
    p.add_argument("--min-ngram-count", type=int, default=MIN_NGRAM_COUNT)
    p.add_argument(
        "--workers",
        type=int,
        default=max(1, min(4, (os.cpu_count() or 2) - 1)),
        help="Count-stage processes (default: min(4, cpu-1)). Merge/score are single-process.",
    )
    p.add_argument(
        "--spill-entries",
        type=int,
        default=DEFAULT_SPILL_ENTRIES,
        help="Approx in-memory (ctx,follower) pairs per worker before spilling a sorted run.",
    )
    p.add_argument(
        "--stage",
        choices=("all", "count", "merge", "score"),
        default="all",
        help="Run one stage or all remaining (default: all).",
    )
    p.add_argument(
        "--force",
        action="store_true",
        help="Re-run the selected stage(s) even if marked done.",
    )
    return p.parse_args(argv)


def main(argv: list[str] | None = None) -> None:
    args = parse_args(argv)
    if args.order < 2:
        sys.exit("--order must be >= 2")
    if args.workers < 1:
        sys.exit("--workers must be >= 1")

    input_path = Path(args.input)
    if not input_path.is_file():
        sys.exit(f"input not found: {input_path}")

    output_path = Path(args.output)
    work_dir = (
        Path(args.work_dir) if args.work_dir else Path(str(output_path) + ".work")
    )
    work_dir.mkdir(parents=True, exist_ok=True)

    log(f"work-dir: {work_dir}")
    manifest = ensure_manifest(work_dir, args, input_path)

    stages = ["count", "merge", "score"] if args.stage == "all" else [args.stage]
    for name in stages:
        if name == "count":
            stage_count(args, input_path, work_dir, manifest)
            manifest = load_manifest(work_dir)
        elif name == "merge":
            stage_merge(args, work_dir, manifest)
            manifest = load_manifest(work_dir)
        elif name == "score":
            stage_score(args, work_dir, manifest, output_path)
            manifest = load_manifest(work_dir)

    log("done.")


if __name__ == "__main__":
    main()
