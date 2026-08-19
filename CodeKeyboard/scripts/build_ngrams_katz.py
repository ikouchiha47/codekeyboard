#!/usr/bin/env python3
"""
build_ngrams.py — build bigram/trigram suggestion data from a large corpus.

Five stages. Each reads what the previous one wrote. Nothing holds the whole
corpus or the whole count table in RAM at once.

  STEP 1  split      Cut the input into one byte range per worker. Ranges are
                     aligned to line boundaries (seek, then skip the partial
                     line with readline, then record f.tell) so no worker ever
                     owns half a line.

  STEP 2  count      Each worker reads its range, counts unigrams/bigrams/
                     trigrams in a dict. When the dict reaches --spill-entries
                     unique keys it sorts JUST that chunk and writes one "run"
                     file, then clears and continues. Result: many small sorted
                     run files under runs/.

  STEP 3  reconcile  K-way merge (min-heap) all run files for each order. The
                     same n-gram can appear in several runs, so equal keys are
                     summed and written into SQLite with an upsert that
                     increments the count of duplicates. Result: counts.sqlite.

  STEP 4  normalize  Read counts.sqlite, compute Katz helper values (Good-Turing
                     count-of-counts histograms per order, unigram MLE
                     probabilities) and put them in a second SQLite file.
                     Result: normalized.sqlite.

  STEP 5  score      Walk contexts in sorted order (min-heap merge), compute
                     Katz backoff scores, and stream the JSON outputs.
                     Result: trigrams.json, bigrams.json, bigrams_support.json.

Why spill to runs instead of one big dict? Sorting 10M+ pairs in a single
worker hangs for hours. Sorting small bounded chunks and k-way merging them is
streaming and finishes. Same idea as `sort | uniq -c` and MapReduce.
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
from pathlib import Path

DISCOUNT = 0.75
MAX_FOLLOWERS = 10
DEFAULT_SPILL_ENTRIES = 2_000_000
MIN_NGRAM_COUNT = 3
MIN_BIGRAM_COUNT = 2
WORD_RE = re.compile(r"^[a-z']+$")


def log(msg: str) -> None:
    print(msg, file=sys.stderr, flush=True)


def is_clean(w: str) -> bool:
    return bool(WORD_RE.match(w)) and 1 <= len(w) <= 30


def tokenize(line: str) -> list[str]:
    return [w for w in (t.lower() for t in line.split()) if is_clean(w)]


def tune_sqlite(conn: sqlite3.Connection, *, readonly: bool = False) -> None:
    """Tune a SQLite connection for this build.

    The same set of PRAGMAs Rails 7.1 ships as its SQLite default (WAL +
    synchronous=NORMAL + capped journal + page cache + mmap), with the cache
    and map sizes bumped for a batch workload:

      journal_mode=WAL         fast, crash-consistent writes; readers don't block
      synchronous=NORMAL       the safe-and-fast companion to WAL
      journal_size_limit=64MB  keep the WAL file from growing unbounded
      cache_size=64MB          fewer disk reads for GROUP BY / ORDER BY / lookups
      mmap_size=128MB          memory-mapped reads (big win in the score step)
    """
    if not readonly:
        conn.execute("PRAGMA journal_mode=WAL")
        conn.execute("PRAGMA synchronous=NORMAL")
        conn.execute("PRAGMA journal_size_limit=67108864")  # 64 MB
    conn.execute("PRAGMA cache_size=-67108864")             # 64 MB page cache
    conn.execute("PRAGMA mmap_size=134217728")              # 128 MB mmap I/O


# =====================================================================
# STEP 1 — split the input into line-aligned byte ranges
# =====================================================================
def split_ranges(path: Path, workers: int) -> list[tuple[int, int]]:
    size = path.stat().st_size
    if workers <= 1 or size == 0:
        return [(0, size)]
    cuts = [0]
    with path.open("rb") as f:
        for i in range(1, workers):
            f.seek(size * i // workers)
            f.readline()            # drop the partial line we landed in
            cuts.append(f.tell())   # now at a clean line start
    cuts.append(size)
    return [(cuts[i], cuts[i + 1]) for i in range(len(cuts) - 1) if cuts[i] < cuts[i + 1]]


# =====================================================================
# STEP 2 — count in parallel, spilling sorted runs to disk
# =====================================================================
def _write_run(runs_dir: Path, worker_id: int, run_id: int, order: int,
               unigrams: dict, counts: dict) -> None:
    tag = f"w{worker_id:03d}_r{run_id:04d}"
    with (runs_dir / f"{tag}.uni.tsv").open("w", encoding="utf-8") as f:
        for w in sorted(unigrams):
            f.write(f"{w}\t{unigrams[w]}\n")
    for k in range(2, order + 1):
        rows = sorted((" ".join(key[:-1]), key[-1], c) for key, c in counts[k].items())
        with (runs_dir / f"{tag}.n{k}.tsv").open("w", encoding="utf-8") as f:
            for ctx, w, c in rows:
                f.write(f"{ctx}\t{w}\t{c}\n")


def count_worker(task: tuple) -> dict:
    path_s, start, end, order, worker_id, runs_dir_s, spill_entries = task
    runs_dir = Path(runs_dir_s)
    done = runs_dir / f"w{worker_id:03d}.done"
    if done.exists():
        return {"worker_id": worker_id, "skipped": True}

    t0 = time.time()
    unigrams: dict[str, int] = {}
    counts: dict[int, dict[tuple, int]] = {k: {} for k in range(2, order + 1)}
    unique = 0
    run_id = 0
    n_lines = 0

    def spill() -> None:
        nonlocal unigrams, counts, unique, run_id
        if unique == 0:
            return
        _write_run(runs_dir, worker_id, run_id, order, unigrams, counts)
        run_id += 1
        unigrams = {}
        counts = {k: {} for k in range(2, order + 1)}
        unique = 0

    # Read this worker's whole range at once (bounded, ~size/workers bytes),
    # then process line by line. No incremental buffer slicing.
    with open(path_s, "rb") as f:
        f.seek(start)
        data = f.read(end - start)
    for raw in data.split(b"\n"):
        n_lines += 1
        words = tokenize(raw.decode("utf-8", errors="ignore"))
        if not words:
            continue
        for w in words:
            if w not in unigrams:
                unique += 1
            unigrams[w] = unigrams.get(w, 0) + 1
        for k in range(2, order + 1):
            d = counts[k]
            for i in range(len(words) - k + 1):
                key = tuple(words[i:i + k])
                if key not in d:
                    unique += 1
                d[key] = d.get(key, 0) + 1
        if unique >= spill_entries:
            spill()
    spill()  # final partial run

    done.write_text(json.dumps({"worker_id": worker_id, "lines": n_lines, "runs": run_id}))
    log(f"count w{worker_id:03d}: lines={n_lines} runs={run_id} in {time.time()-t0:.0f}s")
    return {"worker_id": worker_id, "lines": n_lines, "runs": run_id}


def step_count(args, input_path: Path, runs_dir: Path) -> None:
    runs_dir.mkdir(parents=True, exist_ok=True)
    ranges = split_ranges(input_path, args.workers)
    log(f"[2/5 count] {len(ranges)} ranges, workers={args.workers}, "
        f"spill_entries={args.spill_entries}")
    tasks = [(str(input_path), s, e, args.order, i, str(runs_dir), args.spill_entries)
             for i, (s, e) in enumerate(ranges)]
    t0 = time.time()
    if len(tasks) == 1:
        [count_worker(t) for t in tasks]
    else:
        with mp.get_context("spawn").Pool(len(tasks)) as pool:
            pool.map(count_worker, tasks)
    log(f"[2/5 count] done in {time.time()-t0:.0f}s")


# =====================================================================
# STEP 3 — reconcile: k-way merge runs, sum duplicates into SQLite
# =====================================================================
def _iter_run(path: Path, n_cols: int):
    with path.open(encoding="utf-8") as f:
        for line in f:
            line = line.rstrip("\n")
            if not line:
                continue
            parts = line.split("\t")
            yield tuple(parts[:n_cols]), int(parts[n_cols])


def _merge_into_table(conn, table: str, run_files: list[Path], n_key_cols: int) -> int:
    """K-way merge sorted run files, summing equal keys, insert into `table`."""
    gens = [_iter_run(p, n_key_cols) for p in run_files if p.stat().st_size]
    if not gens:
        return 0
    # The merge below sums adjacent equal keys, so every emitted key is unique;
    # a plain INSERT is correct and skips a per-row conflict check.
    if n_key_cols == 1:
        insert = f"INSERT INTO {table}(w, count) VALUES(?, ?)"
    else:
        insert = f"INSERT INTO {table}(ctx, w, count) VALUES(?, ?, ?)"
    n = 0
    batch = []
    cur_key, cur_count = None, 0
    for key, count in heapq.merge(*gens, key=lambda x: x[0]):
        if key == cur_key:
            cur_count += count
            continue
        if cur_key is not None:
            batch.append((*cur_key, cur_count))
            n += 1
            if len(batch) >= 100_000:
                conn.executemany(insert, batch)
                batch = []
        cur_key, cur_count = key, count
    if cur_key is not None:
        batch.append((*cur_key, cur_count))
        n += 1
    if batch:
        conn.executemany(insert, batch)
    conn.commit()
    return n


def step_reconcile(args, runs_dir: Path, counts_db: Path) -> None:
    if counts_db.exists():
        counts_db.unlink()
    conn = sqlite3.connect(str(counts_db))
    tune_sqlite(conn)
    # Bulk-load pattern: heap tables with NO index, stream the merged rows in,
    # then build each index once at the end. Maintaining a PRIMARY KEY index
    # across ~20M inserts is the slow way; a single post-load index build is
    # nearly free because the merge emits rows already sorted by key.
    conn.execute("CREATE TABLE unigrams(w TEXT, count INTEGER)")
    conn.execute("CREATE TABLE bigrams(ctx TEXT, w TEXT, count INTEGER)")
    conn.execute("CREATE TABLE trigrams(ctx TEXT, w TEXT, count INTEGER)")

    uni_runs = sorted(runs_dir.glob("w*_r*.uni.tsv"))
    n_uni = _merge_into_table(conn, "unigrams", uni_runs, 1)
    log(f"[3/5 reconcile] unigrams={n_uni} from {len(uni_runs)} runs")

    for k, table in ((2, "bigrams"), (3, "trigrams")):
        if k > args.order:
            break
        runs = sorted(runs_dir.glob(f"w*_r*.n{k}.tsv"))
        n = _merge_into_table(conn, table, runs, 2)
        log(f"[3/5 reconcile] {table}={n} from {len(runs)} runs")

    log("[3/5 reconcile] building indexes")
    conn.execute("CREATE INDEX idx_uni ON unigrams(w)")
    conn.execute("CREATE INDEX idx_bi ON bigrams(ctx, w)")
    if args.order >= 3:
        conn.execute("CREATE INDEX idx_tri ON trigrams(ctx, w)")
    conn.commit()
    conn.close()


# =====================================================================
# STEP 4 — normalize: Katz helper values into a second SQLite
# =====================================================================
def step_normalize(args, counts_db: Path, norm_db: Path) -> None:
    if norm_db.exists():
        norm_db.unlink()
    src = sqlite3.connect(f"file:{counts_db}?mode=ro", uri=True)
    tune_sqlite(src, readonly=True)
    dst = sqlite3.connect(str(norm_db))
    tune_sqlite(dst)
    dst.execute("CREATE TABLE uni_p(w TEXT PRIMARY KEY, p REAL)")
    dst.execute("CREATE TABLE hist(ngram_order INTEGER, r INTEGER, n INTEGER, PRIMARY KEY(ngram_order, r))")

    # Unigram MLE probabilities p(w) = c(w) / N — the lowest-order backoff.
    uni_total = src.execute("SELECT SUM(count) FROM unigrams").fetchone()[0] or 1
    dst.executemany(
        "INSERT INTO uni_p(w, p) VALUES(?, ?)",
        ((w, c / uni_total) for w, c in src.execute("SELECT w, count FROM unigrams")),
    )

    # Good-Turing count-of-counts histograms per order: N_r = # types with
    # count r. Katz discounts are derived from these in the score step.
    for order, table in ((2, "bigrams"), (3, "trigrams")):
        if order > args.order:
            break
        dst.executemany(
            "INSERT INTO hist(ngram_order, r, n) VALUES(?, ?, ?)",
            ((order, r, n) for r, n in
             src.execute(f"SELECT count, COUNT(*) FROM {table} GROUP BY count")),
        )
    dst.commit()
    dst.close()
    src.close()
    log("[4/5 normalize] done")


# =====================================================================
# STEP 5 — score: Katz backoff, streamed to JSON
# =====================================================================
def katz_discounts(hist: dict[int, int], k: int = 5) -> dict[int, float]:
    """Good-Turing/Katz discounts d_r from a count-of-counts histogram.

    d_r = ((r+1)/r) * (N_{r+1}/N_r) / ((k+1)*N_{k+1}/N_1)   for r <= k
    d_r = 1                                                  for r > k
    """
    n1 = hist.get(1, 0)
    nk1 = hist.get(k + 1, 0)
    if n1 == 0 or nk1 == 0:
        return {r: 1.0 for r in hist}
    ratio = (k + 1) * nk1 / n1
    d = {}
    for r in hist:
        if r > k:
            d[r] = 1.0
        else:
            nr = hist.get(r, 0)
            nr1 = hist.get(r + 1, 0)
            if nr == 0 or nr1 == 0:
                d[r] = 1.0
            else:
                d[r] = min(((r + 1) / r) * (nr1 / nr) / ratio, 1.0)
    return d


def score_bigram(followers: dict, d_bi: dict, max_f: int):
    """Katz bigram: discounted MLE for seen followers (same shape as KN build).

    The bigram output only contains words that actually follow the context,
    matching the KN build's output structure. The unigram backoff only matters
    inside the trigram backoff distribution (see `backoff`), not here.
    """
    total = sum(followers.values())
    if total <= 0:
        return None
    scored = [(w, d_bi.get(c, 1.0) * c / total) for w, c in followers.items()]
    scored.sort(key=lambda p: (-p[1], p[0]))
    top = scored[:max_f]
    mx = top[0][1] or 1.0
    return [[w, round(s / mx, 4)] for w, s in top], total


def score_trigram(followers: dict, backoff: dict, d_tri: dict, max_f: int):
    """Katz trigram: discounted MLE for seen, beta * bigram-backoff for unseen."""
    total = sum(followers.values())
    if total <= 0:
        return None
    seen = [(w, d_tri.get(c, 1.0) * c / total) for w, c in followers.items()]
    seen_mass = sum(p for _, p in seen)
    seen_bo = sum(backoff.get(w, 0.0) for w in followers)
    beta = (1 - seen_mass) / max(1 - seen_bo, 1e-12)
    scored = seen + [(w, beta * p) for w, p in backoff.items() if w not in followers]
    scored.sort(key=lambda p: (-p[1], p[0]))
    top = scored[:max_f]
    mx = top[0][1] or 1.0
    return {"followers": [[w, round(s / mx, 4)] for w, s in top], "support": total}


def _grouped(cursor):
    """Yield (ctx, {follower: count}) from a cursor ordered by ctx."""
    cur_ctx, fol = None, {}
    for ctx, w, c in cursor:
        if ctx != cur_ctx:
            if cur_ctx is not None:
                yield cur_ctx, fol
            cur_ctx, fol = ctx, {}
        fol[w] = fol.get(w, 0) + c
    if cur_ctx is not None:
        yield cur_ctx, fol


def step_score(args, counts_db: Path, norm_db: Path, output: Path) -> None:
    counts = sqlite3.connect(f"file:{counts_db}?mode=ro", uri=True)
    tune_sqlite(counts, readonly=True)
    norm = sqlite3.connect(f"file:{norm_db}?mode=ro", uri=True)
    tune_sqlite(norm, readonly=True)
    uni_p = dict(norm.execute("SELECT w, p FROM uni_p"))
    hist = {order: {r: n for r, n in
                    norm.execute("SELECT r, n FROM hist WHERE ngram_order=?", (order,))}
            for order in (2, 3)}
    d_bi = katz_discounts(hist.get(2, {}))
    d_tri = katz_discounts(hist.get(3, {}))
    top_uni = heapq.nlargest(args.max_followers, uni_p.items(), key=lambda x: x[1])
    log(f"[5/5 score] uni_p={len(uni_p)} d_bi[1]={d_bi.get(1):.4f} d_tri[1]={d_tri.get(1):.4f}")

    output.parent.mkdir(parents=True, exist_ok=True)

    # ---- bigrams.json + bigrams_support.json ----
    if args.output_bigrams:
        bi_path = Path(args.output_bigrams)
        sup_path = Path(args.output_bigrams_support) if args.output_bigrams_support else None
        bi_tmp = bi_path.with_suffix(".json.tmp")
        sup_tmp = sup_path.with_suffix(".json.tmp") if sup_path else None
        n_bi = 0
        with bi_tmp.open("w", encoding="utf-8") as bf, \
             (sup_tmp.open("w", encoding="utf-8") if sup_tmp else open(os.devnull, "w")) as sf:
            bf.write("{"); sf.write("{")
            first_bi = first_sup = True
            for ctx, fol in _grouped(counts.execute(
                    "SELECT ctx, w, count FROM bigrams ORDER BY ctx, w")):
                total = sum(fol.values())
                if total < args.min_bigram_count:
                    continue
                got = score_bigram(fol, d_bi, args.max_followers)
                if not got:
                    continue
                scored, tot = got
                if not first_bi:
                    bf.write(",")
                bf.write(json.dumps(ctx, ensure_ascii=False) + ":" +
                         json.dumps(scored, ensure_ascii=False, separators=(",", ":")))
                first_bi = False
                n_bi += 1
                if sup_tmp:
                    if not first_sup:
                        sf.write(",")
                    sf.write(json.dumps(ctx, ensure_ascii=False) + ":" + str(tot))
                    first_sup = False
            bf.write("}"); sf.write("}")
        bi_tmp.replace(bi_path)
        if sup_tmp and sup_path:
            sup_tmp.replace(sup_path)
        log(f"[5/5 score] bigrams={n_bi}")

    # ---- trigrams.json ----
    if args.order >= 3:
        tri_tmp = output.with_suffix(".json.tmp")
        n_tri = 0
        # Memoized Katz bigram backoff distribution per last word w2, pruned to
        # its top max_followers entries. Same two techniques as the KN build:
        #   * memoization  — backoff(w2) depends only on w2, and many trigram
        #     contexts share the same w2, so compute it once per distinct w2.
        #   * top-k pruning — an unseen word in the trigram context scores
        #     exactly beta * P_backoff(w|w2), i.e. it is ranked by backoff
        #     probability alone, so anything below the top max_followers of the
        #     backoff can never reach the top max_followers of the result.
        # This bounds every trigram context's work to followers + max_followers.
        backoff_cache: dict[str, dict] = {}

        def backoff(w2: str) -> dict:
            hit = backoff_cache.get(w2)
            if hit is not None:
                return hit
            bo: dict = {}
            followers = {w: c for w, c in counts.execute(
                "SELECT w, count FROM bigrams WHERE ctx=?", (w2,))}
            total = sum(followers.values())
            if total:
                seen = [(w, d_bi.get(c, 1.0) * c / total) for w, c in followers.items()]
                seen_mass = sum(p for _, p in seen)
                seen_uni = sum(uni_p.get(w, 0.0) for w in followers)
                beta = (1 - seen_mass) / max(1 - seen_uni, 1e-12)
                unseen = [(w, beta * p) for w, p in top_uni if w not in followers]
                bo = dict(heapq.nlargest(args.max_followers, seen + unseen,
                                         key=lambda x: x[1]))
            backoff_cache[w2] = bo
            return bo

        with tri_tmp.open("w", encoding="utf-8") as tf:
            tf.write("{")
            first = True
            for ctx, fol in _grouped(counts.execute(
                    "SELECT ctx, w, count FROM trigrams ORDER BY ctx, w")):
                total = sum(fol.values())
                if total < args.min_ngram_count:
                    continue
                w2 = ctx.split()[-1]
                entry = score_trigram(fol, backoff(w2), d_tri, args.max_followers)
                if not entry:
                    continue
                if not first:
                    tf.write(",")
                tf.write(json.dumps(ctx, ensure_ascii=False) + ":" +
                         json.dumps(entry, ensure_ascii=False, separators=(",", ":")))
                first = False
                n_tri += 1
                if n_tri % 200_000 == 0:
                    log(f"[5/5 score] trigrams {n_tri}")
            tf.write("}")
        tri_tmp.replace(output)
        log(f"[5/5 score] trigrams={n_tri}")

    counts.close()
    norm.close()


# =====================================================================
# main
# =====================================================================
def parse_args(argv=None):
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True)
    # Katz outputs are katz-prefixed by default so they never collide with the
    # KN build's bigrams.json / bigrams_support.json / trigrams.json on S3 or
    # when copied back into the app assets (KN files live under assets/kn/).
    p.add_argument("--output", default="katz_trigrams.json", help="trigrams.json path")
    p.add_argument("--output-bigrams", default="katz_bigrams.json")
    p.add_argument("--output-bigrams-support", default="katz_bigrams_support.json")
    p.add_argument("--order", type=int, default=3)
    p.add_argument("--work-dir", default=None)
    p.add_argument("--workers", type=int, default=max(1, min(4, (mp.cpu_count() or 2) - 1)))
    p.add_argument("--spill-entries", type=int, default=DEFAULT_SPILL_ENTRIES)
    p.add_argument("--discount", type=float, default=DISCOUNT)
    p.add_argument("--max-followers", type=int, default=MAX_FOLLOWERS)
    p.add_argument("--min-ngram-count", type=int, default=MIN_NGRAM_COUNT)
    p.add_argument("--min-bigram-count", type=int, default=MIN_BIGRAM_COUNT)
    p.add_argument("--stage", choices=("all", "count", "reconcile", "normalize", "score"),
                   default="all")
    p.add_argument("--force", action="store_true")
    p.add_argument("--keep-runs", action="store_true")
    return p.parse_args(argv)


def _marker(work: Path, name: str) -> Path:
    return work / f"{name}.done"


def main(argv=None):
    args = parse_args(argv)
    if args.order < 2:
        sys.exit("--order must be >= 2")
    input_path = Path(args.input)
    if not input_path.is_file():
        sys.exit(f"missing input {input_path}")
    output = Path(args.output)
    work = Path(args.work_dir) if args.work_dir else Path(str(output) + ".work")
    work.mkdir(parents=True, exist_ok=True)
    runs_dir = work / "runs"
    counts_db = work / "counts.sqlite"
    norm_db = work / "normalized.sqlite"

    def need(name: str) -> bool:
        return args.force or not _marker(work, name).exists()

    stages = ["count", "reconcile", "normalize", "score"] if args.stage == "all" else [args.stage]

    if "count" in stages and need("count"):
        step_count(args, input_path, runs_dir)
        _marker(work, "count").write_text(str(time.time()))
        for down in ("reconcile", "normalize", "score"):
            _marker(work, down).unlink(missing_ok=True)
    if "reconcile" in stages and need("reconcile"):
        step_reconcile(args, runs_dir, counts_db)
        _marker(work, "reconcile").write_text(str(time.time()))
        for down in ("normalize", "score"):
            _marker(work, down).unlink(missing_ok=True)
        if not args.keep_runs:
            for f in runs_dir.glob("*"):
                f.unlink()
    if "normalize" in stages and need("normalize"):
        step_normalize(args, counts_db, norm_db)
        _marker(work, "normalize").write_text(str(time.time()))
        _marker(work, "score").unlink(missing_ok=True)
    if "score" in stages and need("score"):
        step_score(args, counts_db, norm_db, output)
        _marker(work, "score").write_text(str(time.time()))
    log("done.")


if __name__ == "__main__":
    main()
