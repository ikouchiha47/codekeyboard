# Phase 5: Learnable User Trie — TRIE3 Format, Scoring, and A* Traversal

## What this phase delivers

A mutable, frequency-scored user trie (`user.trie`) that lives alongside the
read-only base dictionary (`en.trie`). Every word the user commits is recorded
with a frequency count. At suggestion time both tries are queried and results
are merged by score — user-frequent words surface first, unknown words (e.g.
`torrent`, `ikouchiha47`) are learned from usage and appear in suggestions once
committed even once.

---

## Research basis

### Hsu & Ottaviano (2013) — Completion Trie
**"Space-Efficient Data Structures for Top-k Completion"**
WWW 2013, pp. 583–594.
DOI: 10.1145/2488388.2488440
PDF: http://groups.di.unipi.it/~ottavian/files/topk_completion_www13.pdf

The foundational paper. Introduces the **Completion Trie**: each internal node
stores the maximum score of any terminal in its subtree (`maxDescendantScore`).
Children are ordered descending by that score. A best-first traversal using a
priority queue expands the highest-potential branch first and terminates as soon
as k results have been collected. Formally proves the traversal is optimal —
identical to branch-and-bound with an admissible upper-bound heuristic.

**Limitation:** all three structures in the paper are static (read-only). No
dynamic insertion. Directly applicable to TRIE2 (base dictionary); not to
TRIE3 (mutable user model).

### PruningRadixTrie — Wolf Garbe, SeekStorm (2022)
Blog: https://seekstorm.com/blog/pruning-radix-trie/
GitHub: https://github.com/wolfgarbe/PruningRadixTrie

The most practical engineering reference for a **mutable** scored trie with
early-exit traversal. Each node stores `maxChildRank` (max frequency of any
descendant terminal). On `addTerm(word, freq)`, the path is walked and each
ancestor's `maxChildRank` is updated if the new frequency exceeds it. Traversal
prunes entire subtrees when `maxChildRank < currentFloor` (the k-th best score
found so far). Benchmarks show 1000x speedup over unscored trie on 6M-term
dictionaries. Supports incremental insert — exactly what TRIE3 needs.

**This is the primary design reference for TRIE3's in-memory structure and
suggest algorithm.**

### DynSDT — Validark (2023–2024)
Paper/demo: https://validark.github.io/DynSDT/
GitHub: https://github.com/Validark/DynSDT

Formally extends Hsu & Ottaviano's Score-Decomposed Trie to the dynamic case.
Maintains a heap invariant: siblings ordered by score (horizontal), parent score
≥ any descendant's score along the heavy path (vertical). This makes top-k
traversal equivalent to heap extraction — O(|p| + k log k) with no separate
priority queue at query time. Proven tightest bound for this problem class.

**Too complex for v1 of TRIE3, but the heap invariant is the ideal end state.**
Noted as a future upgrade path.

### SwiftKey Patent US8782556B2 (Microsoft, 2013)
https://patents.google.com/patent/US8782556B2/en

Confirms the two-dictionary architecture (system dictionary + user dictionary,
queried separately and merged by weighted score) as standard industry practice.
Score = weighted sum of global LM probability + user frequency + edit distance.

### Google Personalized QAC Patent US20200104427A1 (2019)
https://patents.google.com/patent/US20200104427A1/en

Confirms that even at Google scale, candidate generation is a simple
frequency-ranked trie lookup. Neural re-ranking only operates on the small
candidate set. For our keyboard only Stage 1 (trie lookup) is needed.

### Why we are NOT using A* by name

The literature calls this technique "best-first traversal" or "branch-and-bound
on a Completion Trie." The node's maxDescendantScore is an *upper bound on
reward* (not a lower bound on cost), so the A* framing is technically dual. The
algorithms are isomorphic but the field does not use the A* label. We use the
correct terminology: **best-first traversal with subtree pruning**.

---

## Why two tries, not one

| Approach | Problem |
|---|---|
| Modify en.trie in place | Static binary format, no dynamic insert |
| Single mutable trie replacing en.trie | Lose the full base dictionary on cold install; huge rebuild cost |
| HashMap of word → freq | Fast insert/lookup but O(n) prefix scan — no early exit |
| SQLite word + freq table | Good prefix query with index, but extra dependency, no trie traversal |
| **Two tries: en.trie (read-only) + user.trie (mutable)** | Each optimized for its use case; base dict always available |

The two-trie split is confirmed by the SwiftKey patent and Google architecture.

---

## TRIE3 binary format

Used only for `user.trie` (on-disk serialization). The in-memory structure
is a pointer-based mutable trie (see below). TRIE3 is written on flush and
read on startup.

### Node layout — 16 bytes per node

```
Offset  Size  Field
0       4     childrenOffset   (uint32, relative to children section start)
4       2     childCount       (uint16)
6       1     isTerminal       (uint8, 0 or 1)
7       1     reserved         (uint8, 0)
8       4     frequency        (uint32, 0 if not terminal)
12      4     maxDescendantFreq (uint32, max freq of any terminal in subtree)
```

`maxDescendantFreq` on a terminal node equals its own `frequency`. On an
internal node it equals the max of all descendants' frequencies. Computed
bottom-up at serialization time; no traversal needed at query time.

### File header — 16 bytes

```
Offset  Size  Field
0       4     magic            ("TRI3" = 0x54524933)
4       4     nodeCount        (uint32)
8       4     totalCommits     (uint32, sum of all frequencies — for decay)
12      4     reserved         (uint32, 0)
```

`childrenBase = 16 + nodeCount * 16` (same offset calculation as TRIE2 but
with 16-byte nodes and 16-byte header).

---

## In-memory mutable trie structure

```kotlin
class UserTrieNode {
    val children = HashMap<Char, UserTrieNode>()
    var frequency: Int = 0          // 0 = not a terminal word
    var maxDescendantFreq: Int = 0  // max frequency in this subtree
}
```

`maxDescendantFreq` is maintained incrementally on every insert:

```
insert(word, delta=1):
    walk path char by char, creating nodes as needed
    at terminal node: frequency += delta
    on the way back up (post-order): 
        node.maxDescendantFreq = max(node.frequency, max(child.maxDescendantFreq))
```

No full recompute on insert. Each insert touches exactly `|word|` nodes.

---

## Suggest algorithm — best-first traversal with pruning

```
suggest(prefix, k):
    node = walkPrefix(prefix)
    if node == null: return []

    results = []          // (word, freq) pairs, sorted desc
    floor = 0             // freq of the k-th result found so far

    pq = MaxPriorityQueue { it.maxDescendantFreq }
    pq.push(State(node, prefix))

    while pq.isNotEmpty() and results.size < k:
        (node, word) = pq.pop()

        // prune: nothing in this subtree beats the floor
        if node.maxDescendantFreq <= floor: break

        if node.isTerminal:
            results.add(word, node.frequency)
            floor = if results.size == k then results.min().freq else floor

        for (char, child) in node.children sorted desc by child.maxDescendantFreq:
            if child.maxDescendantFreq > floor:
                pq.push(State(child, word + char))

    return results.sortedByDescending { it.freq }
```

The key properties:
- Children pushed in descending `maxDescendantFreq` order
- Subtree pruned immediately when `maxDescendantFreq <= floor`
- Stops as soon as k terminals collected
- Guaranteed to return the true top-k in frequency order

---

## Merge logic at suggestion time

```kotlin
val userResults  = userTrie.suggest(word, k = 5)   // (word, freq) pairs
val baseResults  = trie.suggest(word, max = 5)       // plain word list, freq = 0

// Score: user frequency takes priority; base words fill gaps
val merged = (userResults.map { it } +
              baseResults.filter { b -> userResults.none { u -> u.word == b } }
                         .map { ScoredWord(it, 0) })
    .sortedByDescending { it.freq }
    .take(5)
```

User words with any frequency > 0 always rank above base dictionary words.
If the user has committed `torrent` once (freq=1) and the base dictionary has
`torrential` (freq=0), `torrent` appears first. If `the` is in both (base freq=0,
user freq=200), the user version wins and the base duplicate is dropped.

---

## Frequency increment on commit

Every time a word is committed (space, punctuation, suggestion tap), increment:

```kotlin
fun onWordCommitted(word: String) {
    if (word.isBlank()) return
    userTrie.insert(word.lowercase().trim())
    pendingFlush = true
}
```

Called from `flushComposing()` and `handleSuggestionTap()` in `CodeKeyboardIME`.

---

## Background flush

Flushing to disk rewrites the entire `user.trie` file. This is O(n) in the
number of user words. For 1000 words it will be fast but must not block the
main thread.

```kotlin
private val flushScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
private var pendingFlush = false

fun scheduleFlush() {
    if (!pendingFlush) return
    pendingFlush = false
    flushScope.launch {
        TrieWriter.write(userTrie, filesDir.resolve("user.trie"))
    }
}
```

`scheduleFlush()` called from `onFinishInput()` — fires after the user leaves
the text field. Never on the keystroke path.

Flush is atomic: write to `user.trie.tmp`, then `Files.move(tmp, target,
ATOMIC_MOVE)`. Crash during write leaves the old file intact.

---

## Startup load

```kotlin
override fun onCreate() {
    super.onCreate()
    KeyboardSettings.init(this)
    SnippetStore.init()
    trie = Trie.load(this)          // en.trie, unchanged
    userTrie = UserTrie.load(this)  // user.trie, or empty if not found
}
```

`UserTrie.load()` reads the TRIE3 file into the in-memory mutable structure.
On first install the file does not exist — `userTrie` starts empty with no
suggestions until words are committed.

---

## Score decay (future, not v1)

To prevent old words from dominating forever: on each `load()`, multiply all
frequencies by a decay factor (e.g. 0.98). Words committed years ago gradually
lose priority. Words committed recently stay high. Implemented by reading all
terminal nodes during load and scaling their frequency before inserting into
the in-memory trie.

Not in scope for this phase — noted to avoid surprises.

---

## Files that change

| File | Change |
|---|---|
| New: `UserTrieNode.kt` | Mutable node: children HashMap, frequency, maxDescendantFreq |
| New: `UserTrie.kt` | insert(), suggest() with pruning, load(Context), scheduleFlush() |
| New: `TrieWriter.kt` | Serializes in-memory UserTrie to TRIE3 binary file |
| New: `TrieReader3.kt` | Reads TRIE3 binary into UserTrie (reuses TRIE2 offset math, 16-byte nodes) |
| `CodeKeyboardIME.kt` | Add userTrie field; call onWordCommitted() from flushComposing() and handleSuggestionTap(); call scheduleFlush() from onFinishInput(); merge results in composing update |
| `Trie.kt` | No change — en.trie stays TRIE2, read-only |

No new bridge. No new gradle dependencies (coroutines already present via RN).

---

## Test plan

### Correctness tests (`UserTrieTest.kt`) — pure JVM

| Test | Assertion |
|---|---|
| insert + suggest exact match | inserted word appears in results |
| insert unknown word | `torrent` not in en.trie, appears after insert |
| frequency order | word with freq=10 ranks above freq=1 |
| suggest deduplicates vs base | word in both tries appears once |
| maxDescendantFreq updated on insert | ancestor nodes reflect new max |
| insert same word twice | frequency accumulates, not reset |
| empty trie suggest | returns empty list, no crash |
| suggest prefix not in trie | returns empty list |
| pruning correctness | A* result == DFS exhaustive result for all prefixes (property test) |

### Serialization tests (`TrieWriterTest.kt`) — pure JVM

| Test | Assertion |
|---|---|
| write then read round-trip | all words and frequencies preserved |
| atomic write (tmp → rename) | old file intact if write fails mid-way |
| empty trie serializes | zero-node file, loads back as empty trie |
| large trie (5000 words) | file size reasonable, load correct |

### Benchmark suite (`UserTrieBenchmark.kt`)

Measures wall time + nodes visited for each scenario. Run with
`./gradlew testDebugUnitTest --tests "*.UserTrieBenchmark"`.

| Benchmark | Trie size | What we measure |
|---|---|---|
| suggest cold (empty trie) | 0 words | baseline overhead |
| suggest, 100 user words | 100 | P50 / P99 latency, nodes visited |
| suggest, 1000 user words | 1000 | P50 / P99 latency, nodes visited |
| suggest, 5000 user words | 5000 | P50 / P99 latency, nodes visited |
| insert + suggest (hot path) | growing | combined per-keystroke latency |
| flush to disk | 1000 words | write duration (must be < 50ms) |
| load from disk | 1000 words | startup cost (must be < 20ms) |
| merge en.trie + user.trie | 5 + 5 candidates | sort overhead (must be < 1ms) |
| A* vs DFS node visit count | 1000 words, prefix='t' | ratio of nodes visited |

**Performance budget:**
- suggest (any trie size): < 5ms P99 on mid-range Android device
- flush to disk: < 50ms (background, but bounded)
- load on startup: < 20ms (blocks onCreate)
- merge: < 1ms (trivially fast on ≤ 10 items)

### Validation: A* vs DFS equivalence

For correctness validation, implement a reference DFS (exhaustive, collects all
terminals, sorts by frequency) alongside the best-first traversal. For a set of
randomly generated tries with random frequencies, assert:

```
bestFirst.suggest(prefix, k) == dfs.suggest(prefix, k).take(k)
```

for all prefixes and k values. This proves the pruning never incorrectly drops
a result that should have been returned.

---

## Definition of done

- [ ] `torrent` typed and committed → appears in suggestions next time `tor` typed
- [ ] High-frequency user word ranks above base dictionary word with same prefix
- [ ] Flush is background, never blocks keystrokes
- [ ] Flush is atomic — crash during write does not corrupt existing user.trie
- [ ] Load on startup < 20ms for 1000 words
- [ ] A* suggest result == DFS ground truth for all test cases
- [ ] All benchmark budgets met
- [ ] All existing tests pass (no regressions)
