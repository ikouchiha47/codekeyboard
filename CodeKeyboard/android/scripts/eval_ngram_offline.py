#!/usr/bin/env python3
"""Offline next-word eval: trigram.json + bigrams.json against the two
committed eval fixtures. Avoids re-pasting inline Python for every
parameter sweep. Mirrors Ngram.kt's actual cascade logic (ADR-008 task L —
weighted blend by support, not "first non-empty tier wins") so this stays
a faithful simulator, not a stale approximation of what's shipped.

Usage:
    .venv/bin/python eval_ngram_offline.py --trigrams /tmp/trigrams_v4_support.json
"""
import argparse
import json
from pathlib import Path

REPO = Path(__file__).parent.parent


def load_cases(path):
    cases = []
    for line in open(path):
        if line.startswith("#") or not line.strip():
            continue
        parts = line.rstrip("\n").split("\t")
        if parts[0].endswith(" "):
            cases.append((parts[0], parts[1].split("|")))
    return cases


def split_context(sentence):
    words = sentence.rstrip().split(" ") if sentence.endswith(" ") else sentence.rstrip().split(" ")[:-1]
    return [w.lower().strip(".,!?\";") for w in words if w]


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--trigrams", required=True)
    p.add_argument("--bigrams", default=str(REPO / "app/src/main/assets/bigrams.json"))
    p.add_argument("--bigrams-support", default=str(REPO / "app/src/main/assets/bigrams_support.json"))
    p.add_argument("--trigram-only", action="store_true",
                    help="Disable the bigram tier entirely — isolates trigram's real "
                         "performance, unconfounded by blending or fallback.")
    args = p.parse_args()

    trigram_seed = json.load(open(args.trigrams))  # {"w1 w2": {"followers": [...], "support": N}}
    bigram_seed = json.load(open(args.bigrams))     # {"w1": [[word, score], ...]}  (unchanged shape)
    bigram_support = json.load(open(args.bigrams_support)) if Path(args.bigrams_support).exists() else {}

    def bigram_candidate(ctx):
        if len(ctx) < 1:
            return None
        followers = bigram_seed.get(ctx[-1])
        if not followers:
            return None
        results = [w for w, s in sorted(followers, key=lambda p: -p[1])]
        support = bigram_support.get(ctx[-1], 0)
        return results, support

    def trigram_candidate(ctx):
        if len(ctx) < 2:
            return None
        entry = trigram_seed.get(f"{ctx[-2]} {ctx[-1]}")
        if not entry:
            return None
        results = [w for w, s in sorted(entry["followers"], key=lambda p: -p[1])]
        return results, entry["support"]

    def ngram_next(ctx, n=5):
        # Mirrors Ngram.nextWords: gather every tier with enough context and
        # a non-empty result, blend weighted by support (not first-wins).
        tiers = (trigram_candidate,) if args.trigram_only else (trigram_candidate, bigram_candidate)
        candidates = [c for c in (fn(ctx) for fn in tiers) if c is not None]
        if not candidates:
            return []
        if len(candidates) == 1:
            return candidates[0][0][:n]

        total_support = max(sum(support for _, support in candidates), 1)
        weighted = {}
        for results, support in candidates:
            weight = support / total_support
            for idx, word in enumerate(results):
                weighted[word] = weighted.get(word, 0.0) + weight * (len(results) - idx)
        ranked = sorted(weighted.items(), key=lambda p: -p[1])
        return [w for w, _ in ranked[:n]]

    for fname, label in [
        (REPO / "app/src/test/resources/autocomplete_eval_cases_generated.tsv", "generated"),
        (REPO / "app/src/test/resources/autocomplete_eval_cases.tsv", "curated"),
    ]:
        cases = load_cases(fname)
        passed = sum(1 for s, exp in cases if any(e in ngram_next(split_context(s)) for e in exp))
        coverage = sum(1 for s, _ in cases if len(split_context(s)) >= 2)
        print(f"{label} next-word: {passed}/{len(cases)} = {100*passed/len(cases):.1f}%"
              f"  (coverage: {coverage}/{len(cases)} cases had >=2 words of context)")


if __name__ == "__main__":
    main()
