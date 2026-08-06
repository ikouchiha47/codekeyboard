# Phase 7: Spell Correction — Levenshtein DFS on Trie

## What this phase delivers

Fuzzy correction as a fallback when exact prefix traversal returns fewer than
k suggestions. "raiming" → "raining" appears in the suggestion bar. Correct
spelling is completely unaffected — the fast path is unchanged.

See ADR-005 for algorithm selection rationale and rejected alternatives.

---

## Algorithm recap

Levenshtein DFS with DP row per trie node (Hanov 2011). At each node, compute
one DP row from the parent row and the edge character. Prune the subtree if
`min(currentRow) > threshold`. Collect terminals where `row[|W|] ≤ threshold`.

Threshold by word length:
```
len ≤ 3  →  threshold = 0  (no fuzzy, too noisy)
len = 4  →  threshold = 1
len ≥ 5  →  threshold = 2
```

---

## Step-by-step plan

### Step 1 — Fix suggestion tap bug (one line, do first)

Already done in the previous session. Confirmed: removing `finishComposingText()`
before `commitText` in `handleSuggestionTap` fixes the "evolevolved" double-commit.

### Step 2 — Expose child iteration on `Trie` (TRIE2)

File: `Trie.kt`

The existing `Trie` class reads a ByteBuffer TRIE2 file. Its node format
stores children as sorted (char, offset) pairs. Add one package-private method:

```kotlin
internal fun iterateChildren(nodeOffset: Int, block: (Char, Int) -> Unit)
```

This walks the children array at `nodeOffset` and invokes `block` with each
(edgeChar, childOffset) pair. Used by `FuzzyTrieSearch` to drive DFS without
exposing raw buffer internals.

Also expose:
```kotlin
internal fun isTerminal(nodeOffset: Int): Boolean
internal val rootOffset: Int
```

These may already exist privately — promote to `internal`.

### Step 3 — `FuzzyTrieSearch.kt` (new)

A standalone utility that works on either trie type via two adapters:

```kotlin
interface TrieAdapter {
    val rootOffset: Any
    fun isTerminal(node: Any): Boolean
    fun frequency(node: Any): Int        // 0 for base trie nodes
    fun iterateChildren(node: Any, block: (Char, Any) -> Unit)
}
```

Two implementations:
- `UserTrieAdapter(userTrie: UserTrie)` — wraps `UserTrieNode` graph
- `BaseTrieAdapter(trie: Trie)` — wraps `Trie` ByteBuffer via the new
  `iterateChildren` API

Core search function:

```kotlin
fun search(
    adapter: TrieAdapter,
    word: String,
    threshold: Int,
    maxResults: Int,
): List<FuzzyResult>

data class FuzzyResult(val word: String, val editDistance: Int, val frequency: Int)
```

Internal DFS:

```kotlin
private fun dfs(
    node: Any,
    prefix: String,
    prevRow: IntArray,
    word: String,
    threshold: Int,
    adapter: TrieAdapter,
    results: MutableList<FuzzyResult>,
    maxResults: Int,
) {
    if (results.size >= maxResults) return
    val minInRow = prevRow.min()
    if (minInRow > threshold) return   // prune entire subtree

    if (adapter.isTerminal(node)) {
        val dist = prevRow[word.length]
        if (dist <= threshold) {
            results += FuzzyResult(prefix, dist, adapter.frequency(node))
        }
    }

    adapter.iterateChildren(node) { ch, child ->
        val currentRow = IntArray(word.length + 1)
        currentRow[0] = prevRow[0] + 1
        for (j in 1..word.length) {
            val cost = if (word[j - 1] == ch) 0 else 1
            currentRow[j] = minOf(
                currentRow[j - 1] + 1,
                prevRow[j] + 1,
                prevRow[j - 1] + cost,
            )
        }
        dfs(child, prefix + ch, currentRow, word, threshold, adapter, results, maxResults)
    }
}
```

Initial row for the root call: `intArrayOf(0, 1, 2, ..., word.length)`.

Results are sorted by `(editDistance ASC, frequency DESC)` before returning —
corrections closer to the typed word rank higher; among equal-distance results,
more frequent words rank first.

### Step 4 — Threshold helper

```kotlin
object FuzzyThreshold {
    fun forLength(len: Int): Int = when {
        len <= 3 -> 0
        len == 4 -> 1
        else     -> 2
    }
}
```

### Step 5 — Wire into `MergedSuggestionStrategy`

File: `SuggestionStrategy.kt`

```kotlin
class MergedSuggestionStrategy(
    private val userTrie: UserTrie,
    private val baseTrie: Trie,
) : SuggestionStrategy {

    private val userAdapter = UserTrieAdapter(userTrie)
    private val baseAdapter = BaseTrieAdapter(baseTrie)

    override fun suggest(prefix: String, k: Int): List<String> {
        val exact = exactSuggest(prefix, k)
        if (exact.size >= k) return exact

        val threshold = FuzzyThreshold.forLength(prefix.length)
        if (threshold == 0) return exact

        val fuzzy = fuzzySearch(prefix, threshold, k - exact.size)
        val exactSet = exact.toSet()
        return exact + fuzzy.filter { it !in exactSet }
    }

    private fun exactSuggest(prefix: String, k: Int): List<String> {
        val userResults = userTrie.suggest(prefix, k)
        val baseResults = baseTrie.suggest(prefix, k)
        val userWords = userResults.map { it.word }.toSet()
        return (userResults.map { it.word } +
                baseResults.filter { it !in userWords }).take(k)
    }

    private fun fuzzySearch(word: String, threshold: Int, limit: Int): List<String> {
        val userFuzzy = FuzzyTrieSearch.search(userAdapter, word, threshold, limit)
        val baseFuzzy = FuzzyTrieSearch.search(baseAdapter, word, threshold, limit)
        val userWords = userFuzzy.map { it.word }.toSet()
        val merged = userFuzzy +
            baseFuzzy.filter { it.word !in userWords }
        return merged
            .sortedWith(compareBy({ it.editDistance }, { -it.frequency }))
            .map { it.word }
            .take(limit)
    }
}
```

### Step 6 — Tests

File: `FuzzyTrieSearchTest.kt` (new)

All tests use `UserTrie` + `UserTrieAdapter` — no Android context needed.

**Correctness:**
- `raining` inserted, query `raiming` (edit distance 1) → found at threshold 1
- `raining` inserted, query `raiming` at threshold 0 → not found
- Exact match returns edit distance 0
- `help` inserted, query `hlep` (transposition, edit dist 2) → found at threshold 2
- Short prefix `"ra"` (len 2) → threshold 0 → no fuzzy results
- `rain` (len 4) → threshold 1 → `rein` at edit dist 1 found

**Threshold scaling:**
- 3-char query → threshold = 0 → fuzzy returns empty even with close matches
- 4-char query → threshold = 1
- 5-char query → threshold = 2

**Result ordering:**
- Two candidates at edit distance 1: higher-frequency word ranks first
- Candidate at edit distance 1 ranks before candidate at edit distance 2

**Pruning correctness (property test):**
- For any trie and query, fuzzy DFS results ⊆ brute-force linear scan results
- For any trie and query, brute-force results ⊆ fuzzy DFS results (completeness)

**Integration with MergedSuggestionStrategy:**
- Exact match fills k slots → fuzzy not called (use a counting spy on FuzzyTrieSearch)
- Exact returns 2 of k=5 → fuzzy called for remaining 3
- `raining` in base trie, user types `raiming` → `raining` appears in slots

File: `FuzzyTrieSearchBenchmark.kt` (new)

- Query with k=1 on 100k-node trie: p50 and p99
- Query with k=2 on 100k-node trie: p50 and p99
- Budget: p50 < 30ms, p99 < 80ms on JVM (proxy for Android mid-range)

---

## Acceptance criteria

- "raiming" → suggestions include "raining"
- Correct spelling ("rain") → suggestions unchanged, zero latency impact
- All 211 existing tests pass
- Benchmark p50 < 30ms for k=2 on a 100k-word trie
- No additional memory structures beyond the call stack during DFS

---

## Sequence

```
Step 1 (bug fix, done) → Step 2 (Trie child iteration) → Step 3 (FuzzyTrieSearch)
→ Step 4 (threshold) → Step 5 (wire strategy) → Step 6 (tests + benchmark)
```

Steps 2 and 3 can be done simultaneously — `FuzzyTrieSearch` uses `UserTrieAdapter`
exclusively during development and testing; `BaseTrieAdapter` is added in Step 2
and integrated in Step 5.
