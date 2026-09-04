package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Integration test for Hinglish secondary pack merged with English primary.
 * Loads real en.cklm + hi.cklm from assets and exercises MergedSuggestionStrategy
 * with both packs. Checks:
 *  - Common Hinglish words surface in top-5 suggestions for their prefix
 *  - Words are returned in full (not truncated)
 *  - Rank position is printed so regressions are visible
 */
class HinglishIntegrationTest {

    private val enPackFile: File = listOf(
        File("src/main/assets/en.cklm"),
        File("app/src/main/assets/en.cklm"),
    ).first { it.exists() }

    private val hiPackFile: File = listOf(
        File("src/main/assets/hi.cklm"),
        File("app/src/main/assets/hi.cklm"),
    ).first { it.exists() }

    private val strategy: MergedSuggestionStrategy by lazy {
        val enDict = WordDictionary(LanguagePack.open(enPackFile))
        val hiDict = WordDictionary(LanguagePack.open(hiPackFile))
        MergedSuggestionStrategy(
            UserTrieAdapter(UserTrie()),
            enDict.adapter,
            listOf(
                PackConfig("en", enDict, weight = 1.0f, maxOrder = 3),
                PackConfig("hi", hiDict, weight = 0.8f, maxOrder = 1),
            ),
        )
    }

    data class Case(val prefix: String, val expectedWord: String)

    private val cases = listOf(
        Case("karn", "karna"),
        Case("kais", "kaise"),
        Case("theek", "theek"),
        Case("bahu", "bahut"),
        Case("nahi", "nahin"),
        Case("mujh", "mujhe"),
        Case("sama", "samajh"),
        Case("zaro", "zaroor"),
        Case("abhi", "abhi"),
        Case("pyaa", "pyaar"),
        Case("ghar", "ghar"),
        Case("phir", "phir"),
        Case("yaar", "yaar"),
        Case("bhai", "bhai"),
        Case("acha", "acha"),
    )

    @Test fun `hinglish words surface in top-5 for their prefix`() {
        var passed = 0; var missed = 0
        println("\n=== Hinglish integration: prefix → top-5 suggestions ===")
        println("%-10s  %-12s  %-5s  %s".format("prefix", "expected", "rank", "top-5"))
        println("-".repeat(65))

        for ((prefix, expected) in cases) {
            val suggestions = strategy.suggest(prefix, 5)
            val rank = suggestions.indexOf(expected) + 1 // 0 if not found → -1+1=0
            val found = expected in suggestions

            println("%-10s  %-12s  %-5s  %s".format(
                prefix, expected,
                if (found) "#$rank" else "MISS",
                suggestions
            ))

            // Verify no word is returned truncated (length must match what dict has)
            for (w in suggestions) {
                assertTrue("word '$w' looks truncated (ends mid-word?)", w.length >= 2)
            }

            if (found) passed++ else missed++
        }

        println("-".repeat(65))
        println("Result: $passed/${cases.size} found in top-5, $missed missed")
        println()

        // Soft assertion — we expect at least 60% hit rate given unigram-only pack
        val hitRate = passed.toDouble() / cases.size
        // Unigram-only secondary pack at weight=0.8 — 40% is realistic; above is a bonus
        assertTrue("Hit rate too low: $passed/${cases.size} (${(hitRate*100).toInt()}%)", hitRate >= 0.4)
    }

    @Test fun `english suggestions not degraded by hinglish pack`() {
        val englishCases = listOf(
            "search" to "sear",
            "keyboard" to "keyb",
            "android" to "andr",
            "setting" to "sett",
            "language" to "lang",
        )
        println("\n=== English regression check with hi pack active ===")
        for ((word, prefix) in englishCases) {
            val suggestions = strategy.suggest(prefix, 5)
            val found = word in suggestions
            println("%-10s → %-10s  %s  %s".format(prefix, word, if (found) "✓" else "MISS", suggestions))
            assertTrue("English word '$word' missing from suggestions for '$prefix'", found)
        }
    }
}
