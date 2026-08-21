#!/usr/bin/env python3
"""
Builds trigrams.json — P(w3 | w1, w2) — using the same absolute-discounting
+ backoff smoothing family as extract_bigrams_v2.py, extended one level
deeper: trigram counts back off to this script's OWN bigram distribution,
which backs off to this script's OWN unigram distribution.

## Why everything is built from one corpus, in one pass

extract_bigrams_v2.py sources its unigram backoff from en.trie — the
production dictionary's own frequency data — which is fine for a single
extra level. Here it matters more: en.trie and bigrams.json were built from
different, formal-register corpora (Google-Books-derived Norvig counts; see
docs/adr-001-bigram-prediction.md's "Consequences" section: "Norvig corpus
is Google Books (formal text) — suggestions skew literary rather than
conversational"). If this script backed its trigram counts off of THOSE
existing bigram/unigram sources, the register mismatch would compound
across three levels instead of one.

Instead, this script counts unigrams, bigrams, AND trigrams from the exact
same input corpus (OpenSubtitles casual/spoken text — see
android/scripts/download_opensubtitles.py and clean_opensubtitles.py) in a
single pass. Every level of the backoff chain is internally consistent in
register, by construction, rather than stitched together from three
differently-sourced files.

## Smoothing: true interpolated Kneser-Ney (Chen & Goodman, cs/0108005)

    P(w3 | w1,w2) = max(count(w1,w2,w3) - d, 0) / total(w1,w2)
                    + lambda(w1,w2) * P_KN(w3 | w2)

    P_KN(w2 | w1) = max(count(w1,w2) - d, 0) / total(w1)
                    + lambda(w1) * P_cont(w2)

    P_cont(w) = N1+(*, w) / N1+(*, *)     <- continuation probability, NOT
                                              raw unigram frequency

    lambda(ctx) = d * distinct_followers(ctx) / total(ctx)

Absolute discounting subtracts a fixed discount `d` from every observed
count before normalizing, so thin/noisy evidence is automatically pulled
down — same as extract_bigrams_v2.py. What makes this *true* Kneser-Ney
rather than the simplified version originally shipped here (see ADR-008
checkpoint log, 2026-08-16) is P_cont: the unigram backoff is NOT raw word
frequency, it's continuation probability — N1+(*, w), the number of
DISTINCT words w has ever followed, divided by the total number of
distinct bigram types in the corpus. This is the textbook fix for the
classic "Francisco" problem: "Francisco" is textually frequent (because
"San Francisco" occurs often), but a bad generic backoff guess anywhere
else, since it only ever continues ONE context. Raw frequency can't
distinguish "frequent because genuinely common" from "frequent because it
completes one very common phrase"; continuation count can, by
construction. The recursion is continuation-based end to end (trigram
backs off to a bigram estimate that itself backs off to continuation
probability), not just at the bottom level.

## --min-trigram-count

Trigram contexts are inherently much sparser than bigram contexts (many
more distinct (w1,w2) pairs than distinct w1's, for the same corpus size).
On a small or moderately-sized corpus, a large fraction of observed trigram
contexts will have exactly one occurrence — pure noise, not a real pattern.
`--min-trigram-count` (default 1, i.e. no extra filtering) lets a caller
raise the floor to drop singleton-count contexts entirely when working
with a corpus too small to trust them, without touching the smoothing math
itself.

## Output shape (matches bigrams.json's shape, one level up)

    { "w1 w2": [["w3", score], ["w3b", score], ...], ... }

Context key is the two previous words, space-joined, lowercase.

Usage:
    python3 scripts/extract_trigrams.py \
        --input android/scripts/corpus_raw/opensubtitles/en_sample_cleaned.txt \
        --output android/app/src/main/assets/trigrams.json \
        --discount 0.75 \
        --max-followers 10
"""

import argparse
import json
import os
import re
import sys
from collections import defaultdict
from pathlib import Path

DISCOUNT = 0.75  # standard absolute-discounting constant (Chen & Goodman)
MAX_FOLLOWERS = 10
MIN_TRIGRAM_COUNT = 1

WORD_RE = re.compile(r"^[a-z']+$")


def is_clean(w: str) -> bool:
    return bool(WORD_RE.match(w)) and 1 <= len(w) <= 30


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", default="android/scripts/corpus_raw/opensubtitles/en_sample_cleaned.txt")
    p.add_argument("--output", default="android/app/src/main/assets/trigrams.json")
    p.add_argument("--discount", type=float, default=DISCOUNT)
    p.add_argument("--max-followers", type=int, default=MAX_FOLLOWERS,
                   help="Max followers stored per (w1,w2) context.")
    p.add_argument("--min-trigram-count", type=int, default=MIN_TRIGRAM_COUNT,
                   help="Drop trigram contexts whose total observed count is below "
                        "this floor before scoring — avoids keeping pure-noise "
                        "singleton contexts when the corpus is small. Default 1 "
                        "(no extra filtering beyond 'observed at least once').")
    return p.parse_args()


def tokenize(line: str) -> list[str]:
    words = [w.lower() for w in line.split()]
    return [w for w in words if is_clean(w)]


def build_counts(input_path: Path):
    unigrams: dict[str, int] = defaultdict(int)
    bigrams: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    trigrams: dict[tuple[str, str], dict[str, int]] = defaultdict(lambda: defaultdict(int))

    with input_path.open(encoding="utf-8", errors="ignore") as f:
        for line in f:
            words = tokenize(line)
            for w in words:
                unigrams[w] += 1
            for i in range(len(words) - 1):
                bigrams[words[i]][words[i + 1]] += 1
            for i in range(len(words) - 2):
                trigrams[(words[i], words[i + 1])][words[i + 2]] += 1

    return unigrams, bigrams, trigrams


def build_unigram_continuation_p(bigrams: dict[str, dict[str, int]]) -> dict[str, float]:
    """True Kneser-Ney continuation probability: P_cont(w) = N1+(*, w) / N1+(*, *).

    N1+(*, w) = number of DISTINCT words that precede w as a bigram (not how
    often w occurs overall). This is what correctly demotes a word like
    "francisco" — textually frequent (via "san francisco"), but a bad
    generic backoff guess anywhere else, since it only ever continues ONE
    context. Raw unigram frequency (what we shipped originally) can't tell
    these apart; continuation count can, by construction.
    """
    distinct_predecessors: dict[str, set[str]] = defaultdict(set)
    total_bigram_types = 0
    for w1, followers in bigrams.items():
        for w2 in followers:
            distinct_predecessors[w2].add(w1)
            total_bigram_types += 1

    return {w: len(preds) / total_bigram_types for w, preds in distinct_predecessors.items()}


def build_bigram_kn_p(bigrams: dict[str, dict[str, int]], unigram_cont_p: dict[str, float],
                       discount: float) -> dict[str, dict[str, float]]:
    """Full interpolated-KN P_KN(w2|w1) distribution for every observed w1 —
    needed as the backoff source for the trigram level below.

    P_KN(w2|w1) = max(count(w1,w2) - D, 0) / count(w1)
                  + D * distinct_followers(w1) / count(w1) * P_cont(w2)
    """
    bigram_p: dict[str, dict[str, float]] = {}
    for w1, followers in bigrams.items():
        total = sum(followers.values())
        distinct = len(followers)
        lam = (discount * distinct) / total
        dist = {}
        for w2, count in followers.items():
            discounted = max(count - discount, 0) / total
            dist[w2] = discounted + lam * unigram_cont_p.get(w2, 0.0)
        bigram_p[w1] = dist
    return bigram_p


def score_trigram_followers(w1: str, w2: str, followers: dict[str, int],
                             bigram_kn_p: dict[str, dict[str, float]],
                             unigram_cont_p: dict[str, float], discount: float) -> list[tuple[str, float]]:
    """P_KN(w3|w1,w2) = max(count(w1,w2,w3) - D, 0) / count(w1,w2)
                         + D * distinct_followers(w1,w2) / count(w1,w2) * P_KN(w3|w2)

    Backoff is the bigram-level KN estimate (itself backed by unigram
    continuation probability), not a raw frequency — the recursion is
    continuation-based end to end, per Chen & Goodman's interpolated KN,
    not just at the bottom level.

    Candidate generation (the actual fix, 2026-08-17): candidates are
    observed trigram followers UNION bigram followers of w2, not just
    observed trigram followers. Without this, backoff can only re-rank
    words already seen as a literal (w1,w2,w3) trigram — it can never
    surface a word that has real bigram-level support (e.g. "know" after
    "me") but was never observed as this exact trigram (e.g. "let me
    know" never occurred, even though "me know" and "let me ask/tell/see"
    did) — which was the actual cause of the "let me know" / "as soon as
    possible" regressions in ADR-008's checkpoint log, not a data-volume
    problem. A bigram-only candidate gets count=0 here, so its discounted
    term is 0 and its entire score comes from backoff — exactly the
    "no direct evidence, but real support one level down" case KN is
    supposed to handle.
    """
    total = sum(followers.values()) or 1
    distinct = len(followers)
    lam = (discount * distinct) / total if total > 0 else 0.0
    w2_dist = bigram_kn_p.get(w2, {})

    candidates = set(followers.keys()) | set(w2_dist.keys())

    scored = []
    for w3 in candidates:
        count = followers.get(w3, 0)
        discounted = max(count - discount, 0) / total
        backoff = w2_dist.get(w3, unigram_cont_p.get(w3, 0.0))
        score = discounted + lam * backoff
        scored.append((w3, score))
    scored.sort(key=lambda p: -p[1])
    return scored


def main():
    args = parse_args()
    input_path = Path(args.input)

    print(f"Reading {input_path}...", file=sys.stderr)
    unigrams, bigrams, trigrams = build_counts(input_path)
    print(f"Vocab size (distinct unigrams): {len(unigrams)}", file=sys.stderr)
    print(f"Distinct bigram contexts (w1): {len(bigrams)}", file=sys.stderr)
    print(f"Distinct trigram contexts (w1,w2) before min-count filter: {len(trigrams)}", file=sys.stderr)

    if args.min_trigram_count > 1:
        trigrams = {
            ctx: followers for ctx, followers in trigrams.items()
            if sum(followers.values()) >= args.min_trigram_count
        }
        print(f"Distinct trigram contexts after min-count filter "
              f"(>= {args.min_trigram_count}): {len(trigrams)}", file=sys.stderr)

    print("Building unigram continuation-probability distribution (true Kneser-Ney)...", file=sys.stderr)
    unigram_cont_p = build_unigram_continuation_p(bigrams)

    print("Building full bigram KN backoff distribution...", file=sys.stderr)
    bigram_kn_p = build_bigram_kn_p(bigrams, unigram_cont_p, args.discount)

    print("Scoring trigram contexts...", file=sys.stderr)
    result = {}
    distinct_trigrams = 0
    for (w1, w2), followers in trigrams.items():
        scored = score_trigram_followers(w1, w2, followers, bigram_kn_p, unigram_cont_p, args.discount)
        distinct_trigrams += len(followers)
        top = scored[:args.max_followers]
        if not top:
            continue
        max_score = top[0][1] or 1.0
        # "support" = total observed count for this context, needed by Ngram's
        # cascade (ADR-008 task L) to compare confidence across tiers — the
        # per-context score normalization above makes every context's top
        # candidate read as 1.0 regardless of how much evidence backs it, so
        # this is stored as a separate, un-normalized field alongside it.
        result[f"{w1} {w2}"] = {
            "followers": [[w3, round(s / max_score, 4)] for w3, s in top],
            "support": sum(followers.values()),
        }

    print(f"Trigram contexts in output: {len(result)}", file=sys.stderr)
    print(f"Total distinct trigrams observed: {distinct_trigrams}", file=sys.stderr)

    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)
    with output_path.open("w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    size_kb = os.path.getsize(output_path) / 1024
    print(f"Written to {output_path} ({size_kb:.0f} KB)", file=sys.stderr)


if __name__ == "__main__":
    main()
