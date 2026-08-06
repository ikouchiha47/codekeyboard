#!/usr/bin/env python3
"""
Extract top N English bigrams from Norvig's count_2w.txt and emit as compact JSON.

Usage:
    python3 scripts/extract_bigrams.py \
        --input /tmp/count_2w.txt \
        --output android/app/src/main/assets/bigrams.json \
        --top 30000

Output format:
    { "of": [["the", 0.92], ["a", 0.61], ...], ... }

Scores are log-normalized within each predecessor bucket so they're
comparable across words with different total follower counts.
"""

import argparse
import json
import math
import re
import sys
from collections import defaultdict

def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--input", default="/tmp/count_2w.txt")
    p.add_argument("--output", default="android/app/src/main/assets/bigrams.json")
    p.add_argument("--top", type=int, default=30000)
    p.add_argument("--max-followers", type=int, default=10,
                   help="Max followers stored per predecessor word")
    return p.parse_args()

WORD_RE = re.compile(r"^[a-z']+$")

def is_clean(w):
    return bool(WORD_RE.match(w)) and len(w) >= 1 and len(w) <= 30

def main():
    args = parse_args()

    print(f"Reading {args.input}...", file=sys.stderr)
    pairs = []
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
            pairs.append((w1, w2, count))

    # Sort by count descending, take top N
    pairs.sort(key=lambda x: -x[2])
    pairs = pairs[:args.top]
    print(f"Kept {len(pairs)} bigrams after filtering top {args.top}", file=sys.stderr)

    # Group by predecessor, merging duplicate (w1, w2) pairs
    raw_buckets = defaultdict(lambda: defaultdict(int))
    for w1, w2, count in pairs:
        raw_buckets[w1][w2] += count
    buckets = {w1: sorted(followers.items(), key=lambda x: -x[1])
               for w1, followers in raw_buckets.items()}

    # Normalize counts to scores within each bucket using log scale
    result = {}
    for w1, followers in buckets.items():
        # followers already sorted by count desc (pairs were sorted globally)
        total = sum(c for _, c in followers)
        scored = []
        for w2, count in followers[:args.max_followers]:
            # log probability, shifted to positive range [0, 1]
            score = math.log(count + 1) / math.log(total + 1)
            scored.append([w2, round(score, 4)])
        result[w1] = scored

    print(f"Unique predecessor words: {len(result)}", file=sys.stderr)

    with open(args.output, "w", encoding="utf-8") as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))

    import os
    size_kb = os.path.getsize(args.output) / 1024
    print(f"Written to {args.output} ({size_kb:.0f} KB)", file=sys.stderr)

if __name__ == "__main__":
    main()
