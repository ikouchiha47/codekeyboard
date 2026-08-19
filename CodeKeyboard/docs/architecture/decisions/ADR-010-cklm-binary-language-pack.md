# ADR-010: CKLM Binary Language Pack (words + phrases, byte-log-prob, Kotlin mmap)

**Status:** Accepted (direction). Format spec + compiler + reader to implement.
**Date:** 2026-08-19

## Context

ADR-009 chose a custom binary pack (placeholder name `cklm`) as the long-term
on-device contract for uni/bi/tri n-grams, replacing JSON/TRIF loaders. This ADR
defines that format concretely and resolves the open questions:

1. **`.cklm` is not a real standard format.** Research (2026-08-19) confirmed no
   library or project emits `.cklm`; the closest real thing is KenLM's trie binary
   (`.klm`/`.binary`). The name in ADR-009 was a placeholder. This ADR defines it:
   **CKLM = CodeKeyboard Language Model**, our own format.
2. **AOSP `.dict` is rejected** for this purpose: it is a *dictionary* (unigram +
   bigram next-word + shortcuts), not a general n-gram LM — no trigrams, no
   phrases, hard 8 MB cap (3-byte offsets).
3. **KenLM `.klm` is rejected** for runtime: it is C++ (JNI wrapper or Kotlin
   reimplementation of a bit-packed trie). The app is pure Kotlin/Java today
   (verified: zero native code in tree); adding NDK + CMake + JNI + 3 ABIs buys
   nothing at our workload (one context lookup per keystroke, memory-bound,
   sub-10µs in either language).
4. **SwiftKey's production pattern** (patents US8655647/US9659002) is the
   reference: WDP-pruned n-gram LM, **byte-log-probability compression**, 2–20 MB
   per language, **direct context→followers lookup** (explicitly not an FST).
   Our design converges on the same shape.
5. **Byte-log-prob compression is not patent-blocking.** The general technique
   (quantize a log-probability to one byte) is used in open source by AOSP `.dict`
   (1-byte freq, 255=1.0, each decrement ÷1.15) and FlorisBoard `.flict`
   (1-byte freq 0–255). SwiftKey's patents cover their specific pipeline, not the
   idea.
6. **Phrases** (multi-word suggestions) are the next product surface (ADR-009
   sentence-completion roadmap). The format must support them from day one so we
   do not redesign the pack later.

### Related ADRs

- **ADR-009** — language-pack roadmap; `cklm` placeholder now defined here.
- **ADR-008** — `Ngram` cascade, KN trigrams, scoring policy (unchanged; this ADR
  changes storage and packaging only).
- **ADR-007** — eval harness (used for verification gates below).
- **ADR-003** — native suggestions path.

---

## Goals

1. **One mmap binary per locale** carrying vocab + context trie (uni/bi/tri) +
   follower lists + phrase terminals — shared string/vocab table.
2. **Byte-log-prob compression:** 1 byte per follower score (SwiftKey/AOSP-style),
   the dominant size lever in the file.
3. **Kotlin-native reader** via `MappedByteBuffer` — no JNI, no C++, no NDK.
4. **Phrase support:** multi-word terminals in the same trie, no separate format.
5. **Size:** English uni+bi+tri pruned top-k in a bounded pack (target **single-digit
   to low-tens of MB**); never ship raw count dumps.
6. **Verification gate:** quantized-score top-N agreement vs the unquantized model
   must be measured and pass before the pack ships (same methodology as the
   vocab-cap test).

### Non-goals (this ADR)

- Neural LM or FST sentence decoding (ADR-009 phase 3, separate ADR).
- Replacing `user.trie` or the personalization pipeline.
- Multi-token *decoding* — only *storage* of phrase terminals.
- Claiming parity with Gboard/SwiftKey quality in v1.

---

## Architecture

### Format overview (CKLM v1)

Single file, all sections byte-aligned, mmap-friendly. Sections in order:

```text
┌───────────────────────────────────────────────┐
│ Header (fixed 96 bytes)                       │
│   magic "CKLM"  version u16  word_id_bytes u8 │
│   score_min f32  score_max f32                │
│   vocab_count u32  node_count u32             │
│   follower_count u32  phrase_count u32        │
│   offsets: vocab, nodes, children, followers  │
│   file_size u64                               │
├───────────────────────────────────────────────┤
│ Vocab table                                   │
│   offsets: u32 × vocab_count                  │
│   blob: UTF-8 strings, NUL-terminated         │
│   (word-ID = array index, sorted by string)   │
├───────────────────────────────────────────────┤
│ Trie nodes (16 bytes each, flat array)        │
│   children_offset u32  child_count u16        │
│   followers_offset u32  follower_count u8     │
│   phrase_score u8  flags u8                   │
├───────────────────────────────────────────────┤
│ Children (6 bytes each, sorted by word_id)    │
│   word_id u16  child_node u32                 │
├───────────────────────────────────────────────┤
│ Followers (3 bytes each, pre-sorted by score) │
│   word_id u16  score u8                       │
└───────────────────────────────────────────────┘
```

**Key encodings:**

- **Word IDs: `u16`** (2 bytes). The compiler caps vocab at **65,535 words,
  selected by unigram count** — the union of tri/bi/unigram inputs is ~427K,
  far above the u16 ceiling. The vocab-cap analysis showed K=64K retains
  99.9% top-1 agreement, so the cap is nearly lossless. Out-of-vocab
  contexts/followers are pruned at compile time. This is a documented ceiling
  (see Consequences); a future format version can widen to u32 via the
  `word_id_bytes` header field.
- **Scores: `u8` byte-log-prob.** Log10 map of the observed score range onto
  0–255. Header stores the **log10 range** `[score_min, score_max]` (e.g.
  `[log10(0.0002), log10(1.0)] ≈ [-3.7, 0]`). Quantize:
  `byte = round((log10(score) − score_min) / (score_max − score_min) × 255)`.
  Decode: `score = 10^(score_min + (byte / 255) × (score_max − score_min))`.
  **Decision (2026-08-19): log10 from day one, not linear.** Fusion with
  `user.trie` + `phrases.trie` is a definite future surface, and log10 is
  strictly better there: uniform *relative* precision (~3.4%/step across the
  whole range vs linear's absolute precision that starves small probabilities),
  and it matches log-space fusion arithmetic (add log-probs directly). Same
  1 byte/follower either way; both maps are monotonic so ranking is unaffected
  (linear verified 100% top-1/5/10 agreement on `swiftkey_tri_cap10.json`;
  log10 must be verified the same way before shipping). The header's 24
  reserved bytes can carry a `score_encoding` field (0=linear, 1=log10) if we
  ever need to revisit. Reader (task E) must read the version and honor the
  encoding.
  **Range (2026-08-20): computed over the trigram tier + top-255 unigram tier
  only** — `[-3.699, 0]` on the real pack. Bigram scores are excluded from the
  range: the bigram tail below the floor clamps to byte 0 (secondary tier).
  This keeps the gate-validated trigram precision intact (a global range
  including the bigram tail would widen the floor to ~-4.0 and starve the
  trigram tier). Log10 gate verified 2026-08-20: 100% top-1/5/10 agreement,
  zero flips on `swiftkey_tri_cap10.json`.
- **Follower lists:** variable count per context (0–255 cap, per ora-1 decision:
  store what the delta/WDP pruner keeps). The **root node's unigram followers
  are capped at the top-255 words by count** (u8 `follower_count` ceiling).
  Followers are **pre-sorted by score**, so ranking is preserved by file order
  regardless of quantization error.
- **Phrase terminals:** a node with `phrase_score > 0` is a complete multi-word
  phrase; the trie path from root to that node *is* the phrase. No separate
  phrase section needed — `phrase_count` = nodes with `phrase_score > 0`.

### Context trie semantics

The trie is keyed by word-ID sequences. Depth = context length:

| Depth | Node represents | Used for |
|---|---|---|
| 0 | root | unigram context (next word from nothing) |
| 1 | one word | bigram context |
| 2 | two words | trigram context |
| ≥3 | longer sequence | phrase paths (and future 4/5-gram) |

A node's follower list answers "what comes next after this context". A node with
`phrase_score > 0` can additionally be suggested as a complete phrase when the
user's current context is a prefix of it.

**Lookup:** walk the trie from root, binary-searching each node's sorted children
for the next word-ID (O(log children) per step). At the context node, read the
follower list directly. Phrase suggestion = bounded walk from the context node
collecting phrase terminals within `max_extension` words (v1: 1–3).

### Reader API (Kotlin)

```kotlin
class LanguagePack private constructor(private val buf: MappedByteBuffer) {
    companion object {
        fun open(file: File): LanguagePack   // FileChannel.map(READ_ONLY)
    }

    fun word(id: Int): String                // vocab decode
    fun id(word: String): Int                // binary search over sorted vocab

    fun followers(context: List<Int>): List<Pair<Int, Float>>  // ranked, decoded scores
    fun phrases(context: List<Int>, maxExtension: Int = 3): List<Pair<List<Int>, Float>>
}

// Adapters to existing seams (ADR-009):
//   WordDictionary  — prefix/fuzzy current-word completion (vocab + trie)
//   NgramModel(order=2/3) — next-word from context (follower lists)
//   PhraseCompleter — multi-word chips (phrase terminals)
```

Hot path discipline: no allocations per keystroke — reuse a scratch buffer for
the context walk; decode only the top-N followers actually needed.

### Compiler (Python, build-side)

```text
scored trigram JSON (nested: {"ctx": {"followers": [...], "support": N}})
  + bigram JSON (flat: {"ctx": [[word, score], ...]})
  + unigram TSV (word<TAB>count)
  → pass 1: vocab union + log10 range (trigram + top-255 unigram tiers)
  → select top-65,535 vocab by unigram count (prune out-of-vocab contexts/followers)
  → pass 2: three-tier trie (root unigrams / depth-1 bigrams / depth-2 trigrams)
  → quantize scores to u8 (log10 map over header range)
  → write en.cklm
JSON kept only as debug/eval intermediate.
```

### Verification (quantization gate)

Same methodology as the vocab-cap test (`vocab_cap.py`):

1. **Quantize simulation (Python):** take the scored JSON, quantize every score
   to u8 via the header range, re-rank, compare top-N per context vs unquantized.
   Report agreement rates (top-1, top-5) and any ranking flips.
2. **Reader parity (Kotlin test):** load the compiled `en.cklm`, assert the reader
   returns byte-identical follower lists to the quantized JSON for a sample of
   contexts.
3. **Eval harness (ADR-007):** run `AutocompleteEvalTest` against the pack-backed
   models; no regression vs the JSON baseline.

**Success bar:** top-1 agreement ≥ 99.5%, top-5 agreement ≥ 99%, zero curated
fixture regressions, reader parity exact. If the bar fails, widen quantization
(2-byte scores) or revisit the range mapping — do not ship silently.

**Gate result (2026-08-20): PASSED.** `verify_quantization.py` on
`swiftkey_tri_cap10.json` (thr=25, 269,397 contexts): linear AND log10 both
100% top-1/5/10 agreement, zero ranking flips. Log10 range [-3.699, 0].

---

## Files

| File | Change | Role |
|---|---|---|
| `docs/architecture/decisions/ADR-010-cklm-binary-language-pack.md` | **New** | This decision |
| `scripts/compile_cklm.py` | **New** | JSON/counts → `en.cklm` (prune, quantize, trie, vocab) |
| `scripts/verify_quantization.py` | **New** | Top-N agreement: quantized vs unquantized |
| `android/app/src/main/java/com/codekeyboard/LanguagePack.kt` | **New** | mmap reader + vocab/trie/follower/phrase access |
| `android/app/src/main/java/com/codekeyboard/WordDictionary.kt` | **New** | Pack-backed prefix/fuzzy completion (replaces `Trie` for locale) |
| `android/app/src/main/java/com/codekeyboard/PackNgramModel.kt` | **New** | `NgramModel(order=2/3)` over pack follower lists |
| `android/app/src/main/java/com/codekeyboard/PhraseCompleter.kt` | **New** | Phrase-terminal walk (v1 minimal) |
| `android/app/src/main/assets/en.cklm` | **New** | Compiled pack (replaces `en.trie` + `bigrams.json` + `trigrams.json` in prod) |
| `android/app/src/main/java/com/codekeyboard/Ngram.kt` | **Modify** | Construct pack-backed models; cascade policy unchanged |
| `android/app/src/main/java/com/codekeyboard/CodeKeyboardIME.kt` | **Modify** | Open pack by locale; wire `WordDictionary`/`NgramModel` |
| `android/app/src/main/java/com/codekeyboard/SuggestionStrategy.kt` | **Modify** | Base dict from pack |
| `android/app/src/main/java/com/codekeyboard/Trie.kt`, `BigramModel.kt`, `Trigram.kt`, `BigramModelAdapter.kt` | **Later remove** from prod path once pack parity | Legacy loaders (keep for tests) |
| `android/app/src/test/java/com/codekeyboard/LanguagePackTest.kt` | **New** | Reader parity + lookup correctness |
| `user.trie` / `UserTrie*` | **No change** | Personalization |

---

## Consequences

### Easier

- One mmap asset per locale; smaller APK than fat JSON trigrams.
- No JNI/C++/NDK — pure Kotlin reader, matches the current codebase.
- Byte-log-prob is the dominant size lever (~8x smaller score storage: 1 byte vs
  8-byte double; ~2.7M followers ≈ 19 MB saved vs doubles).
- Phrase support is free at the format level (trie terminals) — no redesign when
  sentence completion lands.
- ADR-008 cascade policy work still applies on top of pack-backed models.

### Harder / deferred

- **The format is ours to maintain** — no ecosystem tooling, no spec beyond this
  ADR + source. Compiler and reader must stay in lockstep (version field).
- **u16 word-ID ceiling (65536).** Natural vocab is 28–60K today, but a future
  corpus could exceed it. Escape hatch: `word_id_bytes` header field → u32 in a
  format v2; compiler asserts and refuses to emit an overflowing pack.
- **Quantization precision** is a real trade-off — must pass the verification
  gate before shipping; if it fails, widen to 2-byte scores.
- **Phrase quality** is unproven — phrase terminals come from high-support
  n-grams; curation/quality work is a follow-on, not in v1.
- Legacy JSON/TRIF loaders stay in tree until pack parity is proven (ADR-009
  gate E equivalent).

### Trade-offs

| Choice | Trade-off |
|---|---|
| Kotlin mmap (this ADR) | No JNI complexity; JVM bounds checks (JIT-compiled, sub-10µs — irrelevant at one lookup/keystroke) |
| KenLM `.klm` | Proven format, but C++/JNI/NDK cost for zero practical gain at this workload |
| AOSP `.dict` | Ready-made packs, but no trigrams/phrases, 8 MB cap, dictionary-not-LM |
| u16 word IDs | 2 bytes/follower entry (vs 4 for u32); 64K vocab ceiling documented |
| u8 scores | 1 byte/follower; 256 levels ≈ 0.04 log10/step — must pass verification gate |
| Phrase terminals in trie | One structure for words+phrases; phrase walk is bounded (1–3 words) |

---

## Workflow

### Task list

| ID | Task |
|---|---|
| A | Land this ADR; link from ADR-009 status (cklm placeholder now defined) |
| B | ✅ `scripts/verify_quantization.py` — quantize scored JSON to u8, re-rank, report top-N agreement vs unquantized (linear + `--log10` modes) |
| C | ✅ Decision gate: PASSED — log10 100% top-1/5/10, zero flips → u8 confirmed |
| D | ✅ `scripts/compile_cklm.py` — vocab + trie + follower lists + phrase terminals → `en.cklm` (three-input, vocab cap, log10) |
| E | `LanguagePack.kt` — mmap reader (header, vocab, trie walk, follower decode, phrase walk) |
| F | `LanguagePackTest.kt` — reader parity vs quantized JSON + lookup correctness |
| G | `WordDictionary.kt` — pack-backed prefix/fuzzy completion; parity vs `Trie` |
| H | `PackNgramModel.kt` — `NgramModel(order=2/3)` over pack follower lists |
| I | Wire `Ngram` + `CodeKeyboardIME` + `SuggestionStrategy` to pack behind flag; JSON/TRIF fallback |
| J | Eval harness (ADR-007): pack-backed vs JSON baseline — no regression |
| K | On-device install + manual verification (per project convention) |
| L | Remove prod dependency on `bigrams.json` + `en.trie` + `trigrams.json`; keep fixtures for tests |
| M | Delete `BigramModelAdapter` / JSON-only loaders from prod path |
| N | Phrase terminals: extract high-support multi-word n-grams → compile → `PhraseCompleter` UI (follow-on) |

### Dependency edges

```text
A → B → C
C → D → E → F
E → G, H (parallel after E)
G|H → I → J → K
J → L → M
D → N (N also needs phrase extraction, follow-on)
```

### Wave table

| Wave | Tasks | Can parallelise? |
|---|---|---|
| 0 | A, B | yes |
| 1 | C (needs B) | — |
| 2 | D (needs C) | — |
| 3 | E (needs D) | — |
| 4 | F, G, H (need E) | yes |
| 5 | I (needs G+H) | — |
| 6 | J (needs I) | — |
| 7 | K (needs J) | — |
| 8 | L, M (need J) | L then M |
| 9 | N (needs D; independent of I–M) | — |

### Critical path

```text
A → B → C → D → E → F → G/H → I → J → K → L → M
```

### Blocking notes

- **B/C was the risk gate — now PASSED** (log10 100% top-1/5/10, zero flips).
  u8 confirmed; no 2-byte scores needed.
- **E** is the highest-risk implementation task: mmap reader correctness (endian,
  offsets, bounds) — `LanguagePackTest` parity is mandatory, not optional.
- **I** touches shipping IME code; keep behind a flag with JSON/TRIF fallback
  until J is green.
- **L/M** must not delete legacy loaders until pack parity is proven (J).
- **N** (phrases) is deliberately a follow-on — the format supports it, but
  phrase extraction/curation quality is a separate workstream.

---

## Decision summary

1. **Format:** CKLM v1 — one mmap binary per locale: header + vocab + context
   trie + children + followers, with phrase terminals in the trie.
2. **Compression:** byte-log-prob scores (u8, SwiftKey/AOSP-style), u16 word IDs
   (vocab capped at 65,535 by unigram count), variable follower count per context
   (0–255; root unigram followers capped at 255), followers pre-sorted by score.
3. **Reader:** Kotlin-native `MappedByteBuffer` — no JNI, no C++, no NDK.
4. **Verification:** quantization gate (top-N agreement) before shipping; reader
   parity test; ADR-007 eval no-regression.
5. **Phrases:** supported at the format level now; extraction/UI is a follow-on.

---

## References

- SwiftKey patents US8655647 / US9659002 (WDP pruning, byte-log-probability
  compression, context→followers lookup).
- AOSP LatinIME `.dict` v4 (1-byte log-scale freq, mmap via `MmappedBuffer`).
- KenLM binary format docs (trie/probing, quantization, mmap).
- FlorisBoard `.flict` (deprecated; 1-byte freq, trigram support).
- ADR-008 (cascade policy), ADR-009 (language-pack roadmap), ADR-007 (eval).