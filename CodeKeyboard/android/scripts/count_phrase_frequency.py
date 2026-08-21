#!/usr/bin/env python3
"""
Counts real occurrences of each Wiktionary phrase candidate (see
extract_wiktionary_phrases.py / filter_phrase_candidates.py /
dedup_phrase_candidates.py) directly against the OpenSubtitles corpus —
same corpus trigrams.json was built from (android/scripts/download_
opensubtitles.py + clean_opensubtitles.py) — via a single streaming pass
with a sliding window over each candidate's word length (3-5).

This replaces guessing at a synthetic frequency proxy with real usage
counts, in the same "phrase<TAB>count" shape as scripts/en_freq.txt
(hermitdave/FrequencyWords, what en.trie itself is built from) — so the
same build-trie.js tool can consume it identically. It also doubles as a
quality filter for free: a candidate phrase that occurs zero times in
11.2M lines of real dialogue is almost certainly Wiktionary noise (place
names, technical jargon — see the "dead women crossing" / "intelligent
transportation system" examples flagged during manual review) rather than
something people actually type.

Usage:
    python3 android/scripts/count_phrase_frequency.py \
        --candidates android/scripts/corpus_raw/wiktionary_phrase_candidates_deduped.txt \
        --corpus android/scripts/corpus_raw/opensubtitles/en_sample_big_cleaned.txt \
        --output scripts/en_wiki_freq.txt
"""
import argparse
import sys
from collections import defaultdict
from pathlib import Path


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--candidates", required=True)
    p.add_argument("--corpus", required=True)
    p.add_argument("--output", required=True)
    return p.parse_args()


def load_candidates(path: Path) -> dict[int, set[tuple[str, ...]]]:
    """Groups candidate phrases by word count, as tuples, for O(1) sliding-window lookup."""
    by_length: dict[int, set[tuple[str, ...]]] = defaultdict(set)
    with path.open(encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            phrase = parts[0]
            words = tuple(phrase.split())
            by_length[len(words)].add(words)
    return by_length


def main():
    args = parse_args()
    by_length = load_candidates(Path(args.candidates))
    lengths = sorted(by_length.keys())
    total_candidates = sum(len(v) for v in by_length.values())
    print(f"Loaded {total_candidates} candidate phrases across lengths {lengths}", file=sys.stderr)

    counts: dict[tuple[str, ...], int] = defaultdict(int)

    line_no = 0
    with open(args.corpus, encoding="utf-8", errors="ignore") as f:
        for line in f:
            line_no += 1
            if line_no % 1_000_000 == 0:
                print(f"  ...{line_no} corpus lines scanned", file=sys.stderr)
            words = line.split()
            if not words:
                continue
            n_words = len(words)
            for length in lengths:
                candidates_at_length = by_length[length]
                if n_words < length:
                    continue
                for i in range(n_words - length + 1):
                    window = tuple(w.lower() for w in words[i:i + length])
                    if window in candidates_at_length:
                        counts[window] += 1

    matched = len(counts)
    print(f"Scanned {line_no} corpus lines. {matched}/{total_candidates} candidates "
          f"matched at least once.", file=sys.stderr)

    ranked = sorted(counts.items(), key=lambda kv: -kv[1])
    with open(args.output, "w", encoding="utf-8") as f:
        for words, count in ranked:
            f.write(f"{' '.join(words)} {count}\n")

    print(f"Written to {args.output}", file=sys.stderr)


if __name__ == "__main__":
    main()
