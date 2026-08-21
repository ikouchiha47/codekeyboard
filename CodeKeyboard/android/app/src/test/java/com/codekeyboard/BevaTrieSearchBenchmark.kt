package com.codekeyboard

import org.junit.AfterClass
import org.junit.Assert.assertTrue
import org.junit.Test

class BevaTrieSearchBenchmark {

    companion object {
        private val results = mutableListOf<String>()

        @JvmStatic @AfterClass fun printResults() {
            println("\n===== BevaTrieSearchBenchmark =====")
            results.forEach { println(it) }
            println("===================================\n")
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

    // ── BEVA at varying trie sizes (mirrors Hanov benchmarks) ────────────────

    @Test fun `beva k=1 — 100 words`() {
        val trie = buildTrie(100)
        val adapter = UserTrieAdapter(trie)
        measure("beva  k=1  [ 100 words] query='raiming'") {
            BevaTrieSearch.search(adapter, "raiming", 1, 5)
        }
    }

    @Test fun `beva k=1 — 1000 words`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)
        measure("beva  k=1  [  1k words] query='raiming'") {
            BevaTrieSearch.search(adapter, "raiming", 1, 5)
        }
    }

    @Test fun `beva k=2 — 1000 words`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)
        measure("beva  k=2  [  1k words] query='raiming'") {
            BevaTrieSearch.search(adapter, "raiming", 2, 5)
        }
    }

    @Test fun `beva k=2 — 5000 words`() {
        val trie = buildTrie(5000)
        val adapter = UserTrieAdapter(trie)
        measure("beva  k=2  [  5k words] query='raiming'") {
            BevaTrieSearch.search(adapter, "raiming", 2, 5)
        }
    }

    @Test fun `beva k=2 — short prefix (more noise)`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)
        measure("beva  k=2  [  1k words] query='helo' (len 4→k=1)") {
            BevaTrieSearch.search(adapter, "helo", FuzzyThreshold.forLength(4), 5)
        }
    }

    // ── Pruning correctness check ─────────────────────────────────────────────

    @Test fun `pruning correctness — beva vs brute`() {
        val trie = buildTrie(1000)
        val adapter = UserTrieAdapter(trie)
        val beva  = BevaTrieSearch.search(adapter, "raiming", 2, 50)
        val bevaWords = beva.map { it.word }.toSet()
        assertTrue("beva returned words beyond threshold",
            bevaWords.all { w -> levenshtein("raiming", w) <= 2 })
        results.add("pruning check: beva=${bevaWords.size} words within threshold")
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
