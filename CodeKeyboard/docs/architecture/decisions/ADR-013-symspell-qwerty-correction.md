# ADR-013: SymSpell + QWERTY Proximity Correction

## Context

BevaTrieSearch (ADR-005) implements edit-vector DFS over the pack trie. It finds candidates
within edit distance k, but treats all substitutions as uniform cost 1. This has two problems:

1. **Mobile mistype model is wrong.** On QWERTY, most errors are not spelling mistakes —
   they are fat-finger taps or finger-slide smears that hit physically adjacent keys.
   "search" → "srwach" is not a transposition; it is a multi-key slide producing extra
   characters and wrong characters simultaneously. Uniform edit distance assigns the same
   cost to "serach" (adjacent swap) and "zxqpth" (random noise), which produces wrong rankings.

2. **Multi-character slide errors exceed edit distance 2.** BevaTrieSearch at threshold 2
   misses slide errors that delete and substitute 3+ characters. SymSpell's delete-only
   pre-index catches these at O(1) lookup per delete candidate, because any combination of
   insert+substitute in the query is equivalent to deletes from the dictionary side.

The fix is two-part:
- **SymSpell delete-index** for candidate reachability (replaces BevaTrieSearch for the
  correction path; BevaTrieSearch stays for prefix/fuzzy completion).
- **QWERTY adjacency cost matrix** for candidate ranking (substitution of a direct
  physical neighbour costs 0.5 instead of 1.0, making proximity-plausible corrections
  rank above random edits).

The adjacency map must be keyed on the active KeyMap (QWERTY, Colemak, Dvorak) because
the physical key positions differ across layouts.

---

## Goals

- Catch "srwach" → "search" style multi-key slide errors (currently missed at threshold 2).
- Rank QWERTY-adjacent substitutions above non-adjacent substitutions of equal edit distance.
- O(1) candidate lookup at query time (SymSpell guarantee).
- Adjacency map is a KeyMap dependency, not hardcoded.
- No change to BevaTrieSearch (prefix completion path unchanged).
- All behaviour documented by failing tests before any implementation.

---

## Architecture

### SymSpell delete-index

Build phase (once per language pack load, or pre-built into .cklm):

```
for each word w in vocabulary:
    for each delete-variant d of w at distance 1..MAX_DIST:
        index[d].add(w)
```

Query phase (per typed word):

```
candidates = {}
for each delete-variant d of input at distance 0..MAX_DIST:
    candidates += index[d]
deduplicate, score each candidate, return top-k
```

`MAX_DIST = 2` covers the vast majority of real errors without index explosion.
At distance 2, a 7-letter word has ~250 delete variants — manageable.

### QWERTY adjacency cost matrix

```kotlin
interface KeyAdjacency {
    /** Cost of substituting `typed` where `intended` was the target key. 0.5 if adjacent, 1.0 otherwise. */
    fun substitutionCost(typed: Char, intended: Char): Float
}

class QwertyAdjacency : KeyAdjacency { ... }
class ColemakAdjacency : KeyAdjacency { ... }
class DvorakAdjacency : KeyAdjacency { ... }
object NoAdjacency : KeyAdjacency {
    override fun substitutionCost(typed: Char, intended: Char) = 1.0f
}
```

Adjacency map for QWERTY (physical key neighbours):

```
q: [a, w, s]         r: [e, d, f, t, 4, 5]   u: [y, h, j, i, 7, 8]
w: [q, a, s, e, 2]   t: [r, f, g, y, 5, 6]   i: [u, j, k, o, 8, 9]
e: [w, s, d, r, 3]   y: [t, g, h, u, 6, 7]   o: [i, k, l, p, 9, 0]
                      a: [q, w, s, z, caps]    h: [y, g, j, b, n, u]
                      s: [a, q, w, e, d, x, z] j: [h, y, u, i, k, n, m]
                      d: [s, e, r, f, c, x]    k: [j, u, i, o, l, m]
                      f: [d, r, t, g, v, c]    l: [k, o, p, semicolon]
                      g: [f, t, y, h, b, v]
                      z: [a, s, x]             b: [v, g, h, n, space]
                      x: [z, a, s, d, c]       n: [b, h, j, m, space]
                      c: [x, d, f, v]          m: [n, j, k, comma]
                      v: [c, f, g, b]
```

### Scoring

```
score(candidate) = weighted_edit_distance(input, candidate, adjacency) * frequency_penalty
weighted_edit_distance = sum of per-operation costs:
    delete:      1.0
    insert:      1.0
    substitute:  adjacency.substitutionCost(typed, intended)
frequency_penalty = 1.0 / log(1 + frequency)   // lower is better
```

Top-k by score (ascending).

### Integration point

`WordDictionary` currently calls `BevaTrieSearch.search()` for the correction path.
A new `SymSpellCorrector` class is injected alongside `BevaTrieSearch` — both run,
results are merged and de-duplicated, then scored by `ProximityScorer`.

```
WordDictionary
  ├── BevaTrieSearch      (prefix + fuzzy completion — unchanged)
  └── SymSpellCorrector   (correction — new)
        └── ProximityScorer(KeyAdjacency)
```

---

## Files

| File | Status | Role |
|---|---|---|
| `KeyAdjacency.kt` | New | Interface + QWERTY/Colemak/Dvorak implementations |
| `SymSpellIndex.kt` | New | Delete-variant index builder and lookup |
| `SymSpellCorrector.kt` | New | Query path: generate deletes → lookup → score → top-k |
| `ProximityScorer.kt` | New | Weighted edit distance using KeyAdjacency |
| `SymSpellTest.kt` | New | TDD: all behaviour specified here before implementation |
| `ProximityScorerTest.kt` | New | TDD: adjacency cost and ranking tests |
| `WordDictionary.kt` | Modify | Wire SymSpellCorrector alongside BevaTrieSearch (3 lines) |
| `BevaTrieSearch.kt` | No change | Prefix/fuzzy path untouched |
| `FuzzyTrieSearch.kt` | No change | Already dead code, stays as-is |

---

## TDD Specification

Tests must exist and fail before any implementation file is written.

### SymSpellTest.kt — candidate reachability

```kotlin
// T1: exact match
symspell.correct("search") contains "search" at distance 0

// T2: single delete — missing char
symspell.correct("seach") contains "search" at distance 1

// T3: single insert — extra char
symspell.correct("seaarch") contains "search" at distance 1

// T4: multi-key slide — "srwach" (delete r, delete w → "sach" distance 2)
symspell.correct("srwach") contains "search" at distance 2

// T5: "swsrch" → "search"
symspell.correct("swsrch") contains "search" at distance 2

// T6: "detdctive" → "detective" (e→d adjacent sub, caught at distance 1 substitute)
symspell.correct("detdctive") contains "detective"

// T7: unknown word not in vocab returns empty or unchanged
symspell.correct("xqzpwv") is empty or unchanged

// T8: short words (len ≤ 3) — no correction applied (threshold 0)
symspell.correct("th") does not mutate "th"
```

### ProximityScorerTest.kt — ranking

```kotlin
// P1: adjacent substitution ranks above non-adjacent at same edit distance
// "detdctive" (d adjacent to e) vs "detzctive" (z not adjacent to e)
// both are edit distance 1 from "detective"
// "detdctive" must score lower (better) than "detzctive"
scorer.score("detdctive", "detective") < scorer.score("detzctive", "detective")

// P2: frequency breaks tie — higher frequency word ranks first
// both candidates at equal weighted distance, one has freq 1000, other freq 10
// higher frequency wins

// P3: substitutionCost for adjacent pair is 0.5
adjacency.substitutionCost('d', 'e') == 0.5f   // d and e are neighbours on QWERTY

// P4: substitutionCost for non-adjacent pair is 1.0
adjacency.substitutionCost('z', 'e') == 1.0f
```

---

## Consequences

**Easier:** slide errors ("srwach", "swsrch") are now caught. Proximity-plausible
candidates rank above noise. Correction is O(1) lookup at query time.

**Deferred:** index build time — building the delete-variant index over the full vocab
at pack load adds ~50ms (estimated, must be measured). Pre-building into .cklm is an
option but deferred.

**Trade-offs:** SymSpell index memory — at MAX_DIST=2 over ~200K vocab words, the delete
variant map is ~10-20MB. Acceptable for a keyboard process but must be measured on device.

---

## Workflow

### DAG

```
A (KeyAdjacency.kt)         B (Tests — all failing)
        |                           |
   +----|----+                      |
   |         |                      |
C (SymSpellIndex)    D (ProximityScorer)      |
        \           /               |
         \         /                |
          E (SymSpellCorrector)     |
                |                  |
                +------------------+
                |
          F (Make tests pass)
                |
          G (WordDictionary wire-in)
                |
          H (Measure: time + memory on device)
```

### Tasks

---

### Task A — `KeyAdjacency.kt` (new file)

**Location:** `android/app/src/main/java/com/codekeyboard/KeyAdjacency.kt`

**Checklist:**
- [ ] Define `KeyAdjacency` interface
- [ ] Implement `QwertyAdjacency` with full neighbour map
- [ ] Implement `NoAdjacency` singleton (all costs = 1.0)
- [ ] Colemak + Dvorak stubs (delegate to NoAdjacency for now, TODO marked)

**Pseudocode:**
```kotlin
interface KeyAdjacency {
    fun substitutionCost(typed: Char, intended: Char): Float
}

object NoAdjacency : KeyAdjacency {
    override fun substitutionCost(typed: Char, intended: Char) = 1.0f
}

class QwertyAdjacency : KeyAdjacency {
    // physical key neighbours on QWERTY (row x col layout)
    private val neighbours: Map<Char, Set<Char>> = mapOf(
        'q' to setOf('w', 'a', 's'),
        'w' to setOf('q', 'e', 'a', 's', 'd'),
        'e' to setOf('w', 'r', 's', 'd', 'f'),
        'r' to setOf('e', 't', 'd', 'f', 'g'),
        't' to setOf('r', 'y', 'f', 'g', 'h'),
        'y' to setOf('t', 'u', 'g', 'h', 'j'),
        'u' to setOf('y', 'i', 'h', 'j', 'k'),
        'i' to setOf('u', 'o', 'j', 'k', 'l'),
        'o' to setOf('i', 'p', 'k', 'l'),
        'p' to setOf('o', 'l'),
        'a' to setOf('q', 'w', 's', 'z'),
        's' to setOf('a', 'w', 'e', 'd', 'x', 'z'),
        'd' to setOf('s', 'e', 'r', 'f', 'c', 'x'),
        'f' to setOf('d', 'r', 't', 'g', 'v', 'c'),
        'g' to setOf('f', 't', 'y', 'h', 'b', 'v'),
        'h' to setOf('g', 'y', 'u', 'j', 'n', 'b'),
        'j' to setOf('h', 'u', 'i', 'k', 'm', 'n'),
        'k' to setOf('j', 'i', 'o', 'l', 'm'),
        'l' to setOf('k', 'o', 'p'),
        'z' to setOf('a', 's', 'x'),
        'x' to setOf('z', 's', 'd', 'c'),
        'c' to setOf('x', 'd', 'f', 'v'),
        'v' to setOf('c', 'f', 'g', 'b'),
        'b' to setOf('v', 'g', 'h', 'n'),
        'n' to setOf('b', 'h', 'j', 'm'),
        'm' to setOf('n', 'j', 'k'),
    )

    override fun substitutionCost(typed: Char, intended: Char): Float {
        if (typed == intended) return 0f
        return if (neighbours[intended]?.contains(typed) == true) 0.5f else 1.0f
    }
}

// TODO: ColemakAdjacency, DvorakAdjacency — delegate to NoAdjacency until key maps verified
class ColemakAdjacency : KeyAdjacency by NoAdjacency
class DvorakAdjacency  : KeyAdjacency by NoAdjacency
```

---

### Task B — `SymSpellTest.kt` + `ProximityScorerTest.kt` (new files, all tests must FAIL)

**Location:** `android/app/src/test/java/com/codekeyboard/`

**Checklist:**
- [ ] `SymSpellTest.kt` — T1 through T8 written, all red
- [ ] `ProximityScorerTest.kt` — P1 through P4 written, all red
- [ ] Confirm `./gradlew testDebugUnitTest` compiles but fails

**Pseudocode — SymSpellTest.kt:**
```kotlin
class SymSpellTest {
    private val vocab = setOf(
        "search", "detective", "the", "hello", "world", "test", "keyboard"
    )
    private lateinit var corrector: SymSpellCorrector

    @Before fun setUp() {
        val index = SymSpellIndex.build(vocab, maxDist = 2)
        corrector = SymSpellCorrector(index, QwertyAdjacency(), maxDist = 2)
    }

    @Test fun T1_exactMatch() {
        val results = corrector.correct("search")
        assertTrue(results.any { it.word == "search" && it.editDistance == 0 })
    }

    @Test fun T2_singleDelete_missingChar() {
        // "seach" = "search" minus 'r'
        val results = corrector.correct("seach")
        assertTrue(results.any { it.word == "search" })
    }

    @Test fun T3_singleInsert_extraChar() {
        // "seaarch" = "search" with extra 'a'
        val results = corrector.correct("seaarch")
        assertTrue(results.any { it.word == "search" })
    }

    @Test fun T4_multiKeySlide_srwach() {
        // finger slide: 'r' and 'w' inserted adjacent to 's'
        val results = corrector.correct("srwach")
        assertTrue(results.any { it.word == "search" })
    }

    @Test fun T5_multiKeySlide_swsrch() {
        val results = corrector.correct("swsrch")
        assertTrue(results.any { it.word == "search" })
    }

    @Test fun T6_adjacentSubstitution_detdctive() {
        // 'e' → 'd' (adjacent on QWERTY)
        val results = corrector.correct("detdctive")
        assertTrue(results.any { it.word == "detective" })
    }

    @Test fun T7_unknownWord_returnsEmpty() {
        val results = corrector.correct("xqzpwv")
        assertTrue(results.isEmpty())
    }

    @Test fun T8_shortWord_noCorrection() {
        // words len <= 3: threshold = 0, no fuzzy
        val results = corrector.correct("th")
        // should either return exact match or empty — must NOT return unrelated words
        assertTrue(results.all { it.word == "th" || it.editDistance == 0 })
    }
}
```

**Pseudocode — ProximityScorerTest.kt:**
```kotlin
class ProximityScorerTest {
    private val adjacency = QwertyAdjacency()
    private val scorer = ProximityScorer(adjacency)

    @Test fun P1_adjacentSubstitutionRanksAboveNonAdjacent() {
        // 'd' is adjacent to 'e'; 'z' is not
        val scoreAdjacent    = scorer.score("detdctive", "detective")  // d↔e adjacent
        val scoreNonAdjacent = scorer.score("detzctive", "detective")  // z↔e not adjacent
        assertTrue(scoreAdjacent < scoreNonAdjacent)
    }

    @Test fun P2_higherFrequencyWinsTie() {
        val lowFreq  = FuzzyResult("search", 1, frequency = 10)
        val highFreq = FuzzyResult("search", 1, frequency = 1000)
        val ranked = scorer.rank(listOf(lowFreq, highFreq))
        assertEquals(highFreq, ranked.first())
    }

    @Test fun P3_adjacentCostIsHalf() {
        // 'd' and 'e' are QWERTY neighbours
        assertEquals(0.5f, adjacency.substitutionCost('d', 'e'))
    }

    @Test fun P4_nonAdjacentCostIsFull() {
        // 'z' and 'e' are not neighbours
        assertEquals(1.0f, adjacency.substitutionCost('z', 'e'))
    }
}
```

---

### Task C — `SymSpellIndex.kt` (new file)

**Location:** `android/app/src/main/java/com/codekeyboard/SymSpellIndex.kt`

**Checklist:**
- [ ] `generateDeletes(word, maxDist)` — all substrings reachable by deleting 1..maxDist chars
- [ ] `build(vocab, maxDist)` — iterate vocab, index each word under all its delete variants
- [ ] `lookup(deleteVariant)` — return set of candidate words for this variant
- [ ] Unit: "search" is reachable from "seach" (dist 1) and "sach" (dist 2)

**Pseudocode:**
```kotlin
class SymSpellIndex private constructor(
    private val index: Map<String, Set<String>>  // delete_variant -> original words
) {
    fun lookup(variant: String): Set<String> = index[variant] ?: emptySet()

    companion object {
        fun build(vocab: Set<String>, maxDist: Int = 2): SymSpellIndex {
            val map = HashMap<String, MutableSet<String>>()

            for (word in vocab) {
                // index the word itself (exact match at dist 0)
                map.getOrPut(word) { mutableSetOf() }.add(word)

                // generate all delete variants up to maxDist
                for (variant in generateDeletes(word, maxDist)) {
                    map.getOrPut(variant) { mutableSetOf() }.add(word)
                }
            }

            return SymSpellIndex(map)
        }

        // returns all strings reachable by deleting 1..maxDist chars from word
        fun generateDeletes(word: String, maxDist: Int): Set<String> {
            val result = mutableSetOf<String>()
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(word to 0)

            while (queue.isNotEmpty()) {
                val (current, dist) = queue.removeFirst()
                if (dist >= maxDist) continue
                for (i in current.indices) {
                    val deleted = current.removeRange(i, i + 1)
                    if (result.add(deleted)) {
                        queue.add(deleted to dist + 1)
                    }
                }
            }
            return result
        }
    }
}
```

---

### Task D — `ProximityScorer.kt` (new file)

**Location:** `android/app/src/main/java/com/codekeyboard/ProximityScorer.kt`

**Checklist:**
- [ ] `score(input, candidate)` — weighted edit distance with adjacency costs
- [ ] `rank(candidates)` — sort by score ascending, break ties by frequency descending
- [ ] Uses standard DP table but substitution cost = `adjacency.substitutionCost()`

**Pseudocode:**
```kotlin
class ProximityScorer(private val adjacency: KeyAdjacency) {

    // weighted edit distance: delete/insert cost 1.0, substitute cost from adjacency
    fun score(input: String, candidate: String): Float {
        val m = input.length
        val n = candidate.length
        val dp = Array(m + 1) { FloatArray(n + 1) }

        for (i in 0..m) dp[i][0] = i.toFloat()
        for (j in 0..n) dp[0][j] = j.toFloat()

        for (i in 1..m) {
            for (j in 1..n) {
                val subCost = adjacency.substitutionCost(input[i-1], candidate[j-1])
                dp[i][j] = minOf(
                    dp[i-1][j] + 1f,           // delete from input
                    dp[i][j-1] + 1f,           // insert into input
                    dp[i-1][j-1] + subCost     // substitute
                )
            }
        }
        return dp[m][n]
    }

    fun rank(candidates: List<FuzzyResult>): List<FuzzyResult> {
        return candidates.sortedWith(
            compareBy<FuzzyResult> { score(it.word, it.word) }  // placeholder — caller provides input
                .thenByDescending { it.frequency }
        )
    }

    // proper rank with input context
    fun rank(input: String, candidates: List<FuzzyResult>): List<FuzzyResult> {
        return candidates.sortedWith(
            compareBy<FuzzyResult> { score(input, it.word) }
                .thenByDescending { it.frequency }
        )
    }
}
```

---

### Task E — `SymSpellCorrector.kt` (new file)

**Location:** `android/app/src/main/java/com/codekeyboard/SymSpellCorrector.kt`

**Checklist:**
- [ ] `correct(input)` — generate all delete variants of input, lookup each, collect candidates
- [ ] Deduplicate candidates (same word may appear from multiple delete variants)
- [ ] Score and rank via `ProximityScorer`
- [ ] Apply `FuzzyThreshold.forLength()` — no correction for words len ≤ 3
- [ ] Return `List<FuzzyResult>` (same type as BevaTrieSearch)

**Pseudocode:**
```kotlin
class SymSpellCorrector(
    private val index: SymSpellIndex,
    private val adjacency: KeyAdjacency,
    private val maxDist: Int = 2,
) {
    private val scorer = ProximityScorer(adjacency)

    fun correct(input: String): List<FuzzyResult> {
        val threshold = FuzzyThreshold.forLength(input.length)
        if (threshold == 0) return emptyList()

        val inputLower = input.lowercase()
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<FuzzyResult>()

        // generate delete variants of the input (including input itself at dist 0)
        val variants = SymSpellIndex.generateDeletes(inputLower, maxDist) + inputLower

        for (variant in variants) {
            for (word in index.lookup(variant)) {
                if (seen.add(word)) {
                    val dist = scorer.score(inputLower, word)
                    if (dist <= maxDist) {
                        // frequency lookup from pack vocab — placeholder, wire in Task G
                        candidates.add(FuzzyResult(word, dist.toInt(), frequency = 0))
                    }
                }
            }
        }

        return scorer.rank(inputLower, candidates).take(10)
    }
}
```

---

### Task F — Make tests pass

**Checklist:**
- [ ] Run `./gradlew testDebugUnitTest --tests "com.codekeyboard.SymSpellTest"` — all red
- [ ] Run `./gradlew testDebugUnitTest --tests "com.codekeyboard.ProximityScorerTest"` — all red
- [ ] Implement A, C, D, E
- [ ] Re-run — all green
- [ ] T4 ("srwach" → "search") is the hardest — verify it passes before moving on

---

### Task G — Wire into `WordDictionary.kt` (modify existing file)

**Location:** `android/app/src/main/java/com/codekeyboard/WordDictionary.kt`

**Checklist:**
- [ ] Add `SymSpellCorrector` as a constructor parameter (nullable, default null — feature flag)
- [ ] In the correction call path, run `symSpellCorrector?.correct(word)` alongside BevaTrieSearch
- [ ] Merge results: deduplicate by word, take the lower edit distance if duplicate
- [ ] Re-rank merged list by `ProximityScorer`
- [ ] Verify existing tests still pass

**Pseudocode (3-line change shown in context):**
```kotlin
class WordDictionary(
    private val adapter: TrieAdapter<*>,
    private val symSpellCorrector: SymSpellCorrector? = null,   // ADD
) {
    fun correct(word: String, maxResults: Int): List<FuzzyResult> {
        val threshold = FuzzyThreshold.forLength(word.length)
        val bevaResults = BevaTrieSearch.search(adapter, word, threshold, maxResults)

        // ADD: merge with SymSpell results if available
        val symResults = symSpellCorrector?.correct(word) ?: emptyList()
        return merge(bevaResults, symResults).take(maxResults)    // ADD
    }

    // ADD
    private fun merge(a: List<FuzzyResult>, b: List<FuzzyResult>): List<FuzzyResult> {
        val byWord = LinkedHashMap<String, FuzzyResult>()
        for (r in a + b) {
            val existing = byWord[r.word]
            if (existing == null || r.editDistance < existing.editDistance) {
                byWord[r.word] = r
            }
        }
        return byWord.values.sortedWith(compareBy({ it.editDistance }, { -it.frequency }))
    }
}
```

---

### Task H — Measure on device

**Checklist:**
- [ ] Log `SymSpellIndex.build()` wall time in `WordDictionary` init
- [ ] Log index map size (entry count) after build
- [ ] Install on device, type normally, check logcat for build time
- [ ] **Gate:** if build time > 100ms OR map size > 500K entries → pre-build offline, store in .cklm
- [ ] **Gate:** if correction latency per keypress > 30ms → profile `generateDeletes` + `lookup`

### Dependency edges

- A: none
- B: none (tests compile against interfaces, stubs ok)
- C: A
- D: A
- E: C, D
- F: B, E
- G: E
- H: G

### Wave table

| Wave | Tasks | Can parallelise? |
|---|---|---|
| 0 | A, B | yes |
| 1 | C, D | yes (both need A only) |
| 2 | E | no (needs C + D) |
| 3 | F | no (needs B + E) |
| 4 | G | no (needs E + working WordDictionary context) |
| 5 | H | no (needs G on real device) |

### Critical path

A → C → E → F → G → H

### Blocking notes

- **B before C/D/E**: tests must exist and fail before any implementation. Do not skip.
- **G** touches `WordDictionary.kt` which is on the live correction path. Wrap in a
  feature flag or guard so the old BevaTrieSearch path remains reachable during testing.
- **H** (memory measurement) is a go/no-go gate. If the index is too large, pre-build
  it offline and store in .cklm (ADR-010 extension), but do not implement that speculatively.
