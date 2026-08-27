package com.codekeyboard

import org.junit.AfterClass
import org.junit.Test

/**
 * Benchmarks SymSpell index build time and per-query lookup time.
 * Run with: ./gradlew testDebugUnitTest --tests "com.codekeyboard.SymSpellBenchmark" -i
 */
class SymSpellBenchmark {

    companion object {
        private val results = mutableListOf<String>()

        @JvmStatic @AfterClass fun printResults() {
            println("\n===== SymSpellBenchmark =====")
            results.forEach { println(it) }
            println("=============================\n")
        }

        // Synthetic vocab of realistic size (~150K words)
        private fun buildVocab(wordCount: Int): Set<String> {
            val words = mutableSetOf<String>()
            val rng = java.util.Random(42)
            val alphabet = ('a'..'z').toList()
            while (words.size < wordCount) {
                val len = 4 + rng.nextInt(8)
                words.add((0 until len).map { alphabet[rng.nextInt(26)] }.joinToString(""))
            }
            return words
        }
    }

    @Test fun bench_buildIndex_50k() = benchBuild(50_000)
    @Test fun bench_buildIndex_100k() = benchBuild(100_000)
    @Test fun bench_buildIndex_150k() = benchBuild(150_000)

    private fun benchBuild(vocabSize: Int) {
        val vocab = buildVocab(vocabSize)
        val t0 = System.currentTimeMillis()
        val index = SymSpellIndex.build(vocab, maxDist = 2)
        val ms = System.currentTimeMillis() - t0
        results += "build($vocabSize words): ${ms}ms  index_entries=${index.size}"
    }

    @Test fun bench_correct_query() {
        val vocab = buildVocab(100_000)
        val index = SymSpellIndex.build(vocab, maxDist = 2)
        val corrector = SymSpellCorrector(index, QwertyAdjacency(), maxDist = 2)
        val queries = listOf("srwach", "swsrch", "detdctive", "seach", "helo", "wrold", "tset")

        val t0 = System.currentTimeMillis()
        repeat(1000) { i -> corrector.correct(queries[i % queries.size]) }
        val ms = System.currentTimeMillis() - t0
        results += "correct(1000 queries on 100k vocab): ${ms}ms  avg=${ms / 1000.0}ms/query"
    }
}
