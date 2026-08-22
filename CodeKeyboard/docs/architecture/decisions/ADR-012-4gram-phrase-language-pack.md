# ADR-012: 4-gram + Phrase Language Pack (combined corpus, sample-first)

**Status:** Accepted (direction). Sample build first on-device/laptop; full-scale AWS build is a later wave.
**Date:** 2026-08-22

## Context

ADR-010 shipped the CKLM v1 pack: a Katz+WDP-pruned **trigram** LM over the SwiftKey
2014-16 corpus, mmap'd, byte-log-prob scores, with personalization. Two gaps remain:

1. **Context window.** The current model is trigram-only (context depth-2 in the
   pack). Next-word quality and multi-word *phrase* suggestions want a deeper
   window: **4-gram** context (depth-3) measurably improves next-word accuracy,
   and the pack format already reserves `phrase_score` terminals for multi-word
   chips (ADR-010 task N, deliberately deferred).
2. **Training data.** The SwiftKey corpus is 2014–16 web crawl — stale, no modern
   slang/tech terms, and built from a single register. We now hold a much
   larger **OpenSubtitles 2018** dump (3.66 GB gz, 441M lines, conversational
   dialogue) which is both more recent and more typical of keyboard input.

Also available on USB: a **kaikki.org Wiktionary-derived dictionary** (3.2 GB
JSONL). This is a *lexicon* (definitions, POS, inflections), not natural text —
**not** an n-gram training corpus. It may supplement the word list / vocab
coverage only; it does not align with counts. Registered here so no one tries
to count n-grams from it.

### Related ADRs

- **ADR-009** — language-pack roadmap: pack → sentence completion; phrase
  terminals were always part of the plan.
- **ADR-010** — CKLM v1 binary format. This ADR extends the context trie to
  depth-3 and populates the existing phrase-terminal mechanism.
- **ADR-008** — `Ngram` cascade + KN/Katz/WDP scoring. The 4-gram tier slots
  into the same cascade (`NgramModel(order=4)`).
- **ADR-007** — eval harness. Verification gate unchanged (no-regression +
  improvement on top-N).
- **Blog post** `docs/blog/ngram-pipeline-optimization.md` — the 5-stage
  durable pipeline (split → count → reconcile → normalize → score) this ADR
  extends; its lessons (bulk-load SQLite, memoization, top-k pruning, spill-to-
  disk, resumability) all carry forward.

---

## Goals

1. **4-gram next-word model.** A CKLM pack whose context trie reaches depth-3
   (4-gram context), built from a **merged OpenSubtitles-2018 + SwiftKey**
   corpus. Higher-order predictions where evidence exists, graceful 3→2→1
   backoff (ADR-008 cascade).
2. **Phrase terminals populated.** Multi-word suggestion chips (2–4 words)
   sourced from the same corpus via PMI/t-score extraction, stored in the
   pack's phrase terminals, surfaced as chips (ADR-009 sentence/phrase step
   v1).
3. **Sample-first.** The full 441M-line corpus is ~100× the current 4.3M lines;
    a 4-gram build is a combinatorial jump (35.9M trigram rows → est. hundreds
    of millions of 4-gram rows). We therefore **validate the entire pipeline on
    a sampled corpus first** (no more than ~10% of OpenSubtitles) on a
    laptop- scale machine, measure size/latency/quality gates, then scale the
    identical pipeline to full data on AWS (the blog's spot-instance
    approach).
4. **Sizing metrics** captured before-and-after. The sample must produce honest
   projected full-corpus numbers (rows per order, pack size, build time,
   suggest latency) so the AWS run is a "launch same pipeline", not a fresh
   gamble.
5. **Backward compatible pack reader.** Depth-3 reuse the same node/child/
   follower encoding as ADR-010 — existing reader keeps working; the depth is a
   data property, not a format break. Phrase terminals already in the format.

### Non-goals

- Neural LM in this ADR (follow-on separate ADR; data-prep here is LM-agnostic,
  usable for n-gram or neural later).
- kaikki-derived statements of interest (vocab projection may be folded in,
  but is not an n-gram source and is not a gate);
- OpenFST / beam-search sentence decoding (ADR-009 phases 3; out of scope here).

---

## Architecture

The pipeline is a **superset** of the v0-optimized 5-stage extractor from the
blog. Stages are additive, memory-bounded, resumable, and reuse the proven
bulk-load / external-merge / memoized-scoring patterns.

### Corpus handling

```
inputs
  ├─ OpenSubtitles en.txt.gz  (~441M lines, 3.66GB gz)
  └─ SwiftKey en_US           (4.3M lines, 583MB, from Coursera-SwiftKey)
         │
         v        sampled? yes → a sample step takes a line-aligned fraction
   [dedupe + normalize]        (keep a seeded stream, e.g. first-N lines or
         │                      reservoir) BEFORE counting
         v
   clean, dedup'd stream (one pass; store as a staging dir of shards)
```

- **Sample step (when sample-first):** take a **head + tail + mid** window or an
  N%-streaming slice of OpenSubtitles + add full SwiftKey (small) as the
  conversational anchor. Log exact shard sizes / line counts so we can record
  honest growth factors.
- **Dedupe**: OpenSubtitles repeats massively (episode stacking, duplicate
  subtitles); SwiftKey overlaps. Line-exact dedupe (hash set per shard) before
  counting. Deduped, not full-canonical.
- **Normalize**: lowercase, strip speaker/fx-frames, unicode-normalize (NFKC),
  single-space collapse. Reuse/align with `clean_opensubtitles.py`.

### Count / reconcile (order 1-4)

- **Count**: worker-per-shard, in-dict counting with **spill-to-disk sorted
  runs** when the dict exceeds a budget (lower per-gram budget for order-4,
  e.g. 1M entries). Counting is done for unigrams, bigrams, trigrams, **and
  4-grams** held in / emitted by the same worker pass.
- **Reconcile**: k-way heap merge of the runs into **SQLite heap tables, then
  bulk-create indexes** (blog §5). Tables: `ngram_1`, `ngram_2`, `ngram_3`,
  `ngram_4`. Support thresholds: `--min-cnt` per order (e.g. 3,3,2,2 on
  sample; configurable for full run).
- **Normalize/Score** (per existing scripts `build_ngrams[_katz,_swiftkey].py`):
  KN-style discount + backoff over 4→3→2→1 with the blog's memoized-backoff +
  top-k pruning. Output: kept per-order scored follower lists in JSON (same
  shape as ADR-010 `compile_cklm.py` expects, plus a new order-4 file).

### Phrase extraction (new, order-agnostic)

```
ngram_2/3/4  →  candidate multi-word sequences (count ≥ C, span 2-5 words)
            →  PMI / t-score / significance scoring
            →  filter fragments (leading/trailing stopwords, subsequence
               of a longer phrase, non-alpha tokens)
            →  dedupe overlapping phrases (longest/most-significant wins)
            →  phrase list with score + id mapping → for CKLM phrase terminals
```

Uses the existing `extract_wiktionary_phrases.py` / `count_phrase_frequency.py`
patterns as a starting point but with corpus-derived PMI (not static lexicon).
Also surface kaikki multi-word lexico entries as candidates if present, but
they are optional (secondary source, non-gate).

### CKLM pack (compiler + reader)

- **Compiler** (`compile_cklm.py`): already written for depth-2 with node
  `phrase_score`. Extend node depth handling to depth-3; write a phrase
  section when a phrase list is provided. **No header/encoding change** needed
  for depth (node depth is data; `word_id_bytes` unchanged).
- **Reader** (`LanguagePack.kt`): `followers(context)`, `phrases(context,
  maxExtension)` — already correct for arbitrary depth; **add `order`-aware
  helpers** (nothing blocking) + a test that depth-3 paths round-trip.

### New in IME (wiring, small)

- `NgramModel(order=4)` slot in the cascade (`PackNgramModel(pack, order=4)`),
   plus the phrase completer (`PhraseCompleter`) rendering chip suggestions
   from `pack.phrases(...)`. Both are additive; fallback to trigram+ if sample
   shows 4 not-viable.

---

## Files

| File | Change | Role |
|---|---|---|
| `scripts/prep_corpus.py` | **New** | sample slice, dedupe, normalize, shard staging |
| `scripts/extract_ngrams.py` | **Modify** | count/reconcile to order-4 (expose `--order`, `--spill`, `--min-cnt`) |
| `scripts/extract_ngrams_durable.py` | **Modify** | support order-4 in the durable/reusable path (or keep the `swiftkey` variant) |
| `scripts/extract_phrases.py` | **New** | PMI + t-score phrase extraction from ngram_3/4 |
| `scripts/compile_cklm.py` | **Modify** | depth-3 + phrase-terminal emission |
| `scripts/verify_quantization.py` | **Modify** | order-4 tier added to gate |
| `android/app/src/main/java/com/codekeyboard/LanguagePack.kt` | Minor | confirm depth-3 + phrase read (already mostly) |
| `android/app/src/main/java/com/codekeyboard/PackNgramModel.kt` | Minor | allow `order=4` (currently requires 2/3) |
| `android/app/src/main/java/com/codekeyboard/PhraseCompleter.kt` | **New** | chip suggestions from `pack.phrases` |
| `CodeKeyboardIME.kt`, `SuggestionStrategy.kt` | **Modify** | `NgramModel(order=4)` + `PhraseCompleter` wired |
| Sample outputs (pack + phrase list) | **New** | not committed if over git size limits |
| Default corpus assets | **Later remove** | only after sample → full → eval parity (ADR-007) |

---

## Consequences

### Easier

- 4-gram / phrase reuse existing pack reader format, scoring, cascade — no
  new infra.
- Sample-first de-risks the build: each order is measured before the full
  corpus 10-40× jump.
- Corpus merge is additive; SwiftKey acts as small conversational anchor,
  OpenSubtitles provides volume + recency.
- PMI phrase extraction is a known technique with our own existing scripts to
  build on.

### Harder / deferred

- **Data size is the mesh cost**: order-4 rows are expected in the hundreds of
  millions on full corpus; sample-first must confirm row-count growth is
  linear enough to sustain.
- **Sampling bias**: subtitles-over-weight dialogue fragments vs. keyboard
  text; SwiftKey anchor mitigates. Document sampling assumptions.
- **Phrase quality** is unproven — PMI phrase list needs curation/eval (fixture
  cases) before shipping as UI (PhaseC = follow-up).
- **Compute**: full build needs AWS spot (blog §operationalize); sample must
  run on dev metal.

### Trade-offs

| Choice | Trade-off |
|---|---|
| Corpus merge (OS+Swift) | More data + recency; adds dedupe/sampling complexity |
| 4-gram depth-3 | Better word prediction; 3-4× the counts → bigger pack, longer build |
| Sample → AWS | De-risks but delays the “full” results; sample gate must be honest |
| PMI phrases | Good precision on frequent n-grams; needs curation for novel phrases |
| Trigram (ADR-010) as baseline | Simple, proven; context-window ceiling at 2 |

---

## Workflow

### Task list

| ID | Task |
|---|---|
| A | Land this ADR; link from ADR-0x |
| B | Sample corpus: slice (~10% OpenSubtitles) + Swift anchor + dedupe/normalize → staged shards |
| C | `extract_ngrams.py`: extend count/reconcile to order-4 (spill cap, min-cnt per-order) |
| D | Measure sample pass: row counts per order, GB, build time, memory — write sizing sheet |
| E | Reproduce scoring order→3→2→1 with memoized backoff + top-k pruning (extend `build_ngrams_*.py`), evaluator gate (ADR-007) |
| F | `extract_phrases.py`: PMI/t-score extraction, fragment filter, dedupe, score |
| G | extend `compile_cklm.py`: depth-3 nodes + phrase terminals → sample `en.cklm` |
| H | reader (LanguagePack.kt) order-4 path + `PackNgramModel(order=4)` + pack test update |
| I | `PhraseCompleter` + IME wiring behind flag; A/B vs trigram |
| J | Full-suite + eval gate (ADR-007) on sample → decision: proceed full or iterate |
| K | Scale-run on AWS (same scripts, `--order 4`, full corpus) with S3 upload |
| L | Full-size + latency measurement; compare vs sample projections |
| M | Ship sample or full pack; remove legacy trigram-only pack if gated |

### Dependency edges

```text
A → B → C → D
A → F (phrase)  (F needs ngram_3/4 counts from C)
C → E → G → H → I → J
J → K → L
L → M
F → I (phrases wired into completer)
```

### Wave table

| Wave | Tasks |
|---|---|
| 0 | A, B |
| 1 | C (needs B) |
| 2 | D, F (both need C) |
| 3 | E, G (E needs D; G needs C for sample) |
| 4 | H, I (H needs G; I needs H + F) |
| 5 | J (needs I) |
| 6-7 | K → L (needs J/K) |
| 8 | M (needs L) |

### Critical path

```text
A → B → C → D → E → G → H → I → J → K → M
```

### Blocking notes

- **B** (sampling) determines all scaling projections; do it carefully
  (explicit line/algo counts, record exact shard sizes).
- **C** is the only heavy code-change; benchmark on a small shard first
  to validate the 4-gram spill path (blog §4 captures the “measure first”
  lesson).
- **D** is the go/no-go gate: if 4-gram row-count/size of sample ≠ linear
  projection, stop and iterate before full AWS.
- **I/J** keep 4-gram behind a flag until the ABCD gate + eval pass with no
  regression (ADR-007).
- **K** full corpus is a AWS spot instance job (see blog “Operationalizing”).

---

## References

- ADR-009, ADR-010, ADR-008, ADR-007.
- Blog: `docs/blog/ngram-pipeline-optimization.md`.
- Existing scripts: `scripts/build_ngrams*.py`, `scripts/extract_ngrams*.py`,
  `scripts/extract_wiktionary_phrases.py`,
  `scripts/compile_cklm.py`, `scripts/verify_quantization.py`.
- kaikki.org Wiktionary dictionary (lexicon for vocab, not n-gram counts).