#!/usr/bin/env python3
"""Parse Google Books Ngrams v2 export files (per-year rows) into a single
collapsed count per n-gram, with POS tags and punctuation tokens stripped.

Format: `ngram\tyear\tmatch_count\tvolume_count` where ngram is space-joined
tokens each optionally suffixed `_TAG` (e.g. `A.K._NOUN`, `,_.`).
Source: http://storage.googleapis.com/books/ngrams/books/datasetsv3.html
(2012 v2 per-year export; distinct from the 2020 v3 aggregated export).

Usage:
    .venv/bin/python parse_google_books_ngrams.py \
        --input ~/Downloads/googlebooks-eng-all-4gram-20120701-ak \
        --output /tmp/gbooks_ak_4grams.json \
        --min-year 1990
"""
import argparse
import json
import re
from collections import defaultdict
from pathlib import Path

POS_SUFFIX = re.compile(r"_[A-Z.]+$")
WORD_RE = re.compile(r"^[a-zA-Z']+$")


def clean_token(tok):
    tok = POS_SUFFIX.sub("", tok)
    return tok.lower()


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    p.add_argument("--min-year", type=int, default=0,
                    help="Only sum counts from this year onward (0 = all years).")
    args = p.parse_args()

    totals = defaultdict(int)
    skipped_punct = 0

    with open(Path(args.input).expanduser()) as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 4:
                continue
            ngram, year, match_count, _vol = parts
            year = int(year)
            if year < args.min_year:
                continue
            tokens = [clean_token(t) for t in ngram.split(" ")]
            if not all(WORD_RE.match(t) for t in tokens):
                skipped_punct += 1
                continue
            key = " ".join(tokens)
            totals[key] += int(match_count)

    with open(args.output, "w") as f:
        json.dump(totals, f)

    print(f"{len(totals)} distinct clean n-grams written to {args.output}")
    print(f"skipped {skipped_punct} rows containing punctuation/non-word tokens")


if __name__ == "__main__":
    main()
