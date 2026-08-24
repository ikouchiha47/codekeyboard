#!/usr/bin/env python3
"""
Task F (ADR-012): PMI/t-score phrase extraction from n-gram counts.

Ranks multi-word sequences by collocation strength (PMI + t-score + frequency
floor) rather than raw frequency, complementing the existing Wiktionary-
sourced phrase list (en_wiki_freq.txt). Input is the counts.sqlite produced
by build_ngrams.py (tables: bigrams, trigrams, fourgrams) — the same data the
4-gram pack is built from.

Scoring:
    PMI(w1..wk)  = log( P(seq) / prod(P(wi)) )          -- how much more likely
                                                          than chance
    t-score      = (P(seq) - prod(P(wi))) / sqrt(P(seq)) -- significance
    keep if PMI >= --min-pmi AND count >= --min-count AND length in 2..4

Filters:
    - fragments: leading/trailing stopwords removed (we keep the phrase if the
      remainder still has count >= floor)
    - subsequence: a phrase that is a substring of a longer kept phrase is
      dropped (longest wins)
    - all-alpha tokens only (no numbers/punct inside)

Output: phrase<TAB>score lines (same shape en_wiki_freq.txt uses, so the
existing build-trie.js / compile pipeline can consume it), plus a JSON with
the full stats.

Usage:
    python3 scripts/extract_phrases.py \
        --counts /var/.../work/counts.sqlite \
        --output scripts/en_adr012_phrases.txt \
        --json out/phrases.json \
        --min-count 20 --min-pmi 2.0
"""

import argparse
import json
import math
import re
import sqlite3
import sys
from collections import defaultdict
from pathlib import Path

STOPWORDS = {
    "a", "an", "the", "of", "to", "in", "on", "at", "by", "for", "with",
    "and", "or", "but", "not", "is", "are", "was", "were", "be", "been",
    "have", "has", "had", "do", "does", "did", "will", "would", "can",
    "could", "should", "may", "might", "must", "i", "you", "he", "she",
    "it", "we", "they", "my", "your", "his", "her", "its", "our", "their",
}
WORD_RE = re.compile(r"^[a-z']+$")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("--counts", required=True, help="counts.sqlite from build_ngrams.py")
    p.add_argument("--output", required=True, help="phrase<TAB>score output")
    p.add_argument("--json", help="Optional full-stats JSON output")
    p.add_argument("--min-count", type=int, default=20, help="Min count floor")
    p.add_argument("--min-pmi", type=float, default=2.0, help="Min PMI")
    p.add_argument("--max-len", type=int, default=4, help="Max phrase length")
    args = p.parse_args()

    conn = sqlite3.connect(f"file:{args.counts}?mode=ro", uri=True)

    # Unigram counts for P(w)
    uni_total = 0
    uni_counts = {}
    for w, c in conn.execute("SELECT w, count FROM unigrams"):
        uni_counts[w] = c
        uni_total += c

    # Collect candidate n-grams (2..max-len) above count floor directly via SQL.
    phrases = []  # (phrase, count, words)
    for k, table in ((2, "bigrams"), (3, "trigrams"), (4, "fourgrams")):
        if k > args.max_len:
            continue
        for ctx, w, c in conn.execute(
                f"SELECT ctx, w, SUM(count) FROM {table} GROUP BY ctx, w "
                f"HAVING SUM(count) >= {args.min_count}"):
            phrase = ctx + " " + w if ctx else w
            words = phrase.split()
            if len(words) < 2 or len(words) > args.max_len:
                continue
            if not all(WORD_RE.match(t) for t in words):
                continue
            # Drop repeated-word artifacts (laughter/lyrics: "na na na na",
            # "ha ha ha ha") — high PMI but not useful phrases.
            if len(set(words)) < len(words):
                continue
            phrases.append((phrase, c, words))

    if not phrases:
        print("No candidate phrases above count floor", file=sys.stderr)
        return

    # Unigram probabilities
    uni_p = {w: c / uni_total for w, c in uni_counts.items()}

    # Score each phrase
    scored = []  # (pmi, tscore, count, phrase)
    for phrase, count, words in phrases:
        # P(seq)
        p_seq = count / uni_total
        # product of unigram probs (with floor)
        prod_p = 1.0
        for w in words:
            prod_p *= uni_p.get(w, 1e-9)
        if prod_p <= 0:
            continue
        pmi = math.log(p_seq / prod_p)
        if pmi < args.min_pmi:
            continue
        tscore = (p_seq - prod_p) / (math.sqrt(p_seq) + 1e-12)
        scored.append((pmi, tscore, count, phrase))

    # Sort by PMI desc
    scored.sort(key=lambda x: (-x[0], -x[2]))

    # Dedupe: drop phrases that are substrings of a longer kept phrase.
    # Group kept phrases by token length so a candidate only checks kept
    # phrases of length >= its own (bounded work, avoids O(n^2) over all kept).
    # Dedupe: drop phrases that are substrings of a longer kept phrase.
    # Sort by length descending so longer phrases are kept first. A phrase is
    # dropped iff its token sequence appears as a contiguous window inside an
    # already-kept phrase. Index every contiguous window of each kept phrase
    # in a set keyed by window length, so the check is O(1) per candidate.
    scored.sort(key=lambda x: (-len(x[3].split()), -x[0], -x[2]))
    kept = []
    kept_windows: dict[int, set[tuple]] = {}  # window_len -> set of token tuples

    max_len = args.max_len
    for pmi, tscore, count, phrase in scored:
        words = phrase.split()
        tw = tuple(words)
        # Drop if this exact token sequence is already a window of a kept phrase
        if tw in kept_windows.get(len(tw), ()):
            continue
        kept.append((pmi, tscore, count, phrase))
        # Index every contiguous window of this kept phrase
        for L in range(2, len(tw) + 1):
            bucket = kept_windows.setdefault(L, set())
            for i in range(len(tw) - L + 1):
                bucket.add(tw[i:i + L])

    # Strip leading/trailing stopwords from kept phrases
    final = []
    for pmi, tscore, count, phrase in kept:
        words = phrase.split()
        while words and words[0] in STOPWORDS:
            words.pop(0)
        while words and words[-1] in STOPWORDS:
            words.pop()
        if len(words) >= 2:
            final.append((pmi, tscore, count, " ".join(words)))

    # Write output: phrase<TAB>score (score = normalized t-score like freq list)
    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    max_t = max((t for _, t, _, _ in final), default=1.0)
    with out.open("w") as f:
        for pmi, tscore, count, phrase in final:
            # score in [1, 1e6] like en_freq.txt
            score = max(1, round(tscore / max_t * 1_000_000))
            f.write(f"{phrase}\t{score}\n")

    print(f"candidates={len(phrases)} scored={len(scored)} kept={len(kept)} "
          f"final={len(final)}", file=sys.stderr)
    print(f"Wrote {len(final)} phrases -> {out}", file=sys.stderr)

    if args.json:
        with open(args.json, "w") as f:
            json.dump([{"phrase": ph, "pmi": pmi, "tscore": ts, "count": c}
                       for pmi, ts, c, ph in final], f, indent=1)


if __name__ == "__main__":
    main()