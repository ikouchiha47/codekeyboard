#!/usr/bin/env python3
"""
Filters extract_wiktionary_phrases.py's raw candidate list down to something
a human can actually review (recipe's own "manually accept/reject 50-100"
step). The raw dump is dominated by legal/medical/scientific headwords and
plain inflection cross-references ("plural of X", "alternative form of Y")
that are useless as phone-keyboard eval targets.

Two filters:
  1. Drop glosses that are just inflection pointers (plural of / alternative
     form of / alternative spelling of / obsolete form of ...).
  2. Keep only phrases where every word is a top-N common word per
     scripts/en_freq.txt (the same hermitdave/FrequencyWords source en.trie
     itself is built from) — filters out jargon/technical vocabulary while
     keeping conversational phrases.

Usage:
    python3 android/scripts/filter_phrase_candidates.py \
        --input android/scripts/corpus_raw/wiktionary_phrase_candidates.txt \
        --freq scripts/en_freq.txt \
        --top-n 20000 \
        --output android/scripts/corpus_raw/wiktionary_phrase_candidates_filtered.txt
"""
import argparse
import re
from pathlib import Path

INFLECTION_RE = re.compile(
    r"^(plural of|alternative (form|spelling) of|obsolete (form|spelling) of|"
    r"archaic (form|spelling) of|past (tense|participle) of|present participle of|"
    r"third-person singular of|misspelling of)",
    re.IGNORECASE,
)


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True)
    p.add_argument("--freq", default="scripts/en_freq.txt")
    p.add_argument("--top-n", type=int, default=20000)
    p.add_argument("--output", required=True)
    return p.parse_args()


def load_common_words(freq_path: Path, top_n: int) -> set[str]:
    words = set()
    with freq_path.open(encoding="utf-8", errors="ignore") as f:
        for i, line in enumerate(f):
            if i >= top_n:
                break
            parts = line.split()
            if parts:
                words.add(parts[0].lower())
    return words


def main():
    args = parse_args()
    common = load_common_words(Path(args.freq), args.top_n)
    print(f"Loaded {len(common)} common words (top {args.top_n})")

    kept = 0
    total = 0
    with open(args.input, encoding="utf-8") as f_in, open(args.output, "w", encoding="utf-8") as f_out:
        for line in f_in:
            total += 1
            parts = line.rstrip("\n").split("\t")
            if len(parts) != 3:
                continue
            phrase, pos, gloss = parts
            if INFLECTION_RE.match(gloss):
                continue
            words = phrase.split()
            if not all(w in common for w in words):
                continue
            f_out.write(line)
            kept += 1

    print(f"{total} candidates -> {kept} after filtering, written to {args.output}")


if __name__ == "__main__":
    main()
