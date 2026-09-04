#!/usr/bin/env python3
"""
Adapter: converts an AOSP .combined dictionary file to the word<TAB>count TSV
format expected by compile_cklm.py --unigrams.

AOSP format (one entry per line):
  dictionary=...,locale=...,description=...,date=...,version=...  (header, skip)
   word=<word>,f=<freq>,flags=<flags>,originalFreq=<freq>

Output:
  <word>\t<freq>
sorted descending by frequency.

Usage:
  python3 aosp_combined_to_tsv.py input.combined output.tsv
"""

import sys
import re

def parse_combined(path: str):
    entries = []
    with open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('dictionary='):
                continue
            m = re.search(r'word=([^,]+),f=(\d+)', line)
            if not m:
                continue
            word = m.group(1).strip().lower()
            freq = int(m.group(2))
            if word and freq > 0:
                entries.append((word, freq))
    # deduplicate keeping max freq, sort descending
    best = {}
    for word, freq in entries:
        if freq > best.get(word, 0):
            best[word] = freq
    return sorted(best.items(), key=lambda x: -x[1])

def main():
    if len(sys.argv) != 3:
        print(f"Usage: {sys.argv[0]} <input.combined> <output.tsv>")
        sys.exit(1)
    entries = parse_combined(sys.argv[1])
    with open(sys.argv[2], 'w', encoding='utf-8') as f:
        for word, freq in entries:
            f.write(f"{word}\t{freq}\n")
    print(f"Wrote {len(entries):,} entries to {sys.argv[2]}")

if __name__ == '__main__':
    main()
