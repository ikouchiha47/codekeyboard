package com.codekeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BevaTrieSearchTest {

    private lateinit var trie: UserTrie
    private lateinit var adapter: UserTrieAdapter

    @Before fun setUp() {
        trie = UserTrie()
        adapter = UserTrieAdapter(trie)
    }

    private fun insert(vararg pairs: Pair<String, Int>) =
        pairs.forEach { (w, n) -> repeat(n) { trie.insert(w) } }

    private fun search(word: String, threshold: Int, max: Int = 5) =
        BevaTrieSearch.search(adapter, word, threshold, max)

    // ── Basic correction ──────────────────────────────────────────────────────

    @Test fun `single substitution found at threshold 1`() {
        insert("raining" to 5)
        val results = search("raiming", threshold = 1)
        assertTrue(results.any { it.word == "raining" && it.editDistance == 1 })
    }

    @Test fun `single deletion found at threshold 1`() {
        insert("rain" to 3)
        val results = search("ain", threshold = 1)
        assertTrue(results.any { it.word == "rain" })
    }

    @Test fun `single insertion found at threshold 1`() {
        insert("helo" to 3)
        val results = search("hello", threshold = 1)
        assertTrue(results.any { it.word == "helo" })
    }

    @Test fun `two edits found at threshold 2`() {
        insert("raining" to 5)
        val results = search("raibmng", threshold = 2)
        assertTrue(results.any { it.word == "raining" })
    }

    @Test fun `exact match returned with edit distance 0`() {
        insert("hello" to 3)
        val results = search("hello", threshold = 1)
        assertEquals(0, results.first { it.word == "hello" }.editDistance)
    }

    @Test fun `word beyond threshold not returned`() {
        insert("raining" to 5)
        val results = search("xyz", threshold = 2)
        assertTrue(results.none { it.word == "raining" })
    }

    @Test fun `threshold 0 returns empty`() {
        insert("hello" to 3)
        val results = search("helo", threshold = 0)
        assertTrue(results.isEmpty())
    }

    // ── Threshold scaling ─────────────────────────────────────────────────────

    @Test fun `FuzzyThreshold returns 0 for len 3`() {
        assertEquals(0, FuzzyThreshold.forLength(3))
    }

    @Test fun `FuzzyThreshold returns 1 for len 4`() {
        assertEquals(1, FuzzyThreshold.forLength(4))
    }

    @Test fun `FuzzyThreshold returns 2 for len 5 and above`() {
        assertEquals(2, FuzzyThreshold.forLength(5))
        assertEquals(2, FuzzyThreshold.forLength(10))
    }

    // ── Result ordering ───────────────────────────────────────────────────────

    @Test fun `closer edit distance ranks first`() {
        insert("raining" to 3, "ranning" to 3)
        val results = search("raiming", threshold = 2)
        val raining = results.indexOfFirst { it.word == "raining" }
        val ranning = results.indexOfFirst { it.word == "ranning" }
        assertTrue("raining not found", raining >= 0)
        assertTrue("ranning not found", ranning >= 0)
        assertTrue("dist-1 word must rank before dist-2 word", raining < ranning)
    }

    @Test fun `among equal edit distance higher frequency ranks first`() {
        insert("raining" to 10, "raining" to 0)
        insert("reining" to 2)
        val results = search("raining", threshold = 1)
        assertEquals("raining", results.first().word)
        assertEquals(0, results.first().editDistance)
    }

    // ── maxResults cap ────────────────────────────────────────────────────────

    @Test fun `respects maxResults cap`() {
        insert("help" to 1, "helm" to 1, "hero" to 1, "here" to 1, "herd" to 1)
        val results = search("helo", threshold = 2, max = 2)
        assertTrue(results.size <= 2)
    }

    // ── Empty / edge cases ────────────────────────────────────────────────────

    @Test fun `empty trie returns empty`() {
        assertTrue(search("hello", threshold = 2).isEmpty())
    }

    @Test fun `empty word returns empty`() {
        insert("hello" to 1)
        val results = BevaTrieSearch.search(adapter, "", threshold = 2, maxResults = 5)
        assertTrue(results.isEmpty())
    }

    @Test fun `single char word not matched as prefix of longer word`() {
        insert("hello" to 3)
        val results = search("h", threshold = 2)
        assertTrue(results.none { it.word == "hello" })
    }

    // ── Property: BEVA == brute-force scan ────────────────────────────────────

    @Test fun `BEVA DFS matches brute-force scan — simple words`() {
        val words = listOf("help" to 4, "helm" to 2, "hero" to 5, "here" to 1,
                           "herd" to 3, "her" to 7, "he" to 1)
        words.forEach { (w, n) -> repeat(n) { trie.insert(w) } }

        val queries = listOf("hel", "her", "heroe", "hlep")
        for (q in queries) {
            val threshold = FuzzyThreshold.forLength(q.length)
            if (threshold == 0) continue
            val beva  = search(q, threshold).map { it.word }.toSet()
            val brute = words.map { it.first }
                .filter { levenshtein(q, it) <= threshold }
                .toSet()
            assertEquals("BEVA != brute for '$q'", brute, beva)
        }
    }

    @Test fun `BEVA DFS matches brute-force scan — random trie`() {
        val words = listOf(
            "apple" to 4, "apply" to 8, "apt" to 3, "ape" to 6,
            "apex" to 5, "april" to 7, "app" to 2, "application" to 1,
        )
        words.forEach { (w, n) -> repeat(n) { trie.insert(w) } }

        val queries = listOf("aple", "appl", "appt", "apre")
        for (q in queries) {
            val threshold = FuzzyThreshold.forLength(q.length)
            if (threshold == 0) continue
            val beva  = search(q, threshold).map { it.word }.toSet()
            val brute = words.map { it.first }
                .filter { levenshtein(q, it) <= threshold }
                .toSet()
            assertEquals("BEVA != brute for '$q'", brute, beva)
        }
    }

    // ── Proximity (adjacent-key slide errors) ─────────────────────────────────
    // These FAIL with uniform-cost BEVA (threshold 2, dist=3).
    // They will pass once KeyAdjacency is injected into transition() with half-step costs.

    @Test fun `srwach finds search - multi-key slide dist 3 adjacent keys`() {
        insert("search" to 10)
        // srwach→search: s=s, r→e(adj), w→a(adj), a→r(adj), c=c, h=h — 3 adjacent subs
        // With half-step costs: 3×0.5 = 1.5, within threshold 2. Requires QwertyAdjacency.
        val results = BevaTrieSearch.search(adapter, "srwach", 2, 10, QwertyAdjacency())
        assertTrue("search not found for 'srwach' — proximity injection missing", results.any { it.word == "search" })
    }

    @Test fun `swsrch finds search - dist 2 already caught by uniform BEVA`() {
        insert("search" to 10)
        // swsrch→search: s=s, w→e(sub), s→a(sub), r=r, c=c, h=h — exactly 2 subs, within threshold 2.
        val results = search("swsrch", threshold = 2)
        assertTrue("search not found for 'swsrch'", results.any { it.word == "search" })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
        }
        return dp[a.length][b.length]
    }
}
