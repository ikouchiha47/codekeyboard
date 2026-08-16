#!/usr/bin/env python3
"""
Rebuilds bigrams.json using absolute-discounting smoothing with unigram
backoff, replacing extract_bigrams.py's raw log-frequency-ratio scoring.
extract_bigrams.py is kept as-is (not deleted) — this is a parallel,
independently invokable build, not a destructive rewrite.

## Why (see ADR-001 addendum / ADR-007 checkpoint log for the full story)

Two things were empirically wrong with the original build, found via the
eval harness (autocomplete_eval_cases_generated.tsv, 621 real-text
next-word cases):

1. extract_bigrams.py takes the GLOBAL top-N bigram pairs by raw count,
   THEN groups by predecessor word. Global raw-count ranking is dominated
   by function-word pairs ("of the", "in a") in every context, so a
   less-common predecessor word (e.g. "mind") often has ALL of its pairs
   fall outside the global top-N — it ends up with a thin or all-function-
   word follower list not because "mind" has no good continuations, but
   because none of its pairs won the global popularity contest.

2. Naively fixing #1 by grouping the FULL corpus per-word first (no
   global cut) trades one failure for another: words with very few
   observed bigram occurrences get their thin, noisy counts trusted as
   if they were solid signal, which measurably hurt curated (common
   phrase) accuracy in a real A/B test (69.2% -> 33.3% on next-word
   curated cases) even though it helped the generated fixture.

## What this does instead

Standard n-gram smoothing (Chen & Goodman, "An Empirical Study of
Smoothing Techniques for Language Modeling", cs/0108005 — the reference
survey this technique family is drawn from; Gboard's own production LM is
documented as a Katz-smoothed interpolated 5-gram, the same family):

    P(w2 | w1) = max(count(w1,w2) - d, 0) / total(w1)
                 + lambda(w1) * P_unigram(w2)

  - Absolute discounting: subtract a fixed discount d from every observed
    count before normalizing. Thin, noisy evidence gets pulled down
    automatically instead of being trusted at face value.
  - lambda(w1) = d * distinct_followers(w1) / total(w1) — the probability
    mass "freed" by discounting, redistributed toward the backoff
    (unigram) distribution instead of vanishing.
  - P_unigram(w2) is sourced from en.trie (the production dictionary's own
    frequency data — TrieReader below mirrors Trie.kt, same as
    android/scripts/gen_autocomplete_corpus.py's copy), not a second
    external corpus.

This only rescoes words already observed as followers of w1 (doesn't
invent new candidates) — a deliberately conservative scope so this stays
a targeted fix, not a rewrite of the candidate-generation logic.

Content-word preference (the is_stop downweighting from
android/scripts/rescore_bigrams.py) is layered on AFTER this smoothing
step, not merged into it — smoothing answers "how much do we trust this
count", content-word preference answers "which trusted candidate is more
useful to show", and conflating them made both harder to reason about
independently. rescore_bigrams.py is left untouched and still works
standalone on any already-built bigrams.json.

Usage:
    python3 scripts/extract_bigrams_v2.py \
        --input /path/to/count_2w.txt \
        --output android/app/src/main/assets/bigrams.json \
        --trie android/app/src/main/assets/en.trie
"""

import argparse
import json
import math
import re
import struct
import sys
from collections import defaultdict
from pathlib import Path

DISCOUNT = 0.75  # standard absolute-discounting constant (Chen & Goodman)
MAX_FOLLOWERS = 10
STOPWORD_PENALTY = 0.5  # same constant as rescore_bigrams.py

WORD_RE = re.compile(r"^[a-z']+$")


def is_clean(w: str) -> bool:
    return bool(WORD_RE.match(w)) and 1 <= len(w) <= 30


class TrieReader:
    """Minimal read-only parser for the TRIF binary format (mirrors
    Trie.kt / the copy in android/scripts/gen_autocomplete_corpus.py) —
    used here only to source the unigram backoff distribution."""

    HEADER_SIZE = 12
    NODE_SIZE = 12

    def __init__(self, path: Path):
        self.buf = path.read_bytes()
        if self.buf[:4] != b"TRIF":
            raise ValueError(f"Unsupported trie format: {self.buf[:4]!r}")
        self.node_count = struct.unpack_from("<i", self.buf, 8)[0]
        self.children_base = self.HEADER_SIZE + self.node_count * self.NODE_SIZE

    def _flags(self, idx):
        return self.buf[self.HEADER_SIZE + idx * self.NODE_SIZE + 1]

    def _children_off(self, idx):
        return struct.unpack_from("<i", self.buf, self.HEADER_SIZE + idx * self.NODE_SIZE + 2)[0]

    def _freq(self, idx):
        return struct.unpack_from("<i", self.buf, self.HEADER_SIZE + idx * self.NODE_SIZE + 6)[0]

    def word_freqs(self) -> dict[str, int]:
        freqs = {}
        stack = [(0, "")]
        while stack:
            idx, prefix = stack.pop()
            flags = self._flags(idx)
            if flags & 1 and prefix:
                freqs[prefix] = self._freq(idx)
            if flags & 2:
                block_off = self.children_base + self._children_off(idx)
                child_count = self.buf[block_off]
                for i in range(child_count):
                    base = block_off + 1 + i * 4
                    ch = chr(self.buf[base])
                    b = base + 1
                    cidx = self.buf[b] | (self.buf[b + 1] << 8) | (self.buf[b + 2] << 16)
                    stack.append((cidx, prefix + ch))
        return freqs


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--input", default="/tmp/count_2w.txt")
    p.add_argument("--output", default="android/app/src/main/assets/bigrams.json")
    p.add_argument("--trie", default="android/app/src/main/assets/en.trie")
    p.add_argument("--discount", type=float, default=DISCOUNT)
    p.add_argument("--max-followers", type=int, default=MAX_FOLLOWERS)
    p.add_argument("--no-stopword-penalty", action="store_true",
                    help="Skip the content-word-preference layer, smoothing only.")
    return p.parse_args()


def load_unigram_backoff(trie_path: Path) -> dict[str, float]:
    freqs = TrieReader(trie_path).word_freqs()
    total = sum(freqs.values())
    return {w: c / total for w, c in freqs.items()}


def get_is_stop_fn():
    import spacy
    nlp = spacy.load("en_core_web_sm", disable=["tagger", "parser", "ner", "lemmatizer"])
    cache: dict[str, bool] = {}

    def is_stop(word: str) -> bool:
        cached = cache.get(word)
        if cached is None:
            cached = nlp.vocab[word].is_stop
            cache[word] = cached
        return cached

    return is_stop


def main():
    args = parse_args()

    print(f"Reading {args.input}...", file=sys.stderr)
    buckets: dict[str, dict[str, int]] = defaultdict(lambda: defaultdict(int))
    with open(args.input, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            parts = line.rsplit("\t", 1)
            if len(parts) != 2:
                continue
            phrase, count_str = parts
            try:
                count = int(count_str)
            except ValueError:
                continue
            words = phrase.lower().split()
            if len(words) != 2:
                continue
            w1, w2 = words
            if not is_clean(w1) or not is_clean(w2):
                continue
            buckets[w1][w2] += count
    print(f"Unique predecessor words (no global cut): {len(buckets)}", file=sys.stderr)

    print(f"Loading unigram backoff from {args.trie}...", file=sys.stderr)
    unigram_p = load_unigram_backoff(Path(args.trie))

    is_stop = None if args.no_stopword_penalty else get_is_stop_fn()

    result = {}
    for w1, followers in buckets.items():
        total = sum(followers.values())
        distinct = len(followers)
        # Probability mass freed by discounting every observed count by
        # `discount`, redistributed via the unigram backoff below.
        lam = (args.discount * distinct) / total

        scored = []
        for w2, count in followers.items():
            discounted = max(count - args.discount, 0) / total
            backoff = lam * unigram_p.get(w2, 0.0)
            score = discounted + backoff
            if is_stop is not None and is_stop(w2):
                score *= STOPWORD_PENALTY
            scored.append((w2, score))

        scored.sort(key=lambda p: -p[1])
        top = scored[:args.max_followers]
        if top:
            # Normalize the kept slice to a 0..1-ish range for JSON compactness,
            # matching the original file's rounded-float convention.
            max_score = top[0][1] or 1.0
            result[w1] = [[w2, round(s / max_score, 4)] for w2, s in top]

    print(f"Unique predecessor words in output: {len(result)}", file=sys.stderr)

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    import os
    size_kb = os.path.getsize(args.output) / 1024
    print(f"Written to {args.output} ({size_kb:.0f} KB)", file=sys.stderr)


if __name__ == "__main__":
    main()
