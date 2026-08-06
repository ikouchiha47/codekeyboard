package com.codekeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WordLearnerTest {

    private lateinit var userTrie: UserTrie
    private val knownWords = mutableSetOf<String>()
    private lateinit var learner: WordLearner

    @Before fun setUp() {
        userTrie = UserTrie()
        knownWords.clear()
        learner = WordLearner(userTrie) { word -> word in knownWords }
    }

    // ── learnFromFlush (space/enter/punctuation commits raw buffer) ────────────

    @Test fun `flush — known word is learned`() {
        knownWords += "hello"
        learner.learnFromFlush("hello")
        assertTrue(userTrie.suggest("hello", 1).any { it.word == "hello" })
    }

    @Test fun `flush — partial word not in dictionary is NOT learned`() {
        // user typed "hel" and pressed space — "hel" is not a known word
        learner.learnFromFlush("hel")
        assertTrue(userTrie.suggest("hel", 5).isEmpty())
    }

    @Test fun `flush — typo not in dictionary is NOT learned`() {
        learner.learnFromFlush("teh")
        assertTrue(userTrie.suggest("teh", 5).isEmpty())
    }

    @Test fun `flush — snippet trigger word is NOT learned`() {
        knownWords += ";em"
        learner.learnFromFlush(";em")
        assertTrue(userTrie.suggest(";", 5).isEmpty())
    }

    @Test fun `flush — single character is NOT learned`() {
        knownWords += "a"
        learner.learnFromFlush("a")
        assertTrue(userTrie.suggest("a", 5).isEmpty())
    }

    @Test fun `flush — repeated commits increase frequency`() {
        knownWords += "hello"
        repeat(3) { learner.learnFromFlush("hello") }
        assertEquals(3, userTrie.suggest("hello", 1).first().frequency)
    }

    // ── learnFromTap (explicit suggestion bar tap) ─────────────────────────────

    @Test fun `tap — word is always learned regardless of dictionary`() {
        // suggestion tap is an explicit user choice — no dictionary gate
        learner.learnFromTap("ikouchiha47")
        assertTrue(userTrie.suggest("ikou", 5).any { it.word == "ikouchiha47" })
    }

    @Test fun `tap — known word is learned`() {
        knownWords += "hello"
        learner.learnFromTap("hello")
        assertTrue(userTrie.suggest("hel", 5).any { it.word == "hello" })
    }

    @Test fun `tap — snippet trigger word is NOT learned`() {
        learner.learnFromTap(";em")
        assertTrue(userTrie.suggest(";", 5).isEmpty())
    }

    @Test fun `tap — single character is NOT learned`() {
        learner.learnFromTap("a")
        assertTrue(userTrie.suggest("a", 5).isEmpty())
    }

    @Test fun `tap — repeated taps increase frequency`() {
        repeat(5) { learner.learnFromTap("hello") }
        assertEquals(5, userTrie.suggest("hello", 1).first().frequency)
    }

    // ── tap beats flush in frequency ranking ───────────────────────────────────

    @Test fun `tapped word outranks flush word with same prefix`() {
        knownWords += "help"
        repeat(2) { learner.learnFromFlush("help") }   // known, learned twice
        repeat(5) { learner.learnFromTap("hello") }    // tapped five times
        val top = userTrie.suggest("hel", 2)
        assertEquals("hello", top.first().word)
    }

    // ── blank / empty edge cases ───────────────────────────────────────────────

    @Test fun `flush — blank string is ignored`() {
        learner.learnFromFlush("")
        learner.learnFromFlush("   ")
        // no exception, trie still empty
        assertTrue(userTrie.suggest("a", 5).isEmpty())
    }

    @Test fun `tap — blank string is ignored`() {
        learner.learnFromTap("")
        assertTrue(userTrie.suggest("a", 5).isEmpty())
    }
}
