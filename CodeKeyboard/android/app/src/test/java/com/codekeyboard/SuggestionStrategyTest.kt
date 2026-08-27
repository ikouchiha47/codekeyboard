package com.codekeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Tests the full fuzzyFill pipeline to confirm sesrcg→search ranking.
 *
 * Uses a controlled UserTrie so we don't need the real pack.
 * The fake PrefixDictionary.correct() runs BEVA+QwertyAdjacency exactly as
 * WordDictionary does, so this mirrors the real device path.
 */
class SuggestionStrategyTest {

    private lateinit var baseTrie: UserTrie
    private lateinit var baseAdapter: UserTrieAdapter
    private lateinit var userTrie: UserTrie
    private lateinit var userAdapter: UserTrieAdapter

    @Before fun setUp() {
        // Base dictionary: "search" + competing dist=2 words with various frequencies
        baseTrie = UserTrie()
        listOf(
            "search" to 50,
            "serene" to 30,   // dist=2 from sesrcg
            "sewing" to 20,   // dist=2 from sesrcg
            "seraph" to 15,   // dist=2 from sesrcg
            "seared" to 40,   // dist=2 from sesrcg, higher freq
        ).forEach { (w, n) -> repeat(n) { baseTrie.insert(w) } }
        baseAdapter = UserTrieAdapter(baseTrie)

        userTrie = UserTrie()
        userAdapter = UserTrieAdapter(userTrie)
    }

    // Fake PrefixDictionary backed by the base trie, correct() uses QwertyAdjacency
    // — mirrors WordDictionary.correct() without needing a real LanguagePack.
    private val baseDict = object : PrefixDictionary {
        override fun suggest(prefix: String, max: Int): List<String> =
            baseTrie.suggest(prefix, max).map { it.word }
        override fun has(word: String): Boolean =
            baseTrie.suggest(word, 1).any { it.word == word }
        override fun correct(word: String, maxResults: Int): List<FuzzyResult> {
            val threshold = FuzzyThreshold.forLength(word.length)
            if (threshold == 0) return emptyList()
            val results = BevaTrieSearch.search(baseAdapter, word, threshold, maxResults, QwertyAdjacency())
            return ProximityScorer(QwertyAdjacency()).rank(word, results)
        }
    }

    @Test fun `sesrcg - trace each stage and confirm search is not dropped`() {
        val word = "sesrcg"
        val threshold = FuzzyThreshold.forLength(word.length)  // 2

        // Stage 1: uniform baseFuzzy (old path, no adjacency)
        val baseFuzzy = BevaTrieSearch.search(baseAdapter, word, threshold, Int.MAX_VALUE)
        println("\n=== Stage 1: baseFuzzy (uniform cost) ===")
        baseFuzzy.forEach { println("  ${it.word}  dist=${it.editDistance}  freq=${it.frequency}") }
        val baseFuzzySearch = baseFuzzy.firstOrNull { it.word == "search" }
        println("  'search' in baseFuzzy: $baseFuzzySearch")

        // Stage 2: proximity-weighted baseCorrections
        val baseCorrections = baseDict.correct(word, Int.MAX_VALUE)
        println("\n=== Stage 2: baseCorrections (proximity cost) ===")
        baseCorrections.forEach { println("  ${it.word}  dist=${it.editDistance}  freq=${it.frequency}") }
        val corrSearch = baseCorrections.firstOrNull { it.word == "search" }
        println("  'search' in baseCorrections: $corrSearch")

        // Stage 3: old dedup (distinctBy = first wins)
        val oldMerge = (baseFuzzy + baseCorrections).distinctBy { it.word }
        val oldSearchEntry = oldMerge.firstOrNull { it.word == "search" }
        println("\n=== Stage 3: OLD dedup (distinctBy, first wins) ===")
        println("  'search' entry: $oldSearchEntry")

        // Stage 4: new dedup (keep min editDistance)
        val byWord = LinkedHashMap<String, FuzzyResult>()
        for (r in baseFuzzy + baseCorrections) {
            val existing = byWord[r.word]
            if (existing == null || r.editDistance < existing.editDistance) byWord[r.word] = r
        }
        val newSearchEntry = byWord["search"]
        println("\n=== Stage 4: NEW dedup (keep min editDistance) ===")
        println("  'search' entry: $newSearchEntry")

        // The bug: old path gives search a WORSE editDistance than new path
        if (baseFuzzySearch != null && corrSearch != null) {
            assertTrue(
                "BUG CONFIRMED: baseFuzzy gave search dist=${baseFuzzySearch.editDistance}, " +
                "but baseCorrections gave dist=${corrSearch.editDistance}. " +
                "distinctBy kept the worse one.",
                baseFuzzySearch.editDistance >= corrSearch.editDistance
            )
        }

        // The fix: search must appear in final top-5 with new dedup
        // MergedSuggestionStrategy's baseAdapter is TrieAdapter<Int> (pack-backed).
        // In this test baseDict.correct() already covers the base trie via BEVA,
        // so pass userAdapter as a no-op stand-in for the pack adapter.
        val emptyBaseAdapter = object : TrieAdapter<Int> {
            override val root: Int = -1
            override fun isTerminal(node: Int) = false
            override fun frequency(node: Int) = 0
            override fun iterateChildren(node: Int, block: (Char, Int) -> Unit) {}
        }
        val strategy = MergedSuggestionStrategy(userAdapter, emptyBaseAdapter, baseDict)
        val suggestions = strategy.suggest(word, 5)
        println("\n=== Final suggestions for '$word' ===")
        suggestions.forEach { println("  $it") }
        assertTrue("search must appear in suggestions for '$word'", "search" in suggestions)
    }
}
