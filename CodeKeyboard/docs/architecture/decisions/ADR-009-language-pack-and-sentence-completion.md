# ADR-009: Language Pack (uni/bi/tri) + Sentence Completion Roadmap

**Status:** Accepted (direction). **v1 format decided in ADR-010: `cklm`** (AOSP
`.dict` spike rejected — no trigrams, 8 MB cap, dictionary-not-LM). Compiler +
reader implemented; production still ships `en.trie` + `bigrams.json` +
`user.trie` only until pack parity (ADR-010 tasks I–M).
**Date:** 2026-08-18

## Context

### What we ship today

| Asset | Role |
|---|---|
| `en.trie` (TRIF binary) | Prefix / fuzzy **current-word** completion |
| `bigrams.json` | **Next single word** given one previous word |
| `user.trie` | Personal vocabulary (stays separate forever) |

`trigrams.json`, `Ngram` / `Trigram` / `BigramModelAdapter` plumbing (ADR-008) exist in
tree for cascade experiments; **trigrams are not a committed production ship** until a
packed format and size budget land.

### What hurts

1. **JSON n-grams are not how production keyboards ship LMs.** AOSP/OpenBoard use mmap’d
   binary `.dict`; Gboard uses FST n-gram + quantized neural (TFLite); Microsoft SwiftKey
   uses packed language packs + neural (ONNX). JSON is a fine *build intermediate*, a poor
   *runtime* format (size, parse cost, RAM).
2. **Two static formats** (`en.trie` + `bigrams.json`) duplicate vocabulary strings and
   force parallel loaders (`Trie`, `BigramModel`, adapters).
3. **Full-corpus builds** (SwiftKey en_US blogs/news/twitter, ~4.3M lines) produce far more
   n-gram mass than an APK can hold as JSON; shipping requires prune + binary pack.
4. **Multi-word “finish part of the sentence”** is the next product surface after
   uni/bi/tri next-word. That is **language-model decoding** (several tokens), not a
   separate non-LM feature. It may be implemented later as **weighted FST path decode**
   and/or a **small on-device neural** LM — after the single-word stack is packed.

### Confirmed external facts (2026-08-18)

- AOSP `.dict` (e.g. Helium314 `main_en_us.dict`, ~2.8MB, magic `0x9BC13AFE` v202) holds
  **unigrams + bigrams (next-word)** in one binary when the source `.combined` includes
  `bigram=` lines. Helium314 marks EN_US main as **Next-Word Data = yes**.
- `.dict` does **not** provide trigrams, open-ended multi-word decode, or OpenFST.
- OpenFST is a **general weighted-graph LM representation**, appropriate for multi-token
  continuation if we choose classical n-gram decode — not a dedicated “phrase file format.”

### Related ADRs

- **ADR-008** — `Ngram` cascade, KN trigrams, register/eval lessons (still valid for
  *scoring policy*; this ADR changes *storage and packaging*).
- **ADR-001 / bigram design** — next-word product intent.
- **ADR-003** — native suggestions path.

---

## Goals

1. **One static language pack per locale** (mmap-friendly binary) carrying unigram +
   bigram, and trigram when we choose to ship it — shared string/vocab table.
2. **JSON / TRIF remain build or legacy inputs**, not the long-term on-device contract.
3. **Preserve IME seams:** prefix complete, next-word cascade, user trie merge — collapse
   *loaders*, not *concepts*.
4. **Size:** English uni+bi in the few-MB class (AOSP `.dict`-like); uni+bi+tri pruned
   top-k in a bounded pack (target ballpark **tens of MB unpacked max**, prefer less);
   never ship full raw count dumps.
5. **Roadmap slot for sentence completion** (multi-token LM output) via FST and/or neural
   *after* pack v1, without forcing OpenFST into v1.
6. **Multi-language:** en / bn / Hinglish (and later) as separate packs; Hinglish may need
   custom wordlists (e.g. Helium314 `main_hi_zz` pattern).

### Non-goals (this ADR)

- Implementing neural training or OpenFST integration in the first implementation waves.
- Replacing `user.trie` or personalization pipeline.
- Shipping unpruned SwiftKey-scale trigram JSON.
- Claiming parity with Gboard/SwiftKey quality in v1.

---

## Architecture

### Runtime model

```text
                    ┌──────────────────────────────────────┐
  en.cklm (mmap) ──►│  LanguagePack                        │
                    │  WORD (char trie: prefix/fuzzy/unigram)│
                    │  BIGRAM (top-k next)                 │
                    │  TRIGRAM (context trie depth-2)      │
                    │  [future] SENTENCE_LM handle/ref     │
                    └──────────────────┬───────────────────┘
                                       │
              adapters to existing product seams
                                       │
        ┌──────────────────────────────┼──────────────────────────────┐
        ▼                              ▼                              ▼
  WordDictionary                 NgramModel(order=2)            NgramModel(order=3)
  (replaces Trie for locale)     (replaces BigramModel JSON)    (replaces Trigram JSON)
        │                              │                              │
        └──────────────┬───────────────┴──────────────┬───────────────┘
                       ▼                              ▼
               SuggestionStrategy                   Ngram cascade
                       │                         (ADR-008 policy)
                       ▼
                    user.trie  (unchanged, per-install)
```

**Sentence completion (phase after uni/bi/tri pack):**

```text
left context (tokens)
        │
        ├─► optional: multi-step decode from pack n-grams (weak baseline)
        ├─► FST LM path decode (classical multi-token)     ─┐
        └─► small neural LM (quantized TFLite/ONNX)      ─┴─► chip: "you later"
        optional: idiom table for frozen MWEs (as soon as possible)
```

Sentence completion is **the same product family as LM**, emitting **N>1 tokens**.
It is **not** required to live inside AOSP `.dict`. It may be:

- a **section or sibling artifact** referenced by the language pack manifest, or
- a **downloadable pack** if size exceeds base APK budget.

### Format strategy (decision)

| Phase | Format | Contents |
|---|---|---|
| **v1 (decided)** | `cklm` (ADR-010) — vocab + char trie (WORD) + context trie (uni/bi/tri) | Replaces `en.trie` + `bigrams.json` + `trigrams.json` |
| **v2** | `cklm` v2 (if needed) | u32 word IDs / wider scores if corpus grows |
| **v3** | Pack + sentence LM | FST binary and/or quantized neural; optional MWE table |

**v1 decision (2026-08-20): `cklm`** (ADR-010). The AOSP `.dict` spike was
rejected: `.dict` has no trigrams, a hard 8 MB cap, and is a *dictionary*
(unigram + bigram next-word + shortcuts), not a general n-gram LM. `cklm` gives
us trigrams, shared vocab, phrase terminals, and a sentence-LM pointer in one
contract we control.

**Rejected for v1–v2 storage:** production JSON n-grams; OpenFST as the only store for
simple top-k bi/tri tables.

**OpenFST:** allowed **later** as an implementation of **sentence / multi-token**
classical LM decode — not as the replacement for word trie + top-k bigram tables.

### Code evolution (presumed)

| Today | After language pack |
|---|---|
| `Trie` + `en.trie` | `WordDictionary` backed by pack **char-trie section** (prefix + fuzzy + unigram scores) |
| `BigramModel` + JSON | Pack BIGRAM as `NgramModel(order=2)`; JSON loader deleted from prod path |
| `BigramModelAdapter` | **Delete** once pack implements `NgramModel` |
| `Trigram` + `JsonNgramDataSource` | Pack TRIGRAM section; JSON source test-only or gone |
| `Ngram` orchestrator | **Keep** — cascade / support weighting (ADR-008 task L still applies) |
| `SuggestionStrategy` | Same merge; base dict from pack |
| `user.trie` | **Unchanged** |
| Build scripts | Emit `.combined` / counts → `makedict` or `cklm` compiler; JSON optional debug |

IME sketch:

```text
pack = LanguagePack.open(locale)   // mmap file under assets or filesDir
ngram = Ngram(listOfNotNull(
  pack.ngramOrNull(order = 3),
  pack.ngramOrNull(order = 2),
))
strategy = MergedSuggestionStrategy(userTrie, pack.wordDictionary(), ...)
// later:
sentence = pack.sentenceCompleterOrNull()  // FST and/or neural
```

### Build pipeline (data)

```text
corpus (SwiftKey / OpenSubs / …)
  → count (durable/fast extractors)
  → prune (min-count, top-k followers)
  → pack compile (.dict makedict and/or cklm)
  → APK assets or on-demand download
JSON kept only as debug/eval intermediate if useful
```

### Multi-locale

```text
packs/en.cklm
packs/bn.cklm
packs/hi_ZZ.cklm    // Hinglish — custom list; bigrams only if compiled in
```

Active pack selected by keyboard language; missing trigram section → cascade degrades to bi.

---

## Files

| File | Change | Role |
|---|---|---|
| `docs/architecture/decisions/ADR-009-language-pack-and-sentence-completion.md` | **New** | This decision |
| `android/app/src/main/assets/en.trie` | **Later remove** from prod once pack parity | Legacy unigram |
| `android/app/src/main/assets/bigrams.json` | **Later remove** from prod once pack parity | Legacy bigram |
| `android/app/src/main/assets/dicts/` or `packs/` | **New** | Shipped `.dict` / `.cklm` |
| `.../Trie.kt` | **Later remove** from prod once pack parity | Legacy char trie (ASCII-only; superseded by pack char-trie section) |
| `.../BigramModel.kt` | **Modify** → thin / delete JSON path | `NgramModel` from pack |
| `.../BigramModelAdapter.kt` | **Delete** when redundant | — |
| `.../Trigram.kt`, `Ngram.kt` | **Modify** | Data from pack; cascade kept |
| `.../CodeKeyboardIME.kt` | **Modify** | Open pack by locale; wire sentence completer later |
| `.../SuggestionStrategy.kt` | **Modify** | Base dict from pack |
| `scripts/extract_ngrams*.py` | **Keep / evolve** | Build counts → pack compiler input |
| `scripts/compile_language_pack.*` (name TBD) | **New** | JSON/counts/combined → binary pack (defined as `scripts/compile_cklm.py` in ADR-010) |
| Native JNI (BinaryDictionary or cklm reader) | **New** when needed | mmap + query |
| Sentence LM (FST and/or TFLite/ONNX) | **New** (phase 3) | Multi-token completion |
| `user.trie` / `UserTrie*` | **No change** | Personalization |

---

## Consequences

### Easier

- One download/mmap per language; smaller APK than fat JSON trigrams.
- Aligns with industry packaging (AOSP `.dict`, packed LMs).
- Clear seam for sentence completion without rewriting prefix/next-word again.
- ADR-008 cascade policy work still applies on top of pack-backed models.

### Harder / deferred

- Need native or careful ByteBuffer reader for `.dict` / `cklm`.
- AOSP bigrams ≠ KN `bigrams.json` quality; must re-eval before deleting JSON.
- Trigram ship still a size/product decision.
- Sentence completion (FST vs neural) is a **follow-on ADR or phase**, not blocked on
  inventing OpenFST in v1 — but **explicitly next after** uni/bi/tri pack.

### Trade-offs

| Choice | Trade-off |
|---|---|
| `.dict` first | Fast path for en uni+bi; weaker story for tri + custom sections |
| `cklm` first | Full control; more compiler work up front |
| Multi-step n-gram as fake “sentence” | Cheap baseline; quality limited |
| FST sentence LM | Classical, on-device friendly; toolchain weight |
| Neural sentence LM | Best fluent multi-word; training/quantization cost |

---

## Workflow

### Task list

| ID | Task |
|---|---|
| A | Land this ADR; link from ADR-008 status (packaging supersedes JSON ship assumption) |
| B | ~~Spike: load `main_en_us.dict`~~ — **superseded**: `.dict` rejected in ADR-010 (no trigrams, 8 MB cap, dictionary-not-LM) |
| C | Define `LanguagePack` / `WordDictionary` / pack-backed `NgramModel` interfaces (no JSON in interface) |
| D | IME + `SuggestionStrategy`: optional pack path behind flag; keep JSON/TRIF fallback |
| E | Offline eval: pack uni+bi vs production assets (ADR-007 harness) |
| F | ~~Decision gate: ship `.dict` as v1 or proceed to `cklm`~~ — **resolved 2026-08-20: `cklm`** (ADR-010) |
| G | ~~If `.dict` v1: integrate makedict/Helium314 packs~~ — **superseded**: no `.dict` v1 |
| H | ~~If `cklm`: spec binary layout~~ — **done**: ADR-010 defines CKLM v1 (header, vocab, char trie, context trie, followers, phrases) |
| I | Wire trigram section when size budget OK; delete prod JSON trigram assumptions |
| J | Remove prod dependency on `bigrams.json` + `en.trie` after gate E green; keep fixtures for tests |
| K | Delete `BigramModelAdapter` / JSON-only loaders from prod path |
| L | **Sentence completion phase:** multi-token API (`complete(context, maxTokens)`); baseline = chained n-gram |
| M | Spike FST vs small neural for multi-token quality/size/latency; pick one primary |
| N | Pack manifest field or sibling artifact for sentence LM; download policy if > budget |
| O | Optional MWE/idiom table for frozen multi-word expressions (complements, does not replace, LM decode) |
| P | Eval + on-device UAT for next-word and multi-word chips; update ADR-008/007 notes |

### Dependency edges

```text
A → B → C → D → E → F
F → G or H
G → I (optional tri later via cklm migration)
H → I
G|H → J → K
K → L → M → N
L → O (optional parallel after L API exists)
N → P
O → P
```

### Wave table

| Wave | Tasks | Can parallelise? |
|---|---|---|
| 0 | A | — |
| 1 | B, C | yes |
| 2 | D (needs B+C) | — |
| 3 | E | — |
| 4 | F | — |
| 5 | G or H | — |
| 6 | I, J | I optional; J after ship path solid |
| 7 | K | — |
| 8 | L | — |
| 9 | M, O | yes (O optional) |
| 10 | N | — |
| 11 | P | — |

### Critical path

```text
A → B → C → D → E → F → (G|H) → J → K → L → M → N → P
```

Sentence completion (**L–N**) must not start until pack-backed single-word path is
the production default (**K**), so we do not build a third parallel asset zoo.

### Blocking notes

- **B** is higher risk than it looks: AOSP dict JNI surface is large; prefer minimal
  read path (suggest + bigrams only) over full LatinIME port.
- **E** blocks deleting JSON — quality regression on curated idioms (ADR-008) is likely
  if AOSP bigrams are thinner than Norvig/OpenSubs KN.
- **I** (trigram) re-opens APK size; require explicit budget before compile.
- **M** is a product/tech choice (FST vs neural); capture outcome in a short ADR-009
  addendum or ADR-010 rather than silently picking in a PR.

---

## Decision summary

1. **Target:** one mmap language pack per locale for **unigram + bigram (+ trigram)**.
2. **v1:** **`cklm`** (ADR-010) replaces `en.trie` + `bigrams.json` +
   `trigrams.json`. AOSP `.dict` rejected (no trigrams, 8 MB cap,
   dictionary-not-LM).
3. **Keep** `Ngram` cascade and `user.trie`; **collapse** JSON/TRIF loaders over time.
4. **Sentence completion** (multi-word / partial-sentence chips) is the **next major
   phase after** uni/bi/tri pack — implemented as LM multi-token decode (**FST and/or
   neural**), optionally plus a small MWE table — **not** as OpenFST-only packaging for
   v1 top-k tables.

---

## References

- Helium314 aosp-dictionaries (Codeberg): `.dict` packs, “Next-Word Data” column,
  `main_en_us.dict`, Hinglish `main_hi_zz.dict`, makedict via `dicttool_aosp.jar`.
- AOSP LatinIME BinaryDictionary / `.combined` (`word=`, `bigram=`, log-scale `f=`).
- ADR-008 n-gram cascade and eval lessons.
- Industry packaging: AOSP `.dict`; Gboard FST + TFLite; Microsoft SwiftKey packs + ONNX.
