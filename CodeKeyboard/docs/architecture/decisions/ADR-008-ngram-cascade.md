# ADR-008: N-Gram Cascade (Ngram/NgramModel) for Multi-Order Next-Word Prediction

**Status:** Plumbing + `trigrams.json` (19MB OpenSubtitles) in place. OpenSubs **bigrams** built
(register-alignment experiment) but **not** shipped over Norvig — offline shows no generated gain
and a curated regression (see checkpoint below). Cascade policy fix remains the critical path.
Real Gradle eval and on-device still pending.
**Date:** 2026-08-16

## Status log

- **2026-08-16** — initial cascade plumbing (`Ngram`/`NgramModel`/`Trigram`/`BigramModelAdapter`)
  shipped; `trigrams.json` built from OpenSubtitles; OpenSubs-sourced `bigrams.json` built as a
  register-alignment experiment but not shipped. Cascade arbitration (task L) identified as the
  critical path — see status above and checkpoints below for full detail.
- **2026-08-17** — implemented true Kneser-Ney smoothing and a verified candidate-generation fix
  in `extract_trigrams.py`; neither changed eval numbers, because the remaining gap is a genuine
  bigram/trigram register disagreement, not a smoothing or candidate-coverage bug (see checkpoint
  below — this also corrects a wrong root-cause claim made earlier in the session). Evaluated and
  ruled out `nltk.lm` for bulk generation (too slow at real scale, ~181h extrapolated); considered
  and did not pursue KenLM (wouldn't resolve a data-level disagreement either). Task L (cascade
  arbitration via score interpolation) remains the confirmed critical path, now better-scoped.

## Checkpoint (2026-08-16, OpenSubs bigrams build — register alignment experiment)

### Goal

Build `bigrams.json` from the **same** cleaned OpenSubtitles corpus as `trigrams.json`
(`en_sample_big_cleaned.txt`, 11.2M lines) so trie + bigram + trigram share one spoken register
(option O2 in the corpus-register checkpoint). Does **not** replace cascade arbitration.

### Build

```bash
# Norvig production backup
cp android/app/src/main/assets/bigrams.json \
   android/scripts/corpus_raw/opensubtitles/bigrams_norvig_backup.json

# Text-corpus mode added to extract_bigrams_v2.py (--input-format text)
android/scripts/.venv/bin/python scripts/extract_bigrams_v2.py \
  --input android/scripts/corpus_raw/opensubtitles/en_sample_big_cleaned.txt \
  --input-format text \
  --output android/scripts/corpus_raw/opensubtitles/bigrams_opensubs_mc5.json \
  --trie android/app/src/main/assets/en.trie \
  --min-bigram-count 5 \
  --max-followers 10
```

| Artifact | Path | Size | Predecessors |
|---|---|---|---|
| Norvig production (still shipped) | `android/app/src/main/assets/bigrams.json` | 1.3MB | 18,218 |
| Norvig backup | `.../corpus_raw/opensubtitles/bigrams_norvig_backup.json` | 1.3MB | 18,218 |
| **OpenSubs bigrams (not shipped)** | `.../corpus_raw/opensubtitles/bigrams_opensubs_mc5.json` | **6.2MB** | **58,093** |

Same smoothing as production v2: absolute discount `d=0.75`, unigram backoff from `en.trie`,
stopword penalty 0.5. Token filter matches `extract_trigrams.py` (`^[a-z']+$`).

### Offline eval (same fixtures / hard cascade as before)

| Config | generated next-word (621) | curated next-word (39) |
|---|---|---|
| bigram-only **Norvig** (prod) | 14/621 = **2.3%** | 29/39 = **74.4%** |
| bigram-only **OpenSubs** mc5 | 14/621 = **2.3%** | 27/39 = **69.2%** |
| cascade tri + Norvig | 29/621 = **4.7%** | 28/39 = **71.8%** |
| cascade tri + OpenSubs | 29/621 = **4.7%** | 27/39 = **69.2%** |

### Curated flips (bigram-only)

**Norvig pass / OpenSubs fail (5):**

- `you ` → expect can/are/have/will — OpenSubs prefers know/don't/want/think/got (spoken)
- `please let me ` → know — OpenSubs drops `know` from top-5
- `as soon as ` → possible — OpenSubs has you/i/long/soon, no `possible`
- `on the other ` → hand — OpenSubs has people/way/side, no `hand`
- `this is a good ` → idea — OpenSubs drops `idea`

**OpenSubs pass / Norvig fail (3):**

- `in order to ` → get
- `it looks like ` → you
- `can you please ` → let

Net curated −2 cases. Generated unchanged. Cascade still dominated by trigram tier on the
shared idiom contexts (tri answers first), so OpenSubs bigrams do **not** fix the cascade
curated dip either.

### Decision

- **Do not replace** shipped `bigrams.json` with the OpenSubs build.
- Keep OpenSubs bigrams on disk for further A/B (mix, KN, cascade experiments).
- Register alignment alone is **not** a free win: spoken bigrams help some chatty curated
  phrases and hurt written idioms / function-word contexts the curated set rewards.
- **Next lever unchanged:** cascade arbitration (task L). Optional later: weighted
  Norvig∪OpenSubs merge (O3), not a blind swap.

### Script change

`scripts/extract_bigrams_v2.py` gained `--input-format {norvig,text}`, `--min-bigram-count`,
and plain-text pair counting (same tokenizer as trigrams). Norvig path unchanged.

---

## Checkpoint (2026-08-16, next-step rationale — cascade policy + corpus register)

### What is actually limiting next-word quality now

Three problems got tangled; they need separate levers:

| # | Problem | Evidence | Wrong next move | Right next move |
|---|---|---|---|---|
| 1 | **Structural ceiling of 1-word context** | ADR-001/007: bigram-only generated next-word stuck ~2.1% after scoring fixes | More bigram rescoring alone | Keep multi-order context (this ADR) |
| 2 | **Cascade policy** | Offline: first non-empty higher tier always wins; 3 written idioms lost to thin OpenSubs trigrams | Bigger OpenSubs / tighter min-count (already swept; quality flat) | Confidence gate, thin-context backoff, or score blend across tiers |
| 3 | **Register mismatch across assets** | `en.trie` = OpenSubtitles (spoken); `bigrams.json` = Norvig/Google Web (formal); `trigrams.json` = OpenSubtitles (spoken); eval = 6 mixed genres | Train on the 6-genre **eval** corpora (poisons the yardstick) | Align train registers, or make tiers cooperate under mixed register |

The G/H offline result is therefore a **mixed but informative win**, not a failed experiment:
generated next-word more than doubled; curated lost exactly the cases where spoken trigrams
overrode strong written bigrams. Filtering never moved those five contexts — they are common
enough to survive every min-count tried.

### Recommended sequence (do not reorder casually)

1. **Fix cascade arbitration** in `Ngram.nextWords` (architectural). Options, simplest first:
   - **A. Confidence gate** — use trigram only if top score ≥ τ (or top1/top2 margin large); else fall through to bigram.
   - **B. Thin-context backoff** — skip higher tier when follower mass/count proxy is below a floor (needs a strength signal in the asset or derived from scores).
   - **C. Interpolated blend** — rank-merge or `λ·score_tri + (1-λ)·score_bi` when scores are comparable across tiers.
   Success bar before calling this done: generated stays ≥ ~4.5%; curated recovers to ≥ 74.4%; the three lost idioms (`let me know`, `as soon as possible`, `on the other hand`) pass again when possible without killing the two trigram wins.
2. **Run real Kotlin `AutocompleteEvalTest`** against shipped assets — offline Python is a simulator; do not tune λ/τ only on Python if the harness disagrees.
3. **Only then revisit corpora** (see “Corpus options” below) if register mismatch still dominates after arbitration is honest.
4. **On-device (task K)** after eval says the policy/data choice is real.
5. **Pentagram / higher order** only after order-3 tier policy is settled. Gboard’s production reference is a Katz-smoothed 5-gram; that is the long-term shape, not the next patch.

### What not to do next

- Bigger OpenSubtitles download “to see if 4.7% becomes 8%” before cascade policy is fixed.
- Shipping pure override cascade as-is (current design) — curated regression is measured.
- Jumping to pentagram while order-3 arbitration is wrong.
- Training n-gram seeds on Gutenberg / Wikinews / NPS Chat / Cornell / GitHub READMEs — those are **eval-only** (ADR-007). Using them as train data invalidates the unbiased fixture.

### Train vs eval corpora (keep this distinction hard)

| Asset / corpus | Role | Register | Notes |
|---|---|---|---|
| `bigrams.json` (Norvig `count_2w.txt`) | **Train** (shipped) | Formal / web-literary | ADR-001 Consequences: skews literary; scoring fixed register only partially |
| `trigrams.json` (OpenSubtitles) | **Train** (shipped, 19MB) | Casual / spoken dialogue | Built this ADR; good for chatty next-word, weak on written idioms |
| `en.trie` (OpenSubtitles-derived unigrams) | **Train** (shipped) | Casual / spoken | Already aligned with trigram register, **not** with Norvig bigrams |
| Gutenberg fiction/nonfiction, Wikinews, NPS Chat, Cornell movie-dialog, GitHub READMEs | **Eval only** | Mixed six genres | Feed `gen_autocomplete_corpus.py` → `autocomplete_eval_cases_generated.tsv`; never train seeds on these |

### Corpus options if OpenSubtitles alone is the wrong train source

OpenSubtitles is not “wrong” — it doubled honest next-word — but it is **one register**. If after cascade arbitration curated idioms still lose, consider train-side changes (still never touching eval corpora as train):

| Option | What | Pros | Cons / cost |
|---|---|---|---|
| **O1. Keep OpenSubs trigrams + fix cascade** | No new download | Cheapest; already have +2.6pp generated | May still under-serve written idioms if bigram tier is too weak when trigram abstains |
| **O2. Rebuild `bigrams.json` from OpenSubtitles** (or OpenSubs-primary) | Same pipeline family as trigrams; align bigram+trigram+trie register | Removes Norvig literary skew (ADR-001 Bad); consistent spoken stack | Loses some written-idiom strength Norvig currently provides; must re-run full eval |
| **O3. Mixed-register bigrams** (Norvig ∪ OpenSubs, with source weights or merge rules) | Cover both formal idioms and chat | Best coverage in principle | Merge/scoring design work; risk of neither register winning cleanly |
| **O4. Alternate spoken/web sources for trigrams** (e.g. larger subtitle dumps, other permissively licensed conversational text) | More casual data without eval contamination | Only if O1+cascade still data-limited | License + clean pipeline cost; **do not** expect this to fix the five flip cases (already common enough) |
| **O5. Written-register trigrams** (web/news-like, permissively licensed) | Strengthen idioms OpenSubs misses | Directly targets curated losses | May regress casual generated-fixture gains; another asset or mixture |
| **O6. True multi-order LM build from one corpus** (bigram+trigram counts + proper backoff from the same text) | Matches how Katz/KN systems are trained | Cleanest LM story; comparable scores across orders | Larger build; may replace separate Norvig bigram path |

**Default bias until measured otherwise:** finish cascade arbitration (O1 path) before committing to O2–O6. Corpus swaps are expensive and easy to mis-attribute if tier policy still lies.

### Smoothing vocabulary (do not equate these)

See also `docs/adr-001-bigram-prediction.md` smoothing addendum — clarified so “same technique family” is not read as “identical algorithm”:

| Method | When lower order is used | What we shipped |
|---|---|---|
| **Katz smoothing** | Good-Turing discount on **observed** n-grams; **backoff only when count = 0** (unseen), with backoff weights | **Not** implemented as-is |
| **Absolute discounting + interpolation** (what `extract_bigrams_v2.py` does) | Fixed `d` shaved off observed counts; **always** blend with lower-order (unigram from `en.trie`) via `λ` | **Yes** — bigram seed build |
| **Kneser-Ney** | Discount + backoff/interpolation to **continuation probability** (in how many distinct contexts a word appears), not raw unigram frequency | **Not** implemented (would fix “Francisco”-class issues; needs distinct-context stats) |
| **This ADR’s runtime cascade** | Higher order if it returns any non-empty list | **Not** Katz backoff — it is hard override, which is why thin trigrams beat strong bigrams |

Gboard’s public production description (Katz-smoothed interpolated 5-gram) remains the north-star **shape** (multi-order + principled backoff). Gaps vs that reference: no true Katz/KN; context length in the cascade is only starting at trigram; neural/federated on-device LMs are out of scope.

### References (smoothing / production LM)

- Chen & Goodman, smoothing survey: https://arxiv.org/pdf/cs/0108005
- Kneser-Ney overview: https://en.wikipedia.org/wiki/Kneser%E2%80%93Ney_smoothing
- Gboard / federated keyboard LM (Katz-smoothed 5-gram context): https://www.researchgate.net/publication/328825912_Federated_Learning_for_Mobile_Keyboard_Prediction
- Jurafsky & Martin, *Speech and Language Processing* Ch. 3 (N-gram Language Models) — the
  primary teaching reference for everything in this ADR (smoothing, backoff, interpolation,
  perplexity): https://web.stanford.edu/~jurafsky/slp3/3.pdf
- Maskey (Columbia), large-scale n-gram language model training via MapReduce — relevant if a
  future corpus pull outgrows single-machine Python processing (not needed at current scale,
  the largest build this ADR ran — 11.2M lines — still completes in minutes):
  https://www.cs.columbia.edu/~smaskey/CS6998-0412/supportmaterial/langmodel_mapreduce.pdf

---

## Checkpoint (2026-08-16, task G/H)

Ran the full pipeline: `download_opensubtitles.py --lines 15000000` -> `clean_opensubtitles.py`
(11,199,508 lines kept) -> `extract_trigrams.py`. Swept `--min-trigram-count` (unfiltered/73.8MB,
5/28MB, 10/19MB) via `android/scripts/eval_ngram_offline.py` (offline eval script, avoids
re-pasting inline Python per sweep) — quality was flat across all three (~4.5-4.7% generated
next-word, 71.8% curated next-word), so shipped the smallest: **min-count=10, max-followers=5,
19MB**, copied to `android/app/src/main/assets/trigrams.json`.

**Result vs. production bigram-only baseline:**

| | generated next-word | curated next-word |
|---|---|---|
| production (bigram-only) | 2.1% | 74.4% |
| trigram (19MB) + bigram fallback | **4.7%** | 71.8% |

Real, more-than-doubling improvement on the honest/unbiased fixture. Curated dipped 74.4% -> 71.8%
(29/39 -> 28/39) — investigated by diffing which specific cases flipped rather than assuming noise:

```
"please let me "  -> expected "know"     : bigram passes, trigram fails (tell/see/get/ask/do)
"as soon as "     -> expected "possible" : bigram passes, trigram fails (i/you/we/the/he)
"on the other "   -> expected "hand"     : bigram passes, trigram fails (side/one/day/way)
"in order to "    -> expected "get"      : bigram fails, trigram passes
"it looks like "  -> expected "you"      : bigram fails, trigram passes
```

Net -1 case (3 lost, 2 gained) explains the exact 74.4%->71.8% delta. This is why the number stayed
flat across all three filter sizes tested: these 5 contexts (`let me`, `soon as`, `the other`, ...)
are common enough to survive every min-count threshold tried, so filtering more aggressively never
touched them.

**Design flaw this exposes, not yet fixed:** `Ngram.nextWords()`'s cascade always prefers the
highest-order tier if it returns *any* non-empty result — it never compares confidence/strength
between tiers. A thin trigram context (OpenSubtitles register) can override a strong bigram context
even when the bigram answer is more reliable, which is exactly what happened on "let me know" /
"as soon as possible" / "on the other hand" — common *written-English* idioms that are apparently
less dominant in casual movie dialogue than in the bigram seed's broader text. Fix is architectural
(blend/compare scores across tiers instead of "first non-empty tier wins"), not a data or filtering
problem — more/less trigram data provably didn't move these 5 cases at all.

**Not yet done:** real Gradle `AutocompleteEvalTest` run against the new asset (only the offline
Python simulation has been run so far), on-device verification, and the cascade scoring-comparison
fix described above.

---

## Checkpoint (2026-08-17, true Kneser-Ney + candidate-generation fix + nltk.lm evaluation)

### True Kneser-Ney implemented in `extract_trigrams.py`

The "Smoothing vocabulary" table above listed Kneser-Ney as **not implemented** — it now is.
`build_unigram_continuation_p()` replaces raw-unigram-frequency backoff with true continuation
probability `P_cont(w) = N1+(*, w) / N1+(*, *)` (distinct preceding contexts, not occurrence
count) — the textbook fix for the "Francisco" problem (frequent only via "San Francisco", bad
generic backoff guess anywhere else). `build_bigram_kn_p()` and `score_trigram_followers()`
recurse through this correctly (trigram backs off to a bigram KN estimate that itself backs off
to continuation probability, not raw frequency at any level).

**Result: identical eval numbers to the simplified version (4.7% / 71.8%).** Verified this is a
real null result, not a silent bug — individual scores did shift (e.g. `0.6256→0.6258`), just
never enough to change top-5 membership. Root cause: the smoothing formula only rescores
candidates already present in `followers` (observed trigram completions) — backoff was
computing a value for unseen candidates but that value was never used, because unseen
candidates were never added to the pool in the first place. KN's real value only shows up when
backoff can *introduce* a candidate the higher order never saw; this implementation didn't do
that yet.

### Candidate-generation fix

`score_trigram_followers()` candidate pool changed from `followers.keys()` (trigram-observed
only) to `followers.keys() | bigram_kn_p[w2].keys()` (trigram-observed UNION bigram-observed-for-w2).
A bigram-only candidate gets `count=0` (so its discounted term is 0) and its entire score comes
from the backoff term — exactly the "no direct high-order evidence, real evidence one order down"
case KN is supposed to handle.

**Verified working via a controlled smoke test** (`/tmp/smoke_corpus2.txt`) where a word existed
only as a bigram follower, never as the literal trigram: it correctly surfaced in the top-4 purely
via backoff. Confirmed this was not the case before the fix.

**On the real corpus: same eval numbers again (4.7% / 71.8%), and `let me` → `[tell, see, get,
ask, do]` unchanged, byte-for-byte.** Investigated why rather than assuming a bug:

```
count(let, me, know) trigram = 958      <- NOT zero. Directly observed, substantially.
count(let, me, do) trigram   = 1207     <- also directly observed, and more often
total(let, me) trigram       = 33,669
```

**This corrects a wrong claim made earlier this session.** I had explained the "let me know"
regression as a missing-candidate problem ("this trigram context was never observed, backoff
should surface it") without actually checking the counts. The real explanation: `know` has
substantial direct trigram evidence (958 observations) — it's just genuinely outranked by `do`
(1207) in this corpus. This is not a candidate-generation bug, not a smoothing-formula bug, and
not fixable by any smoothing library (KenLM would compute the same real counts from the same
real data). It's a **register disagreement**: `bigrams.json` (Norvig, written English) and
`trigrams.json` (OpenSubtitles, spoken English) are both correct for their own register, and
genuinely disagree on which continuation is more common. The candidate-generation fix is still
correct and kept (it likely helps other contexts among the 287,143 that *are* genuinely
candidate-starved) — it just doesn't change this specific, most-discussed example.

Re-tested the OpenSubtitles-only bigram (`bigrams_opensubs_mc5.json`) against the
candidate-fixed trigram to see if same-register consistency changes anything now:

| bigram source | generated next-word | curated next-word |
|---|---|---|
| OpenSubtitles (same corpus as trigram) | 4.7% | 69.2% |
| Norvig (written, current production) | 4.7% | 71.8% |

Same pattern as the pre-fix checkpoint. Confirms (again) that unifying register doesn't resolve
this — it's a genuine cross-corpus disagreement requiring real blending, not a data-alignment fix.

### `nltk.lm.KneserNeyInterpolated` evaluated and ruled out for bulk generation

Verified `nltk.lm` has a real interpolated-KN implementation (`KneserNeyInterpolated`, formula
confirmed by reading its source — `alpha`/`gamma` in `nltk.lm.smoothing.KneserNey` matches the
textbook interpolated formula exactly). But it's built for small-scale scoring (a few sentences'
perplexity), not bulk table generation: benchmarked at real corpus scale (200k-line training
slice, 45,483-word vocab) and measured **22 `score()` calls/sec**, not the ~60K/sec a toy
13-word-vocab benchmark had suggested. Extrapolated to our real scale (287,143 contexts × even
just a bounded ~50-candidate pool each, not the full vocab): **~181 hours**. Root cause visible
in `nltk.lm`'s source: `_continuation_counts()` scans `self.counts[order].items()` on every call
rather than using an indexed lookup — not vectorized/optimized for repeated bulk queries.

**Decision: kept the hand-rolled implementation, now that both real gaps (formula correctness,
candidate generation) are fixed and verified, not just claimed.** `nltk.lm` would compute the
same numbers from the same data (verified: register disagreement isn't a smoothing-algorithm
problem) at a 8,000x+ slower rate for our use case. **KenLM** (real production C++ toolkit) was
considered as the more legitimate "just use a library" alternative but not pursued for the same
reason — it would confirm the same underlying counts, not resolve the register disagreement,
and adds real build/infra cost (C++ compilation) this project doesn't currently need elsewhere.

### Corrected: Smoothing vocabulary table (see earlier checkpoint) — Kneser-Ney row

Was: "Not implemented (would fix 'Francisco'-class issues; needs distinct-context stats)".
**Now: implemented** in `extract_trigrams.py` (`build_unigram_continuation_p`, `build_bigram_kn_p`),
confirmed correct against `nltk.lm`'s own source formula. Single fixed discount `d=0.75`
throughout — not "modified" KN (which uses separate discounts for count=1/2/3+ buckets); noted
honestly as a remaining simplification, not claimed as the fully generalized textbook version.

### Next: task L, option C (interpolated blend across tiers)

Confirmed the real lever is language-model **interpolation** — a named, standard technique
(KenLM's `interpolate` tool, SRILM's `-mix-lm`, both operate on this exact problem: combining
models trained on different corpora/registers via a weighted blend, not a hard switch). Not
adopting either toolkit directly: they interpolate whole trained model files offline; our
actual decision point is a live per-keystroke choice in `Ngram`'s Kotlin cascade over two
differently-shaped JSON artifacts, which the toolkit workflows don't map onto directly.

Plan (unchanged in spirit from option C in the earlier checkpoint, now informed by the
interpolation-vs-hard-switch lesson learned twice this session — first with backoff-vs-interpolated
KN, now with cascade tier selection):

1. Add a `support` field (total observed count) to both `bigrams.json` and `trigrams.json`'s
   per-context output — the missing signal that makes cross-tier comparison meaningful (today's
   per-context score normalization makes every context's top pick read as `1.0`, so two contexts
   with wildly different real evidence are indistinguishable by score alone).
2. `BigramModel` gets a new additive method exposing its own support count — does not touch
   `nextWords()` or any existing method.
3. `Ngram.nextWords()` changes from "first non-empty tier wins" to a weighted blend using both
   tiers' support counts, not a hard pick.

---

## Context

`docs/adr-001-bigram-prediction.md`'s smoothing addendum and `ADR-007-autocomplete-eval.md`'s
checkpoint #2 established, with measured evidence, that `BigramModel`'s real-text next-word
accuracy (2.1% on the generated eval fixture) is a **structural ceiling of a 1-word-of-context
model**, not a scoring or corpus-quality problem — confirmed by A/B testing an entirely different
seed corpus (AOSP/HeliBoard) and finding it performed worse, not better.

The next lever, flagged in both of those docs, is extending context length: `P(next | 2 previous
words)` instead of `P(next | 1 previous word)`. An offline trigram experiment (unigram/bigram/
trigram counts built consistently from one OpenSubtitles sample, absolute-discounting + backoff
smoothing, same technique family as `BigramModel`'s rescored seed) measured a real but marginal/
mixed result on a 15.6M-word sample: generated next-word 2.1% -> 2.6%, curated next-word
74.4% -> 71.8%. Not a clean win yet — likely data-volume-limited (see Consequences) — but the
architecture to support it needed to exist regardless of when the data catches up, and needs to
support a third order (pentagram) later without a second rewrite.

`BigramModel` cannot be that architecture directly: it predates this need, owns a
decay/persistence/personalization layer trigram/pentagram don't have yet, and per explicit
project decision **must not be modified to implement a new shared interface** — it stays exactly
as-is, working, single-purpose.

---

## Goals

- One interface (`NgramModel`) that a bigram, trigram, or pentagram model all implement
  identically — no per-order method signature (`nextWords(prevWord)` vs `nextWords(w1, w2)` vs...).
- A priority-ordered cascade (`Ngram`) that tries highest-order-first, falls back automatically
  when a model lacks enough context or lacks data for the context it has, and requires **zero
  changes** to add a new order later beyond constructing one more model and adding it to a list.
- `BigramModel` stays completely unmodified — zero lines changed. Bridged into the cascade via an
  adapter, not retrofitted.
- The suggestion-quality-critical prefix+context path (`BigramAwareSuggestionStrategy`, already
  ~100% in eval, not the diagnosed weak point) is untouched by this ADR.
- User-learned personalization (`BigramModel.recordTransition`, the decay layer) keeps working
  exactly as today — this ADR only changes how next-word *candidates are read*, not how they're
  *learned*.
- A missing or malformed higher-order asset (e.g. `trigrams.json` not yet built) must degrade
  that tier to "unavailable" and fall through the cascade — never crash the IME.

---

## Architecture

Interfaces first, implementations second, wiring last (per project ADR convention).

### Interfaces

```kotlin
interface NgramModel {
    val order: Int   // 2 = bigram, 3 = trigram, 5 = pentagram
    fun nextWords(vararg context: String, prefix: String = "", n: Int = 5): List<String>
}

interface NgramDataSource {
    fun load(): Map<String, List<Pair<String, Float>>>  // contextKey -> [(word, score), ...] desc
}
```

`vararg context` is what lets one method signature serve every order — a trigram implementation
requires `context.size >= 2` and looks at the last 2 words; a bigram requires 1; a pentagram would
require 4. `Ngram` (below) always calls through this same signature regardless of which order it's
talking to.

`NgramDataSource` exists so no model implementation touches `JSONObject`/file I/O directly — the
storage format (currently JSON, matching `bigrams.json`'s existing shape) can change later (e.g. a
packed binary trie, like `Trie.kt`'s TRIF format) by writing one new class, with zero changes to
`Trigram`/`AbstractNgram`.

### Implementations

- **`AbstractNgram(order, dataSource)`** — the shared lookup logic (load seed lazily, filter by
  prefix, sort by score, take top-N) every order needs identically. Lazy-loads defensively: a
  failed/missing asset load is caught and degrades to `emptyMap()` (that tier has no data, cascade
  skips it) rather than throwing.
- **`Trigram(dataSource) : AbstractNgram(order = 3, dataSource)`** — one line. Implements
  `NgramModel` natively; needs no adapter, unlike `BigramModel` (see below), because it was written
  from scratch against this interface rather than predating it.
- **`BigramModelAdapter(bigramModel: BigramModel) : NgramModel`** — wraps the real, existing,
  unmodified `BigramModel` instance and delegates `nextWords()` to it directly. This is the
  Adapter-pattern bridge that satisfies "BigramModel must not change": the adapter implements the
  new interface, `BigramModel` doesn't have to. Critically, this means the cascade's bigram tier is
  byte-for-byte the same personalized (seed + user-learned decay layer) behavior as today — not a
  second, simpler, personalization-blind reader of `bigrams.json`.

### Orchestration

```kotlin
class Ngram(private val models: List<NgramModel>) {
    val maxContextNeeded: Int = (models.maxOfOrNull { it.order } ?: 1) - 1

    fun nextWords(context: List<String>, prefix: String = "", n: Int = 5): List<String> {
        for (model in models) {
            val needed = model.order - 1
            if (context.size < needed) continue
            val result = model.nextWords(*context.takeLast(needed).toTypedArray(), prefix = prefix, n = n)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }
}
```

This is Chain-of-Responsibility: each tier tries in list order, defers to the next on a miss
(either "not enough context yet" or "no data for this context"), caller doesn't know or care which
tier actually answered. `maxContextNeeded` is derived from whatever's in the list (Open/Closed) —
adding a `Pentagram` later is a one-line change to the list passed into `Ngram(...)`; the call
site's context-window sizing adjusts itself, nothing else changes.

### Wiring (`CodeKeyboardIME.kt`)

```kotlin
// onCreate — BigramModel construction is UNCHANGED
bigramModel = BigramModel(this).also { it.load() }
ngram = Ngram(listOf(
    Trigram(JsonNgramDataSource(this, "trigrams.json")),
    BigramModelAdapter(bigramModel),
))
suggestionStrategy = BigramAwareSuggestionStrategy(...)   // UNCHANGED — prefix path untouched

// new: rolling context window, sized dynamically off the cascade, not hardcoded
private val recentContext = ArrayDeque<String>()
private fun pushRecentWord(word: String) {
    recentContext.addLast(word)
    while (recentContext.size > ngram.maxContextNeeded) recentContext.removeFirst()
}
```

Two read call sites change (both currently `bigramModel.nextWords(singleWord, n=5)`):
- The `"space"` action handler (next-word suggestions after committing a space)
- `handleSuggestionTap`'s post-tap suggestion refresh

Both become `ngram.nextWords(recentContext.toList(), n = 5)`.

Two write call sites (`bigramModel.recordTransition(...)`, the learning path) are **explicitly
unchanged** — `pushRecentWord(word)` is added alongside them (to keep `recentContext` in sync) but
`recordTransition` itself keeps writing directly to `bigramModel`, since `Ngram` has no concept of
learning/decay by design; that stays exclusively `BigramModel`'s job.

---

## Files

| File | Change | Role |
|---|---|---|
| `android/app/src/main/java/com/codekeyboard/Ngram.kt` | New (done) | `NgramModel`, `NgramDataSource`, `JsonNgramDataSource`, `Ngram` cascade |
| `android/app/src/main/java/com/codekeyboard/Trigram.kt` | New (done) | `AbstractNgram`, `Trigram` |
| `android/app/src/test/java/com/codekeyboard/NgramSanityTest.kt` | New (done) | Cascade fallback + vararg dispatch + asset-degradation + `maxContextNeeded` + adapter-delegation unit tests |
| `android/app/src/main/java/com/codekeyboard/BigramModelAdapter.kt` | New (done) | Adapter bridging `BigramModel` into the cascade |
| `android/app/src/main/java/com/codekeyboard/BigramModel.kt` | **No change** | Explicit constraint — stays exactly as-is |
| `android/app/src/main/java/com/codekeyboard/CodeKeyboardIME.kt` | Modify (done) — `onCreate`, `"space"` handler, `handleSuggestionTap`, one new `pushRecentWord` helper | Wiring |
| `android/app/src/main/assets/trigrams.json` | New asset (done — 19MB, min-count=10, max-followers=5) | Trigram seed data (OpenSubtitles) |
| `android/scripts/download_opensubtitles.py` | New (done) | Streams a configurable-size OpenSubtitles sample |
| `android/scripts/clean_opensubtitles.py` | New (done) | Strips speaker-labels/dialogue-markers/short lines |
| `scripts/extract_trigrams.py` | New (done) | Builds `trigrams.json` from cleaned corpus |

---

## Consequences

**Good:**
- Adding a 4th order (pentagram) later touches one line (the model list in `onCreate`) — no
  interface, cascade, or call-site changes required.
- `BigramModel`'s personalization/decay behavior is fully preserved at the exact call sites being
  changed — verified by construction (the adapter delegates, doesn't reimplement).
- A missing/bad higher-order asset degrades gracefully instead of crashing.
- Storage format for any order can change independently of model/scoring logic
  (`NgramDataSource` seam).

**Bad / deferred:**
- **Cascade policy is the open quality bug.** `trigrams.json` exists (19MB, min-count=10) and
  offline generated next-word improved 2.1% → 4.7%, but curated dipped 74.4% → 71.8% because
  first-non-empty higher tier overrides strong bigrams. Fix is tier arbitration (see next-step
  checkpoint), not a larger OpenSubtitles pull — min-count sweeps were flat.
- **Register stack is inconsistent:** Norvig bigrams (formal) + OpenSubs trie/trigrams (spoken) +
  mixed-genre eval. Corpus realignment (rebuild/mix bigrams, alternate trigram sources) is a
  deliberate follow-up **after** cascade arbitration; eval corpora must stay train-free.
- Runtime cascade is **hard override**, not Katz-style unseen-only backoff or KN continuation
  backoff — see smoothing vocabulary in the next-step checkpoint and ADR-001 addendum.
- Real `AutocompleteEvalTest` + on-device (K) still not done against the new asset/wiring.
- `BigramModelAdapter.kt` and IME wiring (I, J) are done; personalization path unchanged.

---

## Workflow

**Task list:**
- A. `Ngram.kt` (interfaces + cascade) — **done**
- B. `Trigram.kt` (+ `AbstractNgram`) — **done**
- C. `NgramSanityTest.kt` — **done**
- D. `download_opensubtitles.py` — **done**
- E. `clean_opensubtitles.py` — **done**
- F. `extract_trigrams.py` — **done**
- G. Re-run D at larger sample size, re-run E/F, re-measure offline — **done** (11.2M cleaned
  lines; min-count sweep flat; shipped 19MB min-count=10). Further “just download more OpenSubs”
  is **deprioritized** until cascade arbitration lands (see next-step checkpoint).
- H. Decide whether `trigrams.json` is worth keeping as a real asset — **provisional yes** on
  generated-fixture doubling; **conditional** on fixing cascade policy (and possibly register)
  so curated does not stay regressed. Not a rubber-stamp ship of pure-override cascade.
- I. `BigramModelAdapter.kt` — **done**
- J. `CodeKeyboardIME.kt` wiring (onCreate construction, `pushRecentWord`, 2 call-site swaps) — **done**
- K. Real-device install + manual verification (per project convention: install release build,
  test before/after next-word suggestion behavior)
- L. **Cascade arbitration** — replace first-non-empty-wins with confidence gate / thin backoff /
  score blend; re-measure offline + known flip cases; then real `AutocompleteEvalTest`
- M. **Corpus-register follow-up (only if L leaves curated weak)** — choose among O2–O6 in the
  next-step checkpoint. **O2 pure OpenSubs bigrams tried (2026-08-16): built, measured, not
  shipped** (generated tie, curated 74.4%→69.2%). Remaining M options: weighted Norvig∪OpenSubs
  merge (O3), alternate sources, single-corpus multi-order. Do **not** train on ADR-007 eval genres.

**Dependency edges:**
- G needs D, E, F — done
- H needs G — provisional decision recorded; final “ship override as-is” blocked on L
- I needs A, B — done
- J needs I — done
- L needs A (cascade) + G/H data in tree; does not need K
- M needs L (otherwise corpus A/B is confounded by bad tier policy)
- K needs J + preferably L (and M if taken) so device checks the intended behavior

**Wave table:**

| Wave | Tasks | Can parallelise? |
|---|---|---|
| 0 | A, B, C, D, E, F | done |
| 1 | G, H (provisional), I, J | done |
| 2 | L (cascade arbitration + real eval) | — **critical path now** |
| 3 | M (optional corpus register) | — only if L insufficient |
| 4 | K (on-device) | — |

**Critical path (updated):** L → (optional M) → K.  
G/H “more OpenSubs” is no longer the critical path.

**Blocking notes:**
- L is the real decision gate for shipping trigram-assisted next-word without curated regression.
- M must not use eval corpora as train data.
- J already touched shipping IME code; further IME edits stay minimal.
- Do not equate shipped absolute-discount + unigram interpolation with Katz or Kneser-Ney
  (ADR-001 + next-step checkpoint).

---

## Checkpoint (2026-08-17, same-corpus bigram-vs-trigram — resolves a confound in earlier checkpoints)

Every trigram-vs-bigram comparison in this ADR up to this point compared trigram (OpenSubtitles)
against bigram (Norvig) — two **different corpora**, not a clean test of "does more context help."
Ran the missing controlled comparison: bigram-only vs trigram-only, **both from OpenSubtitles**,
against the same eval fixtures.

| | generated next-word | curated (restricted to the 19/39 cases with >=2 words context) |
|---|---|---|
| OpenSubs bigram-only | 2.3% | 8/19 = 42.1% |
| OpenSubs trigram-only | **3.7%** | 8/19 = 42.1% (tied, exact) |

**Trigram matches or beats bigram once the corpus confound is removed** — confirms the textbook
expectation (Jurafsky & Martin Ch. 3; more context should help, given matched training data) does
hold here. The earlier "trigram loses" framing in this ADR's prior checkpoints was measuring a
corpus/register difference, not an order effect. This is a real point in favor of the "single
unified corpus, single coherent cascade" direction (see prior checkpoint) over continuing to
patch a two-corpus blend.

**Why 4-gram/5-gram would be sparser still, not just "somewhat" sparser:** distinct n-gram count
grows roughly with vocabulary size raised to the n — e.g. a 10k-word vocabulary has ~10k possible
unigrams, ~100M possible bigrams, ~1 trillion possible trigrams (exponential, not linear). Every
additional order compounds this. Relevant if a future pentagram tier is considered — the corpus
that comfortably supports trigram today (11.2M lines) would likely be inadequate for 5-gram
without a substantially larger pull.

References added: Jurafsky & Martin Ch. 3 (primary teaching source for all of the above) and
Maskey's MapReduce n-gram training paper (relevant only if a future corpus pull outgrows
single-machine processing — not needed yet; current builds finish in minutes).
