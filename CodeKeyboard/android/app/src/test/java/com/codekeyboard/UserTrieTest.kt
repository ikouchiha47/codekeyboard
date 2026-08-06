package com.codekeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class UserTrieTest {

    private lateinit var trie: UserTrie

    @Before fun setUp() { trie = UserTrie() }

    // ── insert + basic suggest ─────────────────────────────────────────────────

    @Test fun `inserted word appears in suggest`() {
        trie.insert("torrent")
        val results = trie.suggest("tor", 5)
        assertTrue(results.any { it.word == "torrent" })
    }

    @Test fun `unknown word not in base dict is learned`() {
        trie.insert("ikouchiha47")
        val results = trie.suggest("ikou", 5)
        assertEquals("ikouchiha47", results.first().word)
    }

    @Test fun `frequency increments on repeated insert`() {
        repeat(5) { trie.insert("hello") }
        val results = trie.suggest("hel", 5)
        assertEquals(5, results.first { it.word == "hello" }.frequency)
    }

    @Test fun `higher frequency word ranks first`() {
        repeat(10) { trie.insert("help") }
        repeat(2)  { trie.insert("her") }
        val results = trie.suggest("he", 5)
        assertEquals("help", results.first().word)
    }

    @Test fun `suggest returns empty for unknown prefix`() {
        trie.insert("hello")
        assertEquals(emptyList<ScoredWord>(), trie.suggest("xyz", 5))
    }

    @Test fun `suggest respects k limit`() {
        listOf("he", "her", "here", "hero", "help", "helm").forEach { trie.insert(it) }
        val results = trie.suggest("he", 3)
        assertEquals(3, results.size)
    }

    @Test fun `suggest on empty trie returns empty`() {
        assertEquals(emptyList<ScoredWord>(), trie.suggest("he", 5))
    }

    @Test fun `exact prefix match is terminal and returned`() {
        trie.insert("he")
        trie.insert("her")
        val results = trie.suggest("he", 5)
        assertTrue(results.any { it.word == "he" })
    }

    @Test fun `maxDescendantFreq updated correctly after insert`() {
        trie.insert("help")
        trie.insert("help")
        trie.insert("help")
        // root's maxDescendantFreq must reflect the highest frequency in tree
        assertEquals(3, trie.root.maxDescendantFreq)
    }

    @Test fun `maxDescendantFreq propagates through ancestors`() {
        trie.insert("a")
        repeat(5) { trie.insert("ab") }
        // root -> 'a' node: maxDescendantFreq should be 5 (from "ab")
        val aNode = trie.root.children['a']!!
        assertEquals(5, aNode.maxDescendantFreq)
        assertEquals(5, trie.root.maxDescendantFreq)
    }

    // ── A* (best-first) == DFS ground truth ───────────────────────────────────
    // Property: for any trie and prefix, best-first returns the same top-k
    // as exhaustive DFS sorted by frequency. This validates pruning correctness.

    @Test fun `best-first matches DFS ground truth — simple case`() {
        listOf("her" to 3, "here" to 1, "hero" to 5, "help" to 2, "helm" to 7).forEach { (w, n) ->
            repeat(n) { trie.insert(w) }
        }
        val prefixes = listOf("h", "he", "her", "hel")
        for (prefix in prefixes) {
            val bf  = trie.suggest(prefix, 3)
            val dfs = trie.suggestDfs(prefix, 3)
            assertEquals("best-first != DFS for prefix '$prefix'", dfs, bf)
        }
    }

    @Test fun `best-first matches DFS ground truth — random trie`() {
        val words = listOf(
            "apple" to 4, "app" to 2, "application" to 1, "apply" to 8,
            "apt" to 3, "ape" to 6, "apex" to 5, "april" to 7,
        )
        words.forEach { (w, n) -> repeat(n) { trie.insert(w) } }
        val prefixes = listOf("a", "ap", "app", "appl")
        for (prefix in prefixes) {
            val bf  = trie.suggest(prefix, 5)
            val dfs = trie.suggestDfs(prefix, 5)
            assertEquals("best-first != DFS for prefix '$prefix'", dfs, bf)
        }
    }

    @Test fun `best-first matches DFS — tie-breaking consistent`() {
        // All words inserted once — frequency all equal to 1.
        listOf("cat", "car", "card", "care", "cart").forEach { trie.insert(it) }
        val bf  = trie.suggest("ca", 5)
        val dfs = trie.suggestDfs("ca", 5)
        assertEquals(dfs.map { it.word }.toSet(), bf.map { it.word }.toSet())
    }

    // ── Serialization round-trip ──────────────────────────────────────────────

    @Test fun `save and load preserves all words and frequencies`() {
        listOf("torrent" to 3, "hello" to 5, "world" to 1, "ikouchiha47" to 2).forEach { (w, n) ->
            repeat(n) { trie.insert(w) }
        }
        val file = File.createTempFile("user", ".trie")
        try {
            trie.save(file)
            val loaded = UserTrie.load(file)
            listOf("torrent" to 3, "hello" to 5, "world" to 1, "ikouchiha47" to 2).forEach { (w, n) ->
                val results = loaded.suggest(w, 1)
                assertEquals("frequency mismatch for '$w'", n, results.firstOrNull()?.frequency ?: 0)
            }
        } finally {
            file.delete()
        }
    }

    @Test fun `load from nonexistent file returns empty trie`() {
        val file = File("/tmp/nonexistent_user.trie")
        val loaded = UserTrie.load(file)
        assertEquals(emptyList<ScoredWord>(), loaded.suggest("a", 5))
    }

    @Test fun `round-trip preserves maxDescendantFreq for pruning`() {
        repeat(10) { trie.insert("help") }
        repeat(2)  { trie.insert("her") }
        val file = File.createTempFile("user", ".trie")
        try {
            trie.save(file)
            val loaded = UserTrie.load(file)
            // After load, best-first should still rank help above her
            val results = loaded.suggest("he", 3)
            assertEquals("help", results.first().word)
        } finally {
            file.delete()
        }
    }

    @Test fun `empty trie save and load round-trips cleanly`() {
        val file = File.createTempFile("user_empty", ".trie")
        try {
            trie.save(file)
            val loaded = UserTrie.load(file)
            assertEquals(emptyList<ScoredWord>(), loaded.suggest("a", 5))
        } finally {
            file.delete()
        }
    }

    @Test fun `atomic write — tmp file does not persist after save`() {
        trie.insert("hello")
        val file = File.createTempFile("user", ".trie")
        try {
            trie.save(file)
            val tmp = File(file.parent, file.name + ".tmp")
            assertFalse("tmp file should not exist after save", tmp.exists())
        } finally {
            file.delete()
        }
    }
}
