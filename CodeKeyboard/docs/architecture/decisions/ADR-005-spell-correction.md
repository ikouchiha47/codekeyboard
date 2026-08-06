# ADR-005: Spell Correction — Levenshtein DFS on Trie, Fallback-Only

## Status
Accepted.

---

## Context

Users make typos. "raiming" should suggest "raining". The existing suggestion
pipeline (exact A* prefix traversal on `en.trie` + `user.trie`) returns nothing
for a misspelled prefix, leaving the suggestion bar empty.

Four candidate algorithms were evaluated. Full research summary below.

---

## Algorithms Evaluated

### 1. Levenshtein Automaton — Schulz & Mihov (2002)
*"Fast String Correction with Levenshtein Automata"*
*International Journal of Document Analysis and Recognition, 5(1):67–85.*
https://dmice.ohsu.edu/bedricks/courses/cs655/pdf/readings/2002_Schulz.pdf

A parametric NFA is pre-built offline (once, per edit distance k) by encoding
trie traversal transitions as a characteristic-vector lookup table. At query
time the DFA is constructed in O(|W|) and intersected with the trie in one
pass. Simultaneously delivers prefix completions and fuzzy corrections.
Memory: ~KB for the precomputed table (fixed, independent of dictionary size).
Production use: Apache Lucene `FuzzyQuery`.

**Parametric table size grows fast with k:** k=1 → compact; k=2 → ~960
entries; k=3 → ~25,000 entries; k≥3 is not viable on mobile.

### 2. Levenshtein DFS with DP Row per Node — Hanov (2011)
*"Fast and Easy Levenshtein Distance using a Trie"*
https://stevehanov.ca/blog/?id=114

Walk the trie depth-first. At each node compute one Levenshtein DP row from
the parent's row and the edge label character. Pruning rule: if
`min(currentRow) > threshold`, prune the entire subtree (no descendant can be
within edit distance). Collect terminals where `row[|W|] ≤ threshold`.
Memory: O(depth × |W|) for the call stack — zero additional structures.
Visits ~1–5% of trie nodes in practice for k=1–2 on a 100k-word dictionary.

### 3. BEVA — Zhou et al. (2016)
*"BEVA: An Efficient Query Processing Algorithm for Error-Tolerant Autocompletion"*
*ACM Transactions on Database Systems, 41(1), 2016.*
https://dl.acm.org/doi/10.1145/2877201

Introduces the Edit Vector Automaton (EVA): a DFA over bit-encoded edit
vectors. Maintains a compact set of "active nodes" in the trie updated
incrementally per keystroke. Eliminates ancestor–descendant redundancy in the
active set. Designed specifically for the incremental typing model (one char
added at a time). Better than Schulz & Mihov for interactive autocomplete
because it amortises work across keystrokes rather than re-running per query.
Complexity: O(|active nodes| × alphabet) per keystroke — near-constant once a
few characters have been typed.

### 4. SymSpell — Wolf Garbe
https://github.com/wolfgarbe/SymSpell

Pre-computes all delete-variants of every dictionary word into a hash map.
Query generates delete-variants of the input, looks them up. Near-constant
query time. Weakness: cannot return prefix completions (not a trie — no
ordering by prefix). Memory at k=2 for a 100k-word dictionary: 60–150 MB
additional heap. **Rejected** — memory cost is prohibitive on Android and it
requires a separate structure for prefix completion.

### 5. BK-Trees — Burkhard & Keller (1973)
*"Some Approaches to Best-Match File Searching"*
*Communications of the ACM, 16(4):230–236.*

Metric tree built on Levenshtein distance. Query visits ~17–61% of the
dictionary per call. No prefix completion. **Rejected** — worse query
performance than trie DFS with pruning, and does not integrate with the
existing trie.

---

## Decision

**Implement Levenshtein DFS with DP row (Hanov approach) as a fallback path.**

Rationale:

1. **Zero memory overhead.** Uses the `UserTrieNode` graph already in memory.
   For the TRIE2 base dictionary, child-iteration is exposed via a new
   `Trie.iterateChildren(nodeOffset, block)` API — no copy of data.

2. **Works on both tries.** `UserTrie` (node graph) and the base `Trie`
   (ByteBuffer TRIE2) both support child iteration. One algorithm, two targets.

3. **Fallback-only preserves the fast path.** Fuzzy search only fires when
   exact prefix traversal returns fewer than k results. Common-case typing
   (correct spelling) pays zero additional cost.

4. **Threshold scaling prevents noise.** Edit distance threshold is a function
   of word length: ≤3 chars → 0 (no fuzzy), 4 chars → 1, ≥5 chars → 2. This
   prevents short prefixes from matching almost everything.

5. **Simplest correct implementation.** ~150 lines of Kotlin. Can be shipped
   in the current phase. BEVA would be the upgrade path if profiling shows
   latency exceeding 50ms on low-end devices at k=2 — the trie structure is
   identical, only the traversal driver changes.

6. **"raiming" → "raining" works.** Edit distance 1 (m→n substitution at
   position 4). Word length 7 → threshold 2. Found in the first fuzzy pass.

### Why not BEVA now

BEVA's incremental keystroke amortisation matters when you re-run the search
on every keystroke for a large dictionary. At k=1–2 on 100k words, the Hanov
DFS with pruning already completes in <20ms on a 2019 mid-range Android device
(measured via `UserTrieBenchmark`). BEVA is the right upgrade if that budget is
exceeded. The trie structure does not need to change for the upgrade.

### Why not Levenshtein Automaton (Schulz & Mihov) now

The automaton approach requires building the parametric table at k=1 or k=2,
which is straightforward but adds ~300 lines of non-obvious code (characteristic
vectors, state table construction). The DP-row DFS achieves the same result
with less risk. If we ever need k=3 the automaton becomes the only viable
option — at that point the implementation investment is justified.

---

## Consequences

- Typos within edit distance 1 (≤6 chars) or 2 (≥7 chars) surface in suggestions
- Exact prefix suggestion path unchanged — zero latency impact for correct typing
- `Trie.kt` gains a child-iteration API (one new method)
- `FuzzyTrieSearch.kt` is a standalone utility testable without Android context
- `SuggestionStrategy` interface is unchanged — `MergedSuggestionStrategy` calls
  fuzzy as a second pass internally
- Future upgrade to BEVA: swap `FuzzyTrieSearch.kt` internals only, interface
  and callers unchanged
