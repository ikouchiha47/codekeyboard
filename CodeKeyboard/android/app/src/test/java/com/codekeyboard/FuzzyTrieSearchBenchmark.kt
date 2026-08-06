package com.codekeyboard

import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Test

class FuzzyTrieSearchBenchmark {

    companion object {
        private val results = mutableListOf<String>()

        @JvmStatic @AfterClass fun printResults() {
            println("\n===== FuzzyTrieSearchBenchmark =====")
            results.forEach { println(it) }
            println("=====================================\n")
        }

        private fun buildTrie(wordCount: Int, seed: Long = 42): UserTrie {
            val trie = UserTrie()
            val alphabet = ('a'..'z').toList()
            val rng = java.util.Random(seed)
            repeat(wordCount) {
                val len = 4 + rng.nextInt(8)
                val word = (0 until len).map { alphabet[rng.nextInt(26)] }.joinToString("")
                val freq = 1 + rng.nextInt(100)
                repeat(freq) { trie.insert(word) }
            }
            return trie
        }

        private fun measure(label: String, warmup: Int = 3, runs: Int = 50, block: () -> Unit): Long {
            repeat(warmup) { block() }
            val times = LongArray(runs)
            repeat(runs) { i ->
                val t = System.nanoTime()
                block()
                times[i] = System.nanoTime() - t
            }
            times.sort()
            val p50 = times[runs / 2] / 1_000
            val p99 = times[(runs * 0.99).toInt()] / 1_000
            results.add("%-60s  p50=%5dµs  p99=%5dµs".format(label, p50, p99))
            return p50
        }
    }

    // ── Fuzzy search at varying trie sizes ────────────────────────────────────

    @Test fun `fuzzy k=1 — 100 words`() {
        val trie = buildTrie(100)
        val adapter = UserTrieAdapter(trie)
        measure("fuzzy k=1  [ 100 words] query='raiming'") {
            FuzzyTrieSearch.search(adapter, "raiming", 1, 5)
        }
    }

    @Test fun `fuzzy k=1 — 1000 words`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)
        measure("fuzzy k=1  [  1k words] query='raiming'") {
            FuzzyTrieSearch.search(adapter, "raiming", 1, 5)
        }
    }

    @Test fun `fuzzy k=2 — 1000 words`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)
        measure("fuzzy k=2  [  1k words] query='raiming'") {
            FuzzyTrieSearch.search(adapter, "raiming", 2, 5)
        }
    }

    @Test fun `fuzzy k=2 — 5000 words`() {
        val trie = buildTrie(5000)
        val adapter = UserTrieAdapter(trie)
        measure("fuzzy k=2  [  5k words] query='raiming'") {
            FuzzyTrieSearch.search(adapter, "raiming", 2, 5)
        }
    }

    @Test fun `fuzzy k=2 — short prefix (more noise)`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)
        measure("fuzzy k=2  [  1k words] query='helo' (len 4→k=1)") {
            FuzzyTrieSearch.search(adapter, "helo", FuzzyThreshold.forLength(4), 5)
        }
    }

    // ── Exact vs fuzzy comparison ─────────────────────────────────────────────

    @Test fun `exact suggest vs fuzzy fallback — known prefix`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)
        // Prefix that has exact results — fuzzy should never fire
        val word = trie.root.children.keys.first().toString()
        measure("exact only [  1k words] query='$word' (hits exact)") {
            val exact = trie.suggest(word, 5)
            if (exact.size < 5) FuzzyTrieSearch.search(adapter, word, FuzzyThreshold.forLength(word.length), 5 - exact.size)
        }
    }

    @Test fun `full MergedSuggestionStrategy suggest — typo path`() {
        // Simulate the full suggest() call through MergedSuggestionStrategy
        // using only UserTrie (no Android context for base trie).
        val trie = buildTrie(1000)
        val missingPrefix = "zzzzz" // won't match anything, triggers fuzzy
        val adapter = UserTrieAdapter(trie)
        measure("fuzzy k=2  [  1k words] query='zzzzz' (zero exact results)") {
            val exact = trie.suggest(missingPrefix, 5)
            if (exact.size < 5) {
                FuzzyTrieSearch.search(adapter, missingPrefix, FuzzyThreshold.forLength(missingPrefix.length), 5 - exact.size)
            }
        }
    }

    // ── Pruning effectiveness ─────────────────────────────────────────────────

    @Test fun `pruning ratio — counts nodes visited vs total`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)

        // Verify correctness while measuring
        val fuzzy = FuzzyTrieSearch.search(adapter, "raiming", 2, 5)
        val brute = collectAll(trie).filter { levenshtein("raiming", it) <= 2 }
        val fuzzyWords = fuzzy.map { it.word }.toSet()
        val bruteWords = brute.toSet()

        results.add(
            "pruning check: fuzzy=${fuzzyWords.size} brute=${bruteWords.size} " +
            "missed=${(bruteWords - fuzzyWords).take(3)} " +
            "extra=${(fuzzyWords - bruteWords).take(3)}"
        )
        // Correctness assertion — fuzzy must not return words beyond threshold
        assertTrue("fuzzy returned words beyond threshold",
            fuzzyWords.all { w -> levenshtein("raiming", w) <= 2 })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun collectAll(trie: UserTrie): List<String> {
        val out = mutableListOf<String>()
        fun dfs(node: UserTrieNode, prefix: String) {
            if (node.isTerminal) out += prefix
            node.children.forEach { (ch, child) -> dfs(child, prefix + ch) }
        }
        dfs(trie.root, "")
        return out
    }

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
