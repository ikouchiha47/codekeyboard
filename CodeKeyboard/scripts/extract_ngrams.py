#!/usr/bin/env python3
"""
Generalized, parallel n-gram extractor for order N >= 2 — the tool to reach
for when trying 4-gram (or deeper), instead of hand-rolling another
fixed-order script like extract_trigrams.py.

Same true interpolated Kneser-Ney smoothing family as extract_trigrams.py,
generalized to arbitrary depth:

    P_1(w) = P_cont(w) = N1+(*, w) / N1+(*, *)        <- always bigram-level
                                                           continuation counts,
                                                           regardless of N
    P_k(w_k | w_1..w_k-1) = max(count - d, 0) / total(w_1..w_k-1)
                             + lambda(w_1..w_k-1) * P_k-1(w_k | w_2..w_k-1)

    lambda(ctx) = d * distinct_followers(ctx) / total(ctx)

Built bottom-up: P_1 from bigram counts, P_2 from P_1, ... P_N-1 from P_N-2,
then the top (order-N) level is scored against P_N-1 as backoff, with the
same candidate-union fix as extract_trigrams.py (candidates = observed
order-N followers UNION the backoff distribution's keys — otherwise backoff
can only re-rank words already seen as this exact N-gram, never surface a
word with real (N-1)-gram support but no direct N-gram evidence).

## Parallel counting (the actual reason this is a separate file)

Counting is the slow part at real corpus size (extract_trigrams.py took
2+ hours single-threaded on a 4.27M-line/1.94M-context corpus). Counting is
embarrassingly parallel — same shape as the MapReduce n-gram literature
(Maskey, cited in ADR-008): split the corpus into N chunks, count each
chunk independently in a worker process (map), sum partial count dicts by
key across workers (reduce). This does that locally via multiprocessing —
no cluster needed at our corpus scale, same algorithmic shape as one.

Usage:
    python3 scripts/extract_ngrams.py \
        --input android/scripts/corpus_raw/swiftkey_all.txt \
        --output /tmp/fourgrams.json \
        --order 4 \
        --workers 8 \
        --min-ngram-count 3
"""
import argparse
import json
import multiprocessing as mp
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

DISCOUNT = 0.75
MAX_FOLLOWERS = 10
MIN_NGRAM_COUNT = 1

WORD_RE = re.compile(r"^[a-z']+$")


def is_clean(w: str) -> bool:
    return bool(WORD_RE.match(w)) and 1 <= len(w) <= 30


def tokenize(line: str) -> list[str]:
    return [w for w in (t.lower() for t in line.split()) if is_clean(w)]


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--order", type=int, required=True, help="N-gram order, e.g. 4 for 4-gram. Must be >= 2.")
    p.add_argument("--discount", type=float, default=DISCOUNT)
    p.add_argument("--max-followers", type=int, default=MAX_FOLLOWERS)
    p.add_argument("--min-ngram-count", type=int, default=MIN_NGRAM_COUNT,
                    help="Drop order-N contexts whose total observed count is below this floor.")
    p.add_argument("--workers", type=int, default=max(1, (os.cpu_count() or 2) - 1),
                    help="Parallel workers (default: cpu_count - 1). Memory no longer scales "
                         "with this — see merge_counts_streaming — but leave headroom for "
                         "whatever else is running on the machine regardless.")
    return p.parse_args()


def count_byte_range(args):
    """Each worker opens the file itself and reads only its own byte range —
    no raw text ever gets pickled between processes (only the much smaller
    resulting count dicts do). Replaces the earlier design where the parent
    read the whole file with f.readlines() and pickled line-list chunks out
    to workers, which meant every byte of the corpus got serialized twice
    (parent->worker) for zero counting benefit."""
    path, start, end, order = args
    counts = {k: defaultdict(lambda: defaultdict(int)) for k in range(2, order + 1)}
    unigrams = defaultdict(int)
    with open(path, "rb") as f:
        f.seek(start)
        chunk = f.read(end - start)
    for line in chunk.decode("utf-8", errors="ignore").splitlines():
        words = tokenize(line)
        for w in words:
            unigrams[w] += 1
        for k in range(2, order + 1):
            for i in range(len(words) - k + 1):
                ctx = tuple(words[i:i + k - 1])
                counts[k][ctx][words[i + k - 1]] += 1
    # Plain dicts for pickling back to the parent process.
    return unigrams, {k: {ctx: dict(f) for ctx, f in d.items()} for k, d in counts.items()}


def _byte_range_boundaries(path: Path, workers: int) -> list[tuple[int, int]]:
    """Split the file into `workers` roughly-equal byte ranges, each nudged
    forward to the next newline so no line is split across two ranges."""
    size = path.stat().st_size
    if workers <= 1 or size == 0:
        return [(0, size)]
    raw_bounds = [size * i // workers for i in range(1, workers)]
    bounds = [0]
    with path.open("rb") as f:
        for b in raw_bounds:
            f.seek(b)
            f.readline()  # consume the (possibly partial) line straddling b
            bounds.append(f.tell())
    bounds.append(size)
    return [(bounds[i], bounds[i + 1]) for i in range(len(bounds) - 1) if bounds[i] < bounds[i + 1]]


def merge_counts(partials, order):
    unigrams = defaultdict(int)
    merged = {k: defaultdict(lambda: defaultdict(int)) for k in range(2, order + 1)}
    for u, counts in partials:
        for w, c in u.items():
            unigrams[w] += c
        for k in range(2, order + 1):
            for ctx, followers in counts[k].items():
                for w, c in followers.items():
                    merged[k][ctx][w] += c
    return unigrams, merged


def build_counts_parallel(input_path: Path, order: int, workers: int):
    ranges = _byte_range_boundaries(input_path, workers)
    print(f"Counting {input_path.stat().st_size} bytes across {len(ranges)} byte-range "
          f"chunks ({workers} workers, no full-file read in the parent process)...", file=sys.stderr)

    tasks = [(str(input_path), start, end, order) for start, end in ranges]
    if workers == 1 or len(tasks) == 1:
        partials = (count_byte_range(t) for t in tasks)
        return merge_counts(partials, order)

    # imap_unordered streams results as each worker finishes, instead of
    # pool.map's behavior of materializing every partial into one list
    # before merging starts — that was the actual memory duplication (N
    # full partial-count dicts + the merged dict all alive at once), not
    # worker count. merge_counts consumes this generator one item at a
    # time, so each partial is merged and freed before the next arrives.
    with mp.Pool(workers) as pool:
        partials = pool.imap_unordered(count_byte_range, tasks)
        return merge_counts(partials, order)


def build_unigram_continuation_p(bigrams: dict) -> dict[str, float]:
    """P_cont(w) = N1+(*, w) / N1+(*, *) — always defined at the bigram
    level regardless of the target order N (this is Kneser-Ney's actual
    definition, not a per-order quantity)."""
    distinct_predecessors: dict[str, set] = defaultdict(set)
    total_bigram_types = 0
    for ctx, followers in bigrams.items():
        w1 = ctx[0]
        for w2 in followers:
            distinct_predecessors[w2].add(w1)
            total_bigram_types += 1
    return {w: len(preds) / total_bigram_types for w, preds in distinct_predecessors.items()}


def build_level_p(counts_k: dict, lower_p: dict, lower_is_flat: bool, discount: float) -> dict:
    """Build P_k(w_k | ctx) for every observed ctx at this level, backing
    off to lower_p keyed by ctx[1:] (drop the earliest word) — the same
    recursive-drop pattern extract_trigrams.py hardcodes for k=2,3.

    lower_is_flat: True only when lower_p is unigram_cont_p itself (the
    k=2 call) — that distribution has no context (it's word->prob, not
    context->word->prob), so it must be used directly rather than indexed
    by ctx[1:], which would always be the empty tuple and always miss.
    """
    level_p = {}
    for ctx, followers in counts_k.items():
        total = sum(followers.values())
        distinct = len(followers)
        lam = (discount * distinct) / total
        lower_dist = lower_p if lower_is_flat else lower_p.get(ctx[1:], {})
        dist = {}
        for w, count in followers.items():
            discounted = max(count - discount, 0) / total
            dist[w] = discounted + lam * lower_dist.get(w, 0.0)
        level_p[ctx] = dist
    return level_p


def _score_one(ctx, followers, order, lower_p, unigram_cont_p, discount):
    total = sum(followers.values())
    distinct = len(followers)
    lam = (discount * distinct) / total
    # order == 2: lower_p IS unigram_cont_p, a flat word->prob dict (no
    # per-context nesting) — treat it as the backoff distribution directly.
    lower_dist = lower_p if order == 2 else lower_p.get(ctx[1:], {})

    candidates = set(followers.keys()) | set(lower_dist.keys())
    scored = []
    for w in candidates:
        count = followers.get(w, 0)
        discounted = max(count - discount, 0) / total
        backoff = lower_dist.get(w, unigram_cont_p.get(w, 0.0))
        scored.append((w, discounted + lam * backoff))
    scored.sort(key=lambda p: (-p[1], p[0]))  # alphabetical tie-break: deterministic
                                                # regardless of set iteration order,
                                                # which differs from extract_trigrams.py
                                                # since candidates come from parallel-
                                                # merged chunks here, not one pass
    top = scored[:MAX_FOLLOWERS]
    if not top:
        return None
    max_score = top[0][1] or 1.0
    entry = {
        "followers": [[w, round(s / max_score, 4)] for w, s in top],
        "support": total,
    }
    return entry, len(followers)


# Set once per worker via Pool(initializer=...) so lower_p/unigram_cont_p
# (potentially large — millions of entries) are pickled to each worker ONCE,
# not re-pickled on every chunk the way passing them as map() args would.
_worker_state = {}


def _init_scoring_worker(order, lower_p, unigram_cont_p, discount):
    _worker_state.update(order=order, lower_p=lower_p, unigram_cont_p=unigram_cont_p, discount=discount)


def _score_chunk(chunk):
    s = _worker_state
    result = {}
    total_observed = 0
    for ctx, followers in chunk:
        scored = _score_one(ctx, followers, s["order"], s["lower_p"], s["unigram_cont_p"], s["discount"])
        if scored is None:
            continue
        entry, count = scored
        result[" ".join(ctx)] = entry
        total_observed += count
    return result, total_observed


def score_top_level(order: int, counts_top: dict, lower_p: dict, unigram_cont_p: dict,
                     discount: float, workers: int):
    items = list(counts_top.items())
    if workers == 1 or len(items) < workers * 100:
        result, total_observed = _score_chunk_inline(items, order, lower_p, unigram_cont_p, discount)
        return result, total_observed

    chunk_size = max(1, len(items) // workers)
    chunks = [items[i:i + chunk_size] for i in range(0, len(items), chunk_size)]
    with mp.Pool(workers, initializer=_init_scoring_worker,
                 initargs=(order, lower_p, unigram_cont_p, discount)) as pool:
        partials = pool.map(_score_chunk, chunks)

    result = {}
    total_observed = 0
    for r, t in partials:
        result.update(r)
        total_observed += t
    return result, total_observed


def _score_chunk_inline(items, order, lower_p, unigram_cont_p, discount):
    result = {}
    total_observed = 0
    for ctx, followers in items:
        scored = _score_one(ctx, followers, order, lower_p, unigram_cont_p, discount)
        if scored is None:
            continue
        entry, count = scored
        result[" ".join(ctx)] = entry
        total_observed += count
    return result, total_observed


def main():
    args = parse_args()
    if args.order < 2:
        sys.exit("--order must be >= 2")
    input_path = Path(args.input)

    unigrams, counts = build_counts_parallel(input_path, args.order, args.workers)
    print(f"Vocab size (distinct unigrams): {len(unigrams)}", file=sys.stderr)
    for k in range(2, args.order + 1):
        print(f"Distinct order-{k} contexts: {len(counts[k])}", file=sys.stderr)

    if args.min_ngram_count > 1:
        top = args.order
        before = len(counts[top])
        counts[top] = {
            ctx: f for ctx, f in counts[top].items()
            if sum(f.values()) >= args.min_ngram_count
        }
        print(f"Order-{top} contexts after min-count filter (>= {args.min_ngram_count}): "
              f"{len(counts[top])} (dropped {before - len(counts[top])})", file=sys.stderr)

    print("Building unigram continuation-probability distribution (true Kneser-Ney)...", file=sys.stderr)
    unigram_cont_p = build_unigram_continuation_p(counts[2])

    # Build P_2 .. P_(order-1) bottom-up. P_2's backoff is unigram_cont_p
    # (a flat dict keyed by word, not by context tuple) — backoff_context_len=0
    # signals build_level_p to treat lower_p as that flat distribution.
    level_p = unigram_cont_p
    for k in range(2, args.order):
        print(f"Building order-{k} KN backoff distribution...", file=sys.stderr)
        level_p = build_level_p(counts[k], level_p, lower_is_flat=(k == 2), discount=args.discount)

    print(f"Scoring order-{args.order} contexts...", file=sys.stderr)
    result, total_observed = score_top_level(args.order, counts[args.order], level_p, unigram_cont_p,
                                              args.discount, args.workers)

    print(f"Order-{args.order} contexts in output: {len(result)}", file=sys.stderr)
    print(f"Total distinct order-{args.order} n-grams observed: {total_observed}", file=sys.stderr)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    size_kb = os.path.getsize(output_path) / 1024
    print(f"Written to {output_path} ({size_kb:.0f} KB)", file=sys.stderr)


if __name__ == "__main__":
    main()
