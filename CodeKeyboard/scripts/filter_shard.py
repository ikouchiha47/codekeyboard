#!/usr/bin/env python3
"""
Option A (ADR-012): filter structural subtitle noise from the existing sample
shards in place. Removes credit/header lines, sound-effect brackets, and
all-proper-noun credit blocks — while KEEPING proper nouns that appear in
real dialogue (names/places users actually type).

This is a fast re-clean of the already-sampled shards (no re-stream of the
441M-line source). It complements prep_corpus.py's speaker-label + min-words
cleaning.

Usage:
    python3 scripts/filter_shards.py \
        --in /var/.../adr012_sample10 \
        --out /var/.../adr012_sample10_clean
"""

import argparse
import re
import sys
from pathlib import Path

# Credit/header verbs — a line starting with these is movie metadata, not dialogue.
CREDIT_RE = re.compile(
    r"^(presented|produced|directed|written|starring|screenplay|based on|"
    r"in association|executive producer|music by|edited by|cinematography|"
    r"casting by|costume|production|sound|visual effects|special effects|"
    r"makeup|art direction|set decoration|story by|adapted by|co-produced|"
    r"co-directed|co-written|narrated by|featuring|with the voice|"
    r"original story|additional dialogue|translated by|subtitles by|"
    r"distributed by|released by|a .* production|a .* film|a .* picture)\b",
    re.IGNORECASE,
)

# Bracket / sound-effect lines: [laughs], (sighs), ♪ music ♪, <i>, etc.
BRACKET_RE = re.compile(r"[\[\]()<>♪♫]")

# A line that is ONLY capitalized words (every token Title-Case) is likely a
# credit block / name list, not dialogue. We drop these. Handles hyphens and
# apostrophes in names (e.g. "Jeon Ji-hyun Cha Tae-hyun", "O'Brien").
ALL_TITLE_CASE_RE = re.compile(r"^[A-Z][a-zA-Z'\-]*([ ][A-Z][a-zA-Z'\-]*)+$")

# A line that is a single all-caps word (e.g. "WHAT", "HELLO") is dialogue
# emphasis — KEEP it. Only drop multi-word all-title-case credit blocks.


def is_credit(line: str) -> bool:
    return bool(CREDIT_RE.match(line.strip()))


def has_bracket(line: str) -> bool:
    return bool(BRACKET_RE.search(line))


def is_all_title_case(line: str) -> bool:
    # Drop only if it looks like a name/credit list: 2+ capitalized words,
    # no lowercase words, no punctuation.
    return bool(ALL_TITLE_CASE_RE.match(line.strip()))


def keep(line: str) -> bool:
    line = line.strip()
    if not line:
        return False
    if is_credit(line):
        return False
    if has_bracket(line):
        return False
    if is_all_title_case(line):
        return False
    return True


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--input", required=True, help="Dir of shards to filter")
    p.add_argument("--out", required=True, help="Output dir for filtered shards")
    args = p.parse_args()

    inp = Path(args.input)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)

    total = kept = dropped_credit = dropped_bracket = dropped_title = 0
    shards = sorted(inp.glob("shard_*.txt"))
    for shard in shards:
        out_shard = out / shard.name
        with shard.open(encoding="utf-8", errors="ignore") as fin, \
                out_shard.open("w", encoding="utf-8") as fout:
            for raw in fin:
                line = raw.rstrip("\n")
                total += 1
                if not line.strip():
                    continue
                if is_credit(line):
                    dropped_credit += 1
                    continue
                if has_bracket(line):
                    dropped_bracket += 1
                    continue
                if is_all_title_case(line):
                    dropped_title += 1
                    continue
                fout.write(line + "\n")
                kept += 1
        print(f"  {shard.name}: done", file=sys.stderr)

    print(f"total={total} kept={kept} dropped_credit={dropped_credit} "
          f"dropped_bracket={dropped_bracket} dropped_title={dropped_title}", file=sys.stderr)
    print(f"Wrote {len(shards)} filtered shards -> {out}", file=sys.stderr)


if __name__ == "__main__":
    main()