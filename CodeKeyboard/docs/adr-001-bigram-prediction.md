# ADR-001: Bigram Next-Word Prediction Architecture

**Status:** Implemented (partial — decay pending)
**Date:** 2026-08-07

---

## Context

The suggestion engine prior to this ADR ranked candidates purely by prefix match against a
frequency-weighted trie (`en.trie`, 60k words). It had no concept of what the user had just
written. After committing "I want", typing nothing produced no suggestions. Typing "t" produced
"the", "to", "that" ranked by global English frequency — with no knowledge that "I want to" is
far more probable than "I want the".

The goal was T9-style prediction: after each committed word, surface next-word candidates based
on context, and bias prefix-match ranking toward contextually likely words.

---

## Decision

Implement a two-layer bigram model:

1. **Seed layer** — a static corpus-derived bigram table loaded at startup
2. **User-learned layer** — a runtime-accumulated transition map, personal to the user

Surface next-word candidates after every space or suggestion tap. When the user starts typing,
promote bigram-matching candidates to the top of prefix results.

---

## Alternatives Considered

### A: HeliBoard's approach (pre-trained binary .dict + native JNI engine)
- Works on day 1 with high quality for standard English
- AOSP `.dict` format requires JNI to read — non-trivial to parse in Kotlin
- Pre-trained models exist for 200+ languages at codeberg.org/Helium314/aosp-dictionaries
- Does not cover Banglish, Hinglish, or mixed-language patterns
- **Rejected for now** — too much infrastructure for v1. Planned for v2.

### B: Pure user-learned (ASK's approach, no seed)
- Zero cold-start quality — day 1 has no suggestions
- Works for any language automatically
- **Rejected alone** — cold start is too poor. Used as the user layer on top of a seed.

### C: Chosen — Norvig seed + user-learned layer
- Seed from `norvig.com/ngrams/count_2w.txt` (MIT licensed, Google Web Trillion Word Corpus)
- Top 100k bigrams by frequency, filtered to lowercase alphabetic pairs
- 618KB bundled as `assets/bigrams.json`
- User layer starts empty, learns from every word commit
- Combined scoring: `0.4 * seed + 0.6 * user` — user layer wins over time

---

## Implementation

### Data model

**Seed** (`bigrams.json`):
```json
{ "want": [["to", 0.98], ["it", 0.85], ["a", 0.83], ...], ... }
```
Score is log-normalized within each predecessor bucket. Read-only at runtime.

**User layer** (`files/user_bigrams.json`):
```json
{ "ami": [["tomake", 4], ["khub", 2], ...], ... }
```
Raw count per transition. Persisted asynchronously on every commit. Max 20 followers per
predecessor word to bound memory and file size.

### Scoring

```
score(W | context=P) = 0.4 * seed(P, W) + 0.6 * (user_count(P,W) / max_user_count(P))
```

Bigram candidates are ranked by this score. Candidates not in the bigram map fall back to
trie frequency ranking.

### Strategy interface

Context (previous committed word) is passed as a parameter to `suggest()`:

```kotlin
interface SuggestionStrategy {
    fun suggest(prefix: String, k: Int, context: String = ""): List<String>
}
```

This mirrors librime's `Grammar` interface (`grammar.h`):
```cpp
virtual double Query(const string& context, const string& word, bool is_rear) = 0;
```

The caller owns the context. The strategy uses it or ignores it. No shared mutable state,
no typecasting at call sites.

### `BigramAwareSuggestionStrategy`

Wraps `MergedSuggestionStrategy` (existing trie layer):

```kotlin
class BigramAwareSuggestionStrategy(
    private val base: SuggestionStrategy,
    private val bigram: BigramModel,
) : SuggestionStrategy {
    override fun suggest(prefix: String, k: Int, context: String): List<String> {
        val baseResults = base.suggest(prefix, k + 5)
        if (context.isEmpty()) return baseResults.take(k)
        val bigramMatches = bigram.nextWords(context, prefix = prefix, n = k)
        return (bigramMatches + baseResults.filter { it !in bigramMatches }).take(k)
    }
}
```

### After-space flow

```
space pressed
  → flushComposing(word)         // commits word, records prevCommittedWord
  → recordTransition(prev, word) // updates user layer
  → bigramModel.nextWords(word)  // top 5 next-word candidates
  → suggestionBar.update("", candidates)  // shown before user types anything
```

### While typing flow

```
user types "h" after "want"
  → suggest("h", 5, context="want")
  → BigramAwareSuggestionStrategy:
      base gives ["he", "his", "her", "have", "here", ...]
      bigram gives ["have"] (top follower of "want" starting with "h")
      promoted result: ["have", "he", "his", "her", "here"]
```

### Mixed language

Works automatically. Keys and values in both layers are plain strings. Banglish transitions
like `("ami", "tomake")` accumulate in the user layer from normal typing. No language
detection required.

---

## What Is NOT Implemented Yet

### Frequency decay

The user layer currently accumulates counts indefinitely. A word typed 50 times six months
ago scores the same as one typed 5 times last week.

The correct fix is temporal decay from librime's `algo/dynamics.h`:

```
score = log(formula_p(commits, usages, current_tick, dee))
```

Where `dee` decays over time and `current_tick` is a global commit counter. This biases
scores toward recent usage without discarding long-term history entirely.

**Planned:** Add a `tick` counter to `BigramModel`. On each `recordTransition`, update
`dee` for existing entries using the decay formula. Store `(count, tick, dee)` per entry
instead of just `count`.

### Trigram / extended context

Currently: `P(next | 1 previous word)`.
Next step: `P(next | 2 previous words)` — extend context key to `"$w_prev2 $w_prev1"`,
same map structure.

Long term: suffix array over the user's full typing history — infini-gram on personal data
(Liu et al., 2024). Gives unlimited-length context at O(log n) query cost. A user's full
typing history over a year is ~5-10MB — trivially small for a suffix array on a phone.

### HeliBoard dict integration

Pre-trained binary `.dict` files (AOSP format) at codeberg.org/Helium314/aosp-dictionaries
cover 200+ languages including Bengali, Hindi, Hinglish. These would replace the Norvig
seed for English and add multilingual support. Requires parsing the AOSP binary format
(JNI or a build-time conversion script using `dicttool_aosp.jar`).

---

## Consequences

**Good:**
- Next-word suggestions appear immediately after space on day 1 (seed coverage)
- Personal patterns (Banglish, Hinglish, mixed) accumulate automatically
- Clean interface — context flows through `suggest()`, no shared state
- Scoring weights tunable via metrics (keyboard.word.keystrokes histogram)

**Bad:**
- Norvig corpus is Google Books (formal text) — suggestions skew literary rather than
  conversational ("great" → "deals, prices, for" rather than "job, work, stuff")
- No decay — long-term usage patterns never fade
- Cold start for non-English is still zero (user layer only)

---

## References

- Jurafsky & Martin SLP3 Ch. 3: https://web.stanford.edu/~jurafsky/slp3/
- Norvig bigrams: https://norvig.com/ngrams/count_2w.txt
- Infini-gram: https://arxiv.org/abs/2401.17377
- librime grammar.h: https://github.com/rime/librime
- HeliBoard NgramContext: https://github.com/Helium314/HeliBoard
- HeliBoard dictionaries: https://codeberg.org/Helium314/aosp-dictionaries
- AnySoftKeyboard NextWordDictionary: https://github.com/AnySoftKeyboard/AnySoftKeyboard
