#!/usr/bin/env python3
"""
Cleans a raw OpenSubtitles sample (see download_opensubtitles.py) into plain
sentence-ish lines suitable for n-gram counting.

Subtitle text carries artifacts that don't reflect real running English
prose and would pollute n-gram counts if left in:

1. Bare speaker-label lines — a line that is *only* a name followed by a
   colon (e.g. "JOHN:", "Mary Ann:"), left over from formatted dialogue
   transcripts. These aren't sentences at all and would inject junk
   "words" (the names) into the corpus if kept.
2. Leading "- " dialogue-turn markers — subtitle convention for indicating
   a new speaker within the same subtitle frame (e.g. "- I don't know.").
   The dash isn't part of the sentence; stripping it prevents "- word"
   bigrams/trigrams that no one would ever actually type.
3. Very short lines (fewer than 3 words after the above cleanup) — mostly
   sound-effect cues ("[laughs]"), interjections, or fragments left over
   from subtitle timing splits that don't carry enough context to be
   useful n-gram material.

Deliberately NOT done: profanity or content filtering of any kind. This is
a conscious choice, not an oversight — OpenSubtitles' casual/spoken register
is exactly what makes it useful here (see download_opensubtitles.py's
docstring), and filtering words out after the fact would just reintroduce
a register bias in a different direction. The IME does not moderate what
users type, so its suggestion corpus should not either.

On a 3M-line raw sample, cleaning typically keeps roughly ~2.29M lines,
drops ~15k as bare speaker labels, and drops ~697k as too-short — actual
counts vary by sample and are printed to stderr on every run.

Usage:
    python3 android/scripts/clean_opensubtitles.py \
        --input android/scripts/corpus_raw/opensubtitles/en_sample.txt \
        --output android/scripts/corpus_raw/opensubtitles/en_sample_cleaned.txt
"""

import argparse
import re
import sys
from pathlib import Path

SPEAKER_LABEL_RE = re.compile(r"^[A-Z][a-zA-Z' ]{0,20}:\s*$")
DASH_PREFIX_RE = re.compile(r"^-\s*")
MIN_WORDS = 3


def parse_args():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    default_dir = Path(__file__).parent / "corpus_raw" / "opensubtitles"
    p.add_argument("--input", default=str(default_dir / "en_sample.txt"))
    p.add_argument("--output", default=str(default_dir / "en_sample_cleaned.txt"))
    p.add_argument("--min-words", type=int, default=MIN_WORDS,
                   help="Drop lines with fewer than this many words after cleanup.")
    return p.parse_args()


def clean_line(line: str) -> str | None:
    """Returns the cleaned line, or None if it should be dropped as a bare
    speaker label. Short-line dropping is handled by the caller (needs the
    min-words threshold, kept as a separate counted reason)."""
    line = line.strip()
    if not line:
        return None
    if SPEAKER_LABEL_RE.match(line):
        return None
    return DASH_PREFIX_RE.sub("", line).strip()


def main():
    args = parse_args()
    in_path = Path(args.input)
    out_path = Path(args.output)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    kept = 0
    dropped_speaker_label = 0
    dropped_too_short = 0

    print(f"Reading {in_path}...", file=sys.stderr)
    with in_path.open(encoding="utf-8", errors="ignore") as f_in, \
            out_path.open("w", encoding="utf-8") as f_out:
        for raw_line in f_in:
            raw_line = raw_line.rstrip("\n")
            if not raw_line.strip():
                continue

            cleaned = clean_line(raw_line)
            if cleaned is None:
                dropped_speaker_label += 1
                continue

            if len(cleaned.split()) < args.min_words:
                dropped_too_short += 1
                continue

            f_out.write(cleaned + "\n")
            kept += 1

    total = kept + dropped_speaker_label + dropped_too_short
    print(f"Input lines processed: {total}", file=sys.stderr)
    print(f"  kept:                    {kept}", file=sys.stderr)
    print(f"  dropped (speaker label): {dropped_speaker_label}", file=sys.stderr)
    print(f"  dropped (too short):     {dropped_too_short}", file=sys.stderr)
    print(f"Written to {out_path}", file=sys.stderr)


if __name__ == "__main__":
    main()
