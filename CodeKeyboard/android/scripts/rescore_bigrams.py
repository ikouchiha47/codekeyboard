#!/usr/bin/env python3
"""
Rescores bigrams.json to prefer content-word continuations over function
words, without changing the underlying corpus (see the eval investigation
in ADR-007's checkpoint log: swapping to a different bigram corpus — even
the AOSP/HeliBoard dictionary — didn't help, because *any* frequency-ranked
bigram model correctly ranks function words first; "the debt is" really is
more common than "the debt consolidation". The fix is what we optimize for,
not which corpus we read).

For each prevWord -> [(nextWord, score), ...] entry, applies a multiplicative
penalty to any nextWord that spaCy's static vocabulary marks as a stopword
(nlp.vocab[word].is_stop — a context-free per-word lookup, not the sentence
parser; these are bare candidate words with no surrounding sentence, so the
contextual POS tagger isn't applicable here, only the static stop-word flag
is).

Usage:
    .venv/bin/python rescore_bigrams.py
    (reads  ../app/src/main/assets/bigrams.json,
     writes ../app/src/main/assets/bigrams.json in place —
     back up first, e.g. corpus_raw/bigrams_seed_backup_pre_rescore.json)
"""

import json
import sys
from pathlib import Path

import spacy

BIGRAMS_FILE = Path(__file__).parent.parent / "app/src/main/assets/bigrams.json"

# Multiplies the score of any candidate next-word that is a function word
# (spaCy static is_stop lookup). Doesn't remove them — "is"/"a"/"the" are
# still valid, sometimes-correct suggestions — just deprioritizes them
# below a content-word candidate of otherwise-similar score.
STOPWORD_PENALTY = 0.5


def get_nlp():
    return spacy.load("en_core_web_sm", disable=["tagger", "parser", "ner", "lemmatizer"])


def rescore(seed: dict, nlp) -> dict:
    is_stop_cache: dict[str, bool] = {}

    def is_stop(word: str) -> bool:
        cached = is_stop_cache.get(word)
        if cached is None:
            cached = nlp.vocab[word].is_stop
            is_stop_cache[word] = cached
        return cached

    rescored = {}
    for prev, followers in seed.items():
        adjusted = [
            (word, score * STOPWORD_PENALTY if is_stop(word) else score)
            for word, score in followers
        ]
        adjusted.sort(key=lambda pair: -pair[1])
        rescored[prev] = adjusted
    return rescored


def main():
    nlp = get_nlp()
    seed = json.loads(BIGRAMS_FILE.read_text())

    rescored = rescore(seed, nlp)

    # Sanity check on a few known cases before writing.
    for word in ("debt", "once", "mind", "sea"):
        if word in rescored:
            print(f"  {word} -> {rescored[word][:5]}", file=sys.stderr)

    BIGRAMS_FILE.write_text(json.dumps(rescored, separators=(",", ":")))
    print(f"Rescored {len(rescored)} entries, wrote {BIGRAMS_FILE}", file=sys.stderr)


if __name__ == "__main__":
    main()
