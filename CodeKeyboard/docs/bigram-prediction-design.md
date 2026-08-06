# Bigram Prediction Design

## Why We Need This

The current suggestion engine ranks candidates by prefix match against a frequency-weighted trie
(`en.trie`, 60k words). It answers: "given the letters typed so far, what words match?"

It does not answer: "given what the user just wrote, what word comes next?"

The result is that after committing "I want", typing nothing produces no suggestions. Typing "t"
produces "the", "to", "that" — ranked purely by how common those words are in English, with no
knowledge that "I want to" is far more likely than "I want the". This is the gap between a
spell-checker and a predictive keyboard.

The goal is T9-style prediction: after each committed word, the suggestion bar shows the top
next-word candidates based on what word came before. Typing narrows the candidates further,
but context should bias the ranking from the start.

---

## Background Research

### T9

The original T9 patent (US5818437, Tegic Communications, 1999) is expired. The core idea was
frequency-ranked prefix completion from a numeric keypad, plus a learned user dictionary. The
"predictive" feel came from bigram reranking: after committing a word, the next suggestions
were ranked by P(next | previous) not P(next). The math is simple; the feeling is magic.

### N-gram Language Models — Jurafsky & Martin Ch. 3

The canonical reference. An n-gram model estimates the probability of a word given the N-1
preceding words:

    P(wn | w1...wn-1) ≈ P(wn | wn-N+1...wn-1)

A bigram model uses one preceding word. A trigram model uses two. The counts come from a
large text corpus. Storage grows exponentially with n, which is the practical constraint.

Reference: https://web.stanford.edu/~jurafsky/slp3/ (Chapter 3, free draft)

### Infini-gram (Liu et al., 2024)

Instead of pre-computing n-gram count tables (which explode in size as n grows), infini-gram
stores the raw corpus in a suffix array and computes counts at inference time. This allows
any-length context at O(log n) query cost. For a 5 trillion token corpus this is the only
feasible approach. For a personal on-device corpus (a user's typing history, maybe 10MB over
years), a suffix array is completely feasible and would give true unlimited-context prediction
from the user's own data. This is a future direction, not an immediate implementation target.

Reference: Liu et al. (2024) "Infini-gram: Scaling Unbounded n-gram Language Models to a
Trillion Tokens" — https://arxiv.org/abs/2401.17377

### librime

librime (https://github.com/rime/librime) is the open source Chinese IME engine. It is not a
general prediction library — its core is a Pinyin/shape-code to Chinese character transliteration
pipeline. However two components are architecturally relevant:

1. **`grammar.h`** — a clean abstract interface `Query(context, word) → log-probability` that
   is the plug point for a bigram/trigram language model. The engine calls this during beam
   search. librime ships no implementation — the model is a plugin.

2. **`poet.cc`** — beam search (width 7) over a word graph. When a Grammar plugin is loaded,
   it keeps the top K partial hypotheses and scores each extension with
   `word_weight + Grammar::Query(prev_words, next_word)`. This is the right algorithm for
   sentence-level prediction.

3. **`algo/dynamics.h`** — frequency decay math for user history. A spaced-repetition formula
   that prevents old frequency counts from dominating recent preferences. Worth porting.

The key finding: librime confirms that the right architecture is a `Grammar` interface
(bigram scoring) plugged into beam search, with a user-history layer on top using decay
scoring. librime does not do free next-word prediction after a word commit — that is a gap
we fill ourselves.

### AnySoftKeyboard vs HeliBoard

Both open source Android keyboards implement bigram prediction, but at very different levels:

**AnySoftKeyboard** (https://github.com/AnySoftKeyboard/AnySoftKeyboard):
- `NextWordDictionary.java`: a flat `HashMap<prevWord, List<follower>>` keyed on a single
  prior word. Starts empty on fresh install. Learns purely from user typing.
- After space: `getNextSuggestions(previousWord)` returns stored followers sorted by
  observation count.
- No pre-trained model, no native engine, no NLP. Simple, works for any language.

**HeliBoard** (https://github.com/Helium314/HeliBoard):
- `NgramContext.java`: stores the last N committed words, passed to a native C++ binary
  dictionary engine via JNI.
- After space: `getNextWordSuggestions(ngramContext)` with empty composing buffer — the
  native engine returns candidates scored by bigram log-probability from a pre-trained
  binary `.dict` file.
- Bigram context also influences autocorrection decisions.
- Pre-trained dictionaries published at: https://codeberg.org/Helium314/aosp-dictionaries
  — 200+ languages, `.dict` format (AOSP binary), includes English with bigram section.
  Built with: https://github.com/remi0s/aosp-dictionary-tools

HeliBoard's dictionaries are open, free, and include English with bigram data. We can use
them as a reference and potentially parse the English one as a seed.

---

## Our Architecture

### Why not HeliBoard's approach directly

HeliBoard's pre-trained bigrams cover standard English well. They do not cover:
- Banglish (Bengali written in Latin script: "ami tomake bhalobashi")
- Hinglish (Hindi in Latin script: "kya haal hai")
- Mixed-language sentences ("ami really tired aaj")

No pre-trained English corpus covers these. A user-learned model picks them up naturally
from typing. This is the fundamental reason to build on the ASK pattern, not HeliBoard's.

### Seed corpus: Norvig count_2w.txt

Source: https://norvig.com/ngrams/count_2w.txt (MIT licensed)
Format: `word1 word2\tcount` — 250k most frequent English bigrams with counts.
Size: 5.6MB raw, ~1MB after filtering to top 30k pairs.

We take the top 30k by count. This covers all common English transitions ("of the",
"in the", "I want", "do you", "going to") and gives day-1 prediction quality before
the user has typed anything.

Script: `scripts/extract_bigrams.py` — reads count_2w.txt, emits top N as compact JSON.
Asset: `android/app/src/main/assets/bigrams.json` — bundled in APK (~1MB budget).

### Data model

```kotlin
// Seed: loaded from bigrams.json at startup (static, read-only)
// Key: previous word (lowercase). Value: list of (next word, score) sorted by score desc.
Map<String, List<Pair<String, Float>>>

// User layer: learned from typing, persisted to files/user_bigrams.bin
// Same structure, grows over time, scores decay via dynamics formula
Map<String, MutableList<Pair<String, Float>>>
```

### Scoring

For a candidate word W given previous word P:

    score(W) = α * seed_bigram(P, W) + β * user_bigram(P, W) + γ * unigram(W)

Where α, β, γ are weights (start: 0.3, 0.5, 0.2 — tunable via metrics).
Unigram(W) is the existing trie frequency score.

When the composing buffer is empty (after space), surface the top 5 candidates by score.
When the user starts typing, intersect bigram candidates with trie prefix matches and
re-rank by combined score.

### Learning

On every word commit (space or suggestion tap):
1. Record the transition `(previousWord → committedWord)` in the user bigram map.
2. Apply decay to all scores for `previousWord` using the dynamics formula from librime.
3. Persist asynchronously (same pattern as UserTrie — background executor).

Decay prevents a word the user typed once 6 months ago from dominating forever. Recent
transitions score higher.

### Mixed language support

The model works for any language because keys and values are plain strings. If the user
types "ami tomake" repeatedly, the bigram `("ami", "tomake")` accumulates in the user
layer. If they type "I really" the seed layer covers it. Mixed sentences like
"ami really tired" learn `("ami", "really")` and `("really", "tired")` — the latter
already covered by the seed.

Banglish, Hinglish, code-switching — all handled automatically. No language detection
needed. The user layer becomes a personal mixed-language model over time.

### Extended context: path to sentence-level prediction

Current: bigram — P(next | 1 previous word)
Near future: trigram — P(next | 2 previous words), just extend the context key to
  `"${word_n-2} ${word_n-1}"` and keep the same map structure.

Long term: suffix array over user typing history. Store every sentence the user has typed.
At query time, find the longest matching suffix in the corpus and return what followed it.
This is the infini-gram approach applied to personal data. A user's full typing history
over a year might be 5-10MB — trivially small for a suffix array on a modern phone.
This gives true unlimited-context prediction without any ML model.

---

## Implementation Plan

1. `scripts/extract_bigrams.py` — download count_2w.txt, emit top 30k as JSON
2. `android/app/src/main/assets/bigrams.json` — bundled seed
3. `BigramModel.kt` — loads seed + manages user layer + scoring + persistence
4. `CodeKeyboardIME.kt` — after space/commit, call `BigramModel.nextWords(prevWord, 5)`
   and surface in suggestion bar
5. Metrics: track bigram hit rate (how often user picks a bigram suggestion vs types manually)

---

## Key Links

- Jurafsky & Martin SLP3 Ch. 3: https://web.stanford.edu/~jurafsky/slp3/
- Norvig bigrams: https://norvig.com/ngrams/count_2w.txt
- Infini-gram paper: https://arxiv.org/abs/2401.17377
- librime: https://github.com/rime/librime
- HeliBoard: https://github.com/Helium314/HeliBoard
- HeliBoard dictionaries: https://codeberg.org/Helium314/aosp-dictionaries
- AnySoftKeyboard: https://github.com/AnySoftKeyboard/AnySoftKeyboard
- AOSP dictionary tools: https://github.com/remi0s/aosp-dictionary-tools
