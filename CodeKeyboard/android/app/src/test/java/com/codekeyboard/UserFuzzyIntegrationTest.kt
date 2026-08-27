package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Loads the real user.trie from /tmp/user.trie (pulled from device via adb)
 * and runs the full fuzzyFill pipeline to trace where garbage words come from.
 *
 * Pull the trie first:
 *   adb shell "run-as com.codekeyboard cat /data/data/com.codekeyboard/files/user.trie" > /tmp/user.trie
 */
class UserFuzzyIntegrationTest {

    private val userTrieFile = File("/tmp/user.trie")
    private val packFile: File = run {
        val candidates = listOf(
            File("src/main/assets/en.cklm"),
            File("app/src/main/assets/en.cklm"),
            File("android/app/src/main/assets/en.cklm"),
        )
        candidates.firstOrNull { it.exists() }
            ?: error("en.cklm not found")
    }

    private val userTrie: UserTrie by lazy {
        check(userTrieFile.exists()) { "user.trie not found at /tmp/user.trie — pull from device first" }
        UserTrie.load(userTrieFile)
    }
    private val userAdapter: UserTrieAdapter by lazy { UserTrieAdapter(userTrie) }

    private val wordDict: WordDictionary by lazy {
        WordDictionary(LanguagePack.open(packFile))
    }
    private val baseAdapter: TrieAdapter<Int> by lazy { wordDict.adapter }

    @Test fun `trace sesrcg through full fuzzyFill pipeline`() {
        val word = "sesrcg"
        val threshold = FuzzyThreshold.forLength(word.length)  // 2

        // Stage 1: userFuzzy — BEVA on user trie, NO adjacency
        val userFuzzy = BevaTrieSearch.search(userAdapter, word, threshold, Int.MAX_VALUE)
        println("\n=== userFuzzy (${userFuzzy.size} results) ===")
        userFuzzy.sortedBy { it.editDistance }.take(20).forEach {
            println("  ${it.word}  d=${it.editDistance}  freq=${it.frequency}")
        }

        // Stage 2: baseCorrections — BEVA on pack with QwertyAdjacency
        val baseCorrections = wordDict.correct(word, Int.MAX_VALUE)
        println("\n=== baseCorrections (${baseCorrections.size} results) ===")
        baseCorrections.take(20).forEach {
            println("  ${it.word}  d=${it.editDistance}  freq=${it.frequency}")
        }

        // Stage 3: merge with min editDistance
        val byWord = LinkedHashMap<String, FuzzyResult>()
        for (r in userFuzzy + baseCorrections) {
            val existing = byWord[r.word]
            if (existing == null || r.editDistance < existing.editDistance) byWord[r.word] = r
        }
        val merged = byWord.values.toList()
            .sortedWith(compareBy({ it.editDistance }, { -commonPrefixLength(word, it.word) }, { -it.frequency }))
            .take(5)

        println("\n=== Final merged top-5 ===")
        merged.forEach { println("  ${it.word}  d=${it.editDistance}") }

        val words = merged.map { it.word }
        assertFalse("seaver must NOT appear", "seaver" in words)
        assertFalse("desert must NOT appear", "desert" in words)
        assertFalse("decry must NOT appear", "decry" in words)
        assertTrue("search must appear", "search" in words)
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }
}
