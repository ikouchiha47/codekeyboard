#!/usr/bin/env python3
"""
Collapses inflected near-duplicate phrase candidates (e.g. "get hold of" /
"gets hold of" / "got hold of" all being the same idiom, just conjugated
differently) using nltk's PorterStemmer, so manual review doesn't see the
same idiom multiple times. filter_phrase_candidates.py's regex only caught
explicit "plural of X"/"alternative form of X" glosses — this catches the
rest via stemming.

For each stemmed-phrase group, keeps the shortest surface form (usually the
base/dictionary form) as the representative.

Usage:
    python3 android/scripts/dedup_phrase_candidates.py \
        --input android/scripts/corpus_raw/wiktionary_phrase_candidates_filtered.txt \
        --output android/scripts/corpus_raw/wiktionary_phrase_candidates_deduped.txt
"""
import argparse

from nltk.stem import PorterStemmer


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True)
    p.add_argument("--output", required=True)
    return p.parse_args()


def main():
    args = parse_args()
    stemmer = PorterStemmer()

    groups: dict[str, list[str]] = {}
    lines_by_phrase: dict[str, str] = {}

    with open(args.input, encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            phrase = parts[0]
            stem_key = " ".join(stemmer.stem(w) for w in phrase.split())
            groups.setdefault(stem_key, []).append(phrase)
            lines_by_phrase[phrase] = line

    kept = 0
    with open(args.output, "w", encoding="utf-8") as f_out:
        for stem_key, phrases in groups.items():
            representative = min(phrases, key=len)
            f_out.write(lines_by_phrase[representative])
            kept += 1

    print(f"{len(lines_by_phrase)} filtered candidates -> {kept} after stem-based dedup")


if __name__ == "__main__":
    main()
