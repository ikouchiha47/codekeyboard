package com.codekeyboard

import org.junit.AfterClass
import org.junit.Test
import java.io.File
import java.util.Locale

class UserTrieBenchmark {

    companion object {
        private val results = mutableListOf<String>()

        @JvmStatic @AfterClass fun printResults() {
            println("\n===== UserTrieBenchmark =====")
            results.forEach { println(it) }
            println("=============================\n")
        }

        private fun buildTrie(wordCount: Int): UserTrie {
            val trie = UserTrie()
            val alphabet = ('a'..'z').toList()
            val rng = java.util.Random(42)
            repeat(wordCount) { i ->
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
            results.add("%-55s  p50=%5dµs  p99=%5dµs".format(label, p50, p99))
            return p50
        }
    }

    @Test fun `suggest cold — empty trie baseline`() {
        val trie = UserTrie()
        measure("suggest  [  0 words] prefix='he' k=5") { trie.suggest("he", 5) }
    }

    @Test fun `suggest — 100 user words`() {
        val trie = buildTrie(100)
        measure("suggest  [100 words] prefix='he' k=5") { trie.suggest("he", 5) }
    }

    @Test fun `suggest — 1000 user words`() {
        val trie = buildTrie(1000)
        measure("suggest  [1k  words] prefix='he' k=5") { trie.suggest("he", 5) }
    }

    @Test fun `suggest — 5000 user words`() {
        val trie = buildTrie(5000)
        measure("suggest  [5k  words] prefix='he' k=5") { trie.suggest("he", 5) }
    }

    @Test fun `suggest short prefix — 1000 user words`() {
        val trie = buildTrie(1000)
        measure("suggest  [1k  words] prefix='a'  k=5") { trie.suggest("a", 5) }
    }

    @Test fun `insert hot path — single word into 1000-word trie`() {
        val trie = buildTrie(1000)
        measure("insert   [1k  words] single word") { trie.insert("benchmark") }
    }

    @Test fun `insert + suggest combined — hot path per keystroke`() {
        val trie = buildTrie(1000)
        measure("insert+suggest [1k words] combined") {
            trie.insert("hello")
            trie.suggest("hel", 5)
        }
    }

    @Test fun `A* vs DFS — node visit ratio at 1000 words`() {
        val trie = buildTrie(1000)
        val prefixes = listOf("a", "b", "he", "tor", "xyz")
        val report = StringBuilder()
        for (prefix in prefixes) {
            val bf  = trie.suggest(prefix, 5)
            val dfs = trie.suggestDfs(prefix, 5)
            // Verify correctness while benchmarking
            assert(bf.map { it.word }.toSet() == dfs.map { it.word }.toSet()) {
                "A* != DFS for prefix '$prefix'"
            }
            report.append("  prefix='$prefix' bf=${bf.size} dfs=${dfs.size} ")
        }
        results.add("A* vs DFS correctness check: $report")
    }

    @Test fun `flush to disk — 1000 words`() {
        val trie = buildTrie(1000)
        val file = File.createTempFile("bench_user", ".trie")
        try {
            measure("flush    [1k  words] to disk", warmup = 1, runs = 10) { trie.save(file) }
        } finally {
            file.delete()
        }
    }

    @Test fun `flush to disk — 5000 words`() {
        val trie = buildTrie(5000)
        val file = File.createTempFile("bench_user5k", ".trie")
        try {
            measure("flush    [5k  words] to disk", warmup = 1, runs = 10) { trie.save(file) }
        } finally {
            file.delete()
        }
    }

    @Test fun `load from disk — 1000 words`() {
        val trie = buildTrie(1000)
        val file = File.createTempFile("bench_load", ".trie")
        try {
            trie.save(file)
            measure("load     [1k  words] from disk", warmup = 1, runs = 10) { UserTrie.load(file) }
        } finally {
            file.delete()
        }
    }

    @Test fun `load from disk — 5000 words`() {
        val trie = buildTrie(5000)
        val file = File.createTempFile("bench_load5k", ".trie")
        try {
            trie.save(file)
            measure("load     [5k  words] from disk", warmup = 1, runs = 10) { UserTrie.load(file) }
        } finally {
            file.delete()
        }
    }

    @Test fun `merge results — 5 user + 5 base candidates`() {
        val userResults = (1..5).map { ScoredWord("word$it", it * 10) }
        val baseWords   = (1..5).map { "base$it" }
        measure("merge    [5+5 candidates]") {
            val merged = (userResults +
                baseWords.filter { b -> userResults.none { u -> u.word == b } }
                         .map { ScoredWord(it, 0) })
                .sortedByDescending { it.frequency }
                .take(5)
            merged.size // prevent dead-code elimination
        }
    }
}
