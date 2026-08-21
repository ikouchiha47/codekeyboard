#!/usr/bin/env python3
"""
Streams a kaikki.org Wiktionary English JSONL dump (~3.2GB, 1 entry per line
— too large to load into memory) and pulls out multi-word headword entries
as candidate phrases for expanding the curated eval fixture's
`next-word-phrase` category (see ADR-007).

Not every idiom worth having is its own Wiktionary headword ("as soon as
possible" isn't; "let me know" and "looking forward to" are — verified by
direct grep before writing this), so this is a candidate-generation pass,
not a finished fixture. Per the "what makes it curated" step: output goes
to a plain text file for manual accept/reject, not straight into the TSV.

Usage:
    python3 android/scripts/extract_wiktionary_phrases.py \
        --input ~/Downloads/kaikki.org-dictionary-English.jsonl \
        --output android/scripts/corpus_raw/wiktionary_phrase_candidates.txt \
        --min-words 3 --max-words 5
"""
import argparse
import json
import re
import sys
from pathlib import Path

WORD_RE = re.compile(r"^[a-zA-Z']+$")


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True)
    p.add_argument("--output", default=str(Path(__file__).parent / "corpus_raw" / "wiktionary_phrase_candidates.txt"))
    p.add_argument("--min-words", type=int, default=3)
    p.add_argument("--max-words", type=int, default=5)
    return p.parse_args()


def is_clean_phrase(words: list[str]) -> bool:
    return all(WORD_RE.match(w) for w in words)


def main():
    args = parse_args()
    input_path = Path(args.input).expanduser()
    output_path = Path(args.output)
    output_path.parent.mkdir(parents=True, exist_ok=True)

    seen = set()
    kept = 0
    total = 0

    with input_path.open(encoding="utf-8", errors="ignore") as f_in, \
            output_path.open("w", encoding="utf-8") as f_out:
        for line in f_in:
            total += 1
            if total % 200_000 == 0:
                print(f"  ...{total} entries scanned, {kept} phrase candidates so far", file=sys.stderr)
            line = line.strip()
            if not line:
                continue
            try:
                entry = json.loads(line)
            except json.JSONDecodeError:
                continue

            word = entry.get("word", "")
            if " " not in word:
                continue
            words = word.split()
            if not (args.min_words <= len(words) <= args.max_words):
                continue
            if not is_clean_phrase(words):
                continue
            phrase = word.lower()
            if phrase in seen:
                continue
            seen.add(phrase)

            pos = entry.get("pos", "")
            gloss = ""
            senses = entry.get("senses", [])
            if senses and senses[0].get("glosses"):
                gloss = senses[0]["glosses"][0]

            f_out.write(f"{phrase}\t{pos}\t{gloss}\n")
            kept += 1

    print(f"Scanned {total} entries, wrote {kept} unique phrase candidates to {output_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
