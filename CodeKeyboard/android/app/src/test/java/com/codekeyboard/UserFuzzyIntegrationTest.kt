package com.codekeyboard

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Loads the real user.trie from /tmp/user.trie (pulled from device) and runs
 * the full MergedSuggestionStrategy pipeline. Skipped if the file is absent
 * (CI, other machines) — pull it first with:
 *   adb shell "run-as com.codekeyboard cat /data/data/com.codekeyboard/files/user.trie" > /tmp/user.trie
 */
class UserFuzzyIntegrationTest {

    private val userTrieFile = File("/tmp/user.trie")
    private val packFile: File = run {
        listOf(
            File("src/main/assets/en.cklm"),
            File("app/src/main/assets/en.cklm"),
            File("android/app/src/main/assets/en.cklm"),
        ).firstOrNull { it.exists() } ?: error("en.cklm not found")
    }

    private val strategy: MergedSuggestionStrategy by lazy {
        val userTrie = UserTrie.load(userTrieFile)
        val userAdapter = UserTrieAdapter(userTrie)
        val wordDict = WordDictionary(LanguagePack.open(packFile))
        MergedSuggestionStrategy(userAdapter, wordDict.adapter, wordDict)
    }

    @Test fun `sesrcg suggests search and not garbage words`() {
        assumeTrue("user.trie not present — pull from device first", userTrieFile.exists())

        val suggestions = strategy.suggest("sesrcg", 5)
        println("sesrcg suggestions: $suggestions")

        assertTrue("search must appear", "search" in suggestions)
        assertFalse("seaver must NOT appear", "seaver" in suggestions)
        assertFalse("desert must NOT appear", "desert" in suggestions)
        assertFalse("decry must NOT appear", "decry" in suggestions)
    }
}
