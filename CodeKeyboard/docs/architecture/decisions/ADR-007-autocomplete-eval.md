# ADR-007: Autocomplete Eval Harness

## Context

The suggestion pipeline (`Trie` + `UserTrie` + `BigramModel`, combined via
`MergedSuggestionStrategy` / `BigramAwareSuggestionStrategy`) has grown
incrementally with no way to measure whether a change to it actually helps.
Before tuning weights, thresholds, or ranking logic, we need a repeatable
way to see how good current suggestions are, and where they fall down.

## Goals

- A wide, varied set of test cases with clear pass/fail expectations
- Exercise the *real* production dictionaries (`en.trie`, `bigrams.json`)
  and the *real* production strategy pipeline — not a hand-rolled stand-in
- Run entirely as a fast JVM unit test (no emulator, no Robolectric)
- Produce a report broken down by category, not just a single pass/fail
- A checkpoint/tune/re-run loop: run → find a saturation point (a category
  or pattern that consistently fails) → tune → re-run → checkpoint the new
  baseline → repeat. Aim: 90% overall, understanding some percentage
  (contractions, rare/jargon words, deep multi-word context) may never be
  fully coverable without a materially different architecture.

## What "autocomplete" means here — two distinct query shapes

Early drafts of the fixture mostly tested typing partial words
("please let me kno" → "know"). That undersells what the trie already does
for free: prefix search on 3+ typed characters narrows the candidate set to
a handful of words regardless of context, so those cases pass almost by
construction and don't tell us anything about the *bigram* model.

The case that actually matters is the trailing-space one: the full previous
word has been committed, nothing of the next word has been typed yet, and
we ask the model to predict purely from context. That is where
`BigramModel.nextWords` is the only thing doing any work, and it's the
highest-value moment for a keyboard user (a full suggestion strip with
zero keystrokes spent).

## Architecture

```
Case (sentence, expected[], category)
    │  loaded from a plain TSV fixture — no Kotlin edits needed to add cases
    ▼
splitSentence(sentence) → (context, prefix)
    │  same split CodeKeyboardIME performs at suggestion time:
    │  context = last *completed* word, prefix = partial word in progress
    ▼
BigramAwareSuggestionStrategy(MergedSuggestionStrategy(userTrie, trie), bigramModel)
    │  the exact production pipeline, wired with:
    │    - Trie.load(File)        — real en.trie, read off disk
    │    - BigramModel(File, File) — real bigrams.json seed, no user data
    │    - UserTrie()             — empty, simulating a fresh install
    ▼
Result (passed if ANY of Case.expected appears in top-K suggestions)
    ▼
report grouped by category + printed list of failures
    ▼
single assertion: overall pass rate >= checkpointed BASELINE_PASS_RATE
```

### Why File-based loaders instead of Robolectric

`Trie.load()` and `BigramModel` originally required an Android `Context`
(for `context.assets` / `context.filesDir`). Rather than pull in Robolectric
to fake a `Context`, both gained small File/InputStream-based overloads:

- `Trie.load(file: File)` — delegates to the existing `fromBytes(bytes)`
- `BigramModel(seedJsonFile: File, userFile: File)` — new secondary
  constructor; the primary `BigramModel(context: Context)` now just supplies
  a `() -> String` seed reader and a `File` instead of holding `context`
  directly, so both code paths share every line of parsing/scoring logic

This keeps the eval test pure-JVM and fast (no Robolectric startup cost),
consistent with how `KeyboardState.kt` was deliberately kept Android-free.

One real gotcha hit along the way: Android's unit-test stub jar makes
`org.json.*` and `android.util.Log.*` throw `RuntimeException("not mocked")`
by default. `returnDefaultValues=true` would silence that but leaves
`JSONObject` non-functional (every method returns null/0), which would
make the seed bigrams silently load as empty — invisible corruption of the
exact thing being measured. Fixed instead by adding a real
`org.json:json` **test** dependency, which Gradle resolves ahead of the
platform stub on the unit-test runtime classpath, so `BigramModel`'s real
JSON parsing runs unmodified.

## Generated corpus (real-text, unbiased ground truth)

The curated fixture has a bias problem: I pick both the sentence and what
counts as a correct answer. `scripts/gen_autocomplete_corpus.py` addresses
this by sampling real sentences from a genre-diverse, public-domain /
permissively-licensed corpus and using **the actual next word from the
source text** as ground truth — no hand-labeling, no alternatives list,
just "what did the book/article/README actually say next."

Sources (fetched to `scripts/corpus_raw/`, gitignored — re-fetchable, only
the derived TSV is committed):

| Genre | Source | License |
|---|---|---|
| `fiction` | Pride and Prejudice, Alice in Wonderland, Frankenstein | Public domain (Project Gutenberg) |
| `nonfiction-classic` | Wealth of Nations, Meditations | Public domain (Project Gutenberg) — older register than modern text, a known skew |
| `tech` | READMEs: vscode, react, kubernetes | MIT/Apache — only short derived fragments stored, not the READMEs themselves |
| `news` | Wikinews article extracts | CC BY 2.5 |
| `casual-chat` | NPS Chat Corpus — real anonymized chatroom posts | Distributed with NLTK, research-permissive |
| `movie-dialogue` | Cornell Movie-Dialogs Corpus — real movie script lines | Research-permissive (cs.cornell.edu) |

The first four sources are all formal/literary register — narrative prose,
an 18th-century economics treatise, news articles, OSS documentation — and
don't represent how people actually type on a phone keyboard: short,
casual, contractions, fragments, second person. `casual-chat` and
`movie-dialogue` were added specifically to cover that gap with real
conversational text rather than more of my own hand-written examples
(which would just reintroduce the same author-bias problem this generated
fixture exists to avoid). Sample cases from those two:

```
: Are     -> you             [casual-chat]
brb ..    -> need            [casual-chat]
i use to li -> live          [casual-chat]  (prefix)
bc you    -> want            [casual-chat]
My dear sir, I would gladly  -> change      [movie-dialogue]
I don't want to lie down.    -> i've        [movie-dialogue]
So, I gu  -> guess           [movie-dialogue]  (prefix)
```

For each sampled sentence, a random word boundary is chosen: either cut
exactly at the boundary (a "next-word" case, nothing typed of the next
word — tests the bigram model in isolation) or partway into the next word
(a "prefix" case, 1 to len-1 characters typed). 40 cases per genre, 240
total, seeded RNG for reproducibility.

This fixture is **report-only** — no baseline assertion — because a single
real-text ground truth makes legitimate synonyms count as failures (model
suggests "cheerful," the source said "happy"). The value is the category
breakdown and failure patterns at much larger N than hand-curation could
practically reach, not a pass/fail gate.

### Checkpoint #1 (generated corpus, 4 formal/literary genres, 160 cases)

| Category | Result |
|---|---|
| `generated-fiction` | 22/40 (55.0%) |
| `generated-nonfiction-classic` | 18/40 (45.0%) |
| `generated-tech` | 23/40 (57.5%) |
| `generated-news` | 19/40 (47.5%) |

**Overall: 82/160 (51.2%)**

This confirms the curated-fixture finding at scale, with the same
fingerprint: a cluster of failures all return the identical top-5
(`[same, first, following, most, best]` or `[the, be, a, get, make]`)
regardless of the actual sentence, because they all collapse to a handful
of extremely common single-word contexts ("the", "to", "a", ...). E.g.
"Kubernetes is hosted by the ", "Should the custom of weighing gold... the ",
and "Extensions that... have the " all hit the exact same 5 suggestions —
same 1-word-context ceiling identified in the curated run, now visible
across genres and at 3x the sample size.

### Checkpoint #1b — added casual-chat + movie-dialogue (240 cases total)

All four original sources are formal/literary register, which doesn't
represent actual phone-keyboard typing. Added two real conversational
sources (NPS Chat Corpus, Cornell Movie-Dialogs) to check whether register
mismatch was masking or explaining the failure rate.

| Category | Result |
|---|---|
| `generated-fiction` | 22/40 (55.0%) |
| `generated-nonfiction-classic` | 20/40 (50.0%) |
| `generated-tech` | 19/40 (47.5%) |
| `generated-news` | 19/40 (47.5%) |
| `generated-casual-chat` | 19/40 (47.5%) |
| `generated-movie-dialogue` | 22/40 (55.0%) |

**Overall: 121/240 (50.4%)**

**Finding: register doesn't matter.** All six genres land within a tight
47.5-55% band — casual chat and movie dialogue score essentially the same
as 18th-century economics prose. This rules out "the model just doesn't
understand casual English" as an explanation. The failure mode is the same
one word-for-word regardless of genre: whenever the required signal is
more than one word of context, the model falls back to the same handful of
globally-frequent words. Register was a reasonable hypothesis to check but
isn't the actual bottleneck — the 1-word bigram context window is.

## Files

| File | Status | Role |
|---|---|---|
| `app/src/main/java/com/codekeyboard/Trie.kt` | Modify | add `load(File)` overload |
| `app/src/main/java/com/codekeyboard/BigramModel.kt` | Modify | extract Context-independent core; add `(File, File)` constructor |
| `app/build.gradle` | Modify | add `testImplementation("org.json:json:...")` |
| `app/src/test/resources/autocomplete_eval_cases.tsv` | New | curated fixture — sentence, expected word(s), category |
| `scripts/gen_autocomplete_corpus.py` | New | fetches genre-diverse public-domain/permissive text (6 genres incl. real chat/movie dialogue), samples real sentences, generates the second fixture |
| `app/src/test/resources/autocomplete_eval_cases_generated.tsv` | New | generated fixture — real text, single ground-truth word, regenerate via the script above |
| `app/src/test/java/com/codekeyboard/AutocompleteEvalTest.kt` | New | loads both fixtures, runs the real pipeline, reports; asserts baseline on curated only |
| `.gitignore` | Modify | exclude `android/scripts/corpus_raw/` (re-fetchable raw source text) |
| `docs/architecture/decisions/ADR-007-autocomplete-eval.md` | New | this document |

## Checkpoint log

### Checkpoint #1 — initial harness, no tuning

54 cases, `en.trie` + seed `bigrams.json`, empty `UserTrie`, top-5 window.

| Category | Result | What it tests |
|---|---|---|
| `next-word` | 20/20 (100%) | trailing space, 1-word context — bigram seed hit rate for common single-word transitions (`we ` → have/are/can/will, `let ` → me, `thank ` → you, ...) |
| `prefix` | 10/10 (100%) | partial word typed, no context needed — pure trie completion |
| `prefix+context` | 5/5 (100%) | partial word typed, context could help disambiguate |
| `next-word-phrase` | 8/19 (42.1%) | trailing space, but the *correct* answer needs 2-3 words of context, not just the last word |

**Overall: 43/54 (79.6%)**

**Finding:** the model is essentially perfect wherever a single previous
word is enough signal, and predictably weak wherever it isn't. Every
`next-word-phrase` failure has the same shape — context collapses to a
generic function word ("to", "the", "a", "me", "you") that has dozens of
common continuations, so the seed bigram table's top-5 is dominated by the
globally most frequent followers of that one word, not by what actually
follows the *phrase*:

```
"please let me "        -> expected "know",     got [to, a, and, that, in]
"I would like to "      -> expected "ask",       got [the, be, a, get, make]
"feel free to "         -> expected "reach",     got [the, be, a, get, make]
"can you please "       -> expected "let",       got [contact, note, click, read, use]
```

Notice `I would like to `, `I look forward to `, `just wanted to `,
`we look forward to `, and `please make sure to ` all produced the exact
same top-5 (`[the, be, a, get, make]`) — this is the single-word bigram
context saturating: every one of those sentences reduces to context="to",
and "to" always yields the same global answer no matter what came before
it. This is the concrete "saturation point" the eval loop is meant to
surface — not a bug, a structural ceiling of a 1-word Markov model.

**Next tuning candidates (not yet implemented):**
1. Extend `BigramModel` to a trigram (2-word context) fallback: try the
   full 2-word context first, fall back to the last word alone when the
   2-word context is unseen. Directly targets the failures above.
2. A small curated phrase table for extremely common multi-word idioms
   ("let me know", "as soon as possible", "feel free to", "looking forward
   to") — cheap, high-precision patch for the highest-frequency phrases
   without waiting on a full n-gram model change.
3. **`generated-tech` is corpus-starved (~75 candidate sentences from 3
   READMEs vs. hundreds-to-thousands for every other genre) — this is
   CodeKeyboard's actual target use case (a coding keyboard) and currently
   has the thinnest eval coverage of any genre.** Scraping more READMEs is
   a dead end (still just prose *about* code, not code-adjacent typing).
   The real fix is a purpose-built coding/technical text corpus — commit
   messages, docstrings, Stack Overflow-style Q&A text — fed into both the
   eval fixture and (eventually) `en.trie`/`bigrams.json` themselves, i.e.
   an actual "coding trie" the base dictionary could special-case for. Not
   started; noted here so it isn't lost.
4. Re-run the harness after each change and log a new checkpoint row here
   before moving the `BASELINE_PASS_RATE` constant in
   `AutocompleteEvalTest.kt` — the baseline should only ever move up, and
   only alongside an entry in this log explaining why.

### Checkpoint #2 — spaCy + trie-rank target filtering, bigram seed rescoring (2026-08-16)

Two independent changes, both driven by findings from checkpoint #1's fixture quality:

**1. Eval target selection rebuilt as a filter-strategy pipeline.** The generated
fixture's hardcoded `STOPWORDS` list let through ultra-common-but-not-technically-a-
stopword words (`know`, `get`, `go`, `well`, `said`, `make`, ...) as eval targets —
grammatically content words, but so predictable they told us nothing. Replaced with
a composable `TargetFilter` chain in `gen_autocomplete_corpus.py` (strategy pattern,
per user request): min-length, spaCy content-POS (NOUN/PROPN/VERB/ADJ, no ADV),
spaCy `is_stop`, and a new `TrieRankFilter` that rejects words ranked in the top ~200
most frequent words in `en.trie` itself — the production dictionary, not a generic
English frequency source. Also fixed a real bug: Cornell movie-dialogue turns weren't
being re-split on sentence boundaries, producing garbled multi-sentence contexts.
Considered (and rejected) using `wordfreq` as a second frequency source alongside
`en.trie` — redundant, since requiring the word exist in `en.trie` already resolves
the OOV case that a second source would otherwise be needed for.

This made the "honest" pass rate on the generated fixture drop from ~50% to ~26% —
expected and correct: the old number was inflated by trivial targets, not evidence of
better suggestions.

**2. Bigram seed rescoring** — see the new "Seed Rescoring: Absolute Discounting +
Unigram Backoff" section in `docs/adr-001-bigram-prediction.md` for the full
investigation (AOSP dictionary A/B test, naive per-word rebuild regression, and the
smoothing fix that improved both curated and generated next-word accuracy without
trading one off against the other). Summary of the by-type breakdown this surfaced —
a diagnostic split added permanently to `AutocompleteEvalTest`'s report, since "next
word" (context prediction) and "prefix" (trie completion) stress completely different
parts of the pipeline and a blended overall number hides which one is actually broken:

| | generated next-word | generated prefix | curated next-word | curated prefix |
|---|---|---|---|---|
| before rescoring | 1.0% | 53.0% | 71.8% | 100% |
| after discount+backoff+rescore | 2.1% | 53.7% | 74.4% | 100% |

**Finding:** prefix completion (trie-based) was never the bottleneck — it's been
~53-100% throughout. Next-word context prediction is the actual weak point, and even
after this fix it's still low on real, diverse text (2.1%). This is not a scoring bug
at this point — it's the structural ceiling of a 1-word-of-context (bigram/Markov-1)
model discussed in checkpoint #1's tuning candidate #1 (trigram/2-word context
extension). Confirmed via direct A/B: swapping the entire seed corpus (Norvig ->
AOSP/HeliBoard `en_US.combined`, real bigram data, 17x more word coverage) made
next-word accuracy *worse* (1.0%), not better — proving the bottleneck is context
length, not corpus choice or corpus quality.

**Curated baseline bumped:** `CURATED_BASELINE_PASS_RATE` in `AutocompleteEvalTest.kt`
left at 0.75 for now despite measuring 81.5% overall — the checkpoint discipline in
tuning candidate #4 above says move it deliberately with margin, not chase the exact
number; revisit once the next real change (trigram context, most likely) lands.

## Consequences

- Adding more test cases is a one-line TSV edit, not a Kotlin change —
  keeps the fixture growable toward "wide variety" without churn
- The report is honest about *why* something fails (category breakdown +
  actual suggestions returned), not just red/green
- The single `BASELINE_PASS_RATE` assertion is deliberately loose (checks
  regression, not a fixed target) so the test suite doesn't block on
  categories we haven't tuned yet — the categorized printout is the real
  signal, the assertion is just a regression tripwire
- Deferred: the harness doesn't yet test `UserTrie` learning behavior
  (recency/frequency boosts from actually-typed words) — it evaluates
  cold-start (fresh install) quality only, since that's the harder and
  more universal problem
