#!/usr/bin/env python3
"""
Download an English word frequency list and emit word<TAB>frequency pairs
sorted by frequency descending, suitable for piping into build-trie.js.

Source: hermitdave/FrequencyWords (CC BY-SA 4.0)
  https://github.com/hermitdave/FrequencyWords

Format of source file: "word count" one per line.

Usage:
    python scripts/gen_wordlist.py | node tools/build-trie.js > android/app/src/main/assets/en.trie

Options (env vars):
    MIN_FREQ  — minimum raw count to include (default 5)
    MAX_WORDS — maximum words to emit (default 60000)
"""

import os
import sys
import urllib.request
from pathlib import Path

SOURCE_URL = (
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords"
    "/master/content/2018/en/en_full.txt"
)
CACHE_FILE = Path(__file__).parent / "en_freq.txt"
MIN_FREQ  = int(os.environ.get("MIN_FREQ",  "5"))
MAX_WORDS = int(os.environ.get("MAX_WORDS", "60000"))


def download_if_needed():
    if not CACHE_FILE.exists():
        print(f"Downloading word frequency list ...", file=sys.stderr)
        urllib.request.urlretrieve(SOURCE_URL, CACHE_FILE)
        print("Done.", file=sys.stderr)
    else:
        print(f"Using cached {CACHE_FILE}", file=sys.stderr)


def main():
    download_if_needed()
    entries = []
    with CACHE_FILE.open(encoding="utf-8", errors="ignore") as f:
        for line in f:
            parts = line.strip().split()
            if len(parts) < 2:
                continue
            word, raw = parts[0], parts[1]
            word = word.lower()
            if not word.isalpha():
                continue
            if len(word) < 2 or len(word) > 20:
                continue
            try:
                count = int(raw)
            except ValueError:
                continue
            if count < MIN_FREQ:
                continue
            entries.append((word, count))

    # Sort by count descending; assign rank-based frequency score.
    # We normalize to 1..1000000 range so the score fits in u32 easily.
    entries.sort(key=lambda x: -x[1])
    entries = entries[:MAX_WORDS]

    if not entries:
        print("No entries found — check source file.", file=sys.stderr)
        sys.exit(1)

    max_count = entries[0][1]
    for word, count in entries:
        # Scale to 1..1_000_000 so common words score high
        score = max(1, round(count / max_count * 1_000_000))
        print(f"{word}\t{score}")

    print(f"Emitted {len(entries)} words (max_count={max_count})", file=sys.stderr)


if __name__ == "__main__":
    main()
