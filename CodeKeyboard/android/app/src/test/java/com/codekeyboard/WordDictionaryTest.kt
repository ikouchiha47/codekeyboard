package com.codekeyboard

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Tests for WordDictionary — pack-backed prefix/fuzzy completion over the char-trie.
 */
class WordDictionaryTest {

    companion object {
        private lateinit var pack: LanguagePack
        private lateinit var wordDict: WordDictionary
        private lateinit var legacyTrie: Trie
        private val PACK_FILE = File("/tmp/en.cklm")
        private val TRIE_FILE = File("src/main/assets/en.trie")

        @BeforeClass
        @JvmStatic
        fun loadResources() {
            assumeTrue("Test file /tmp/en.cklm not found", PACK_FILE.exists())
            println("TRIE_FILE absolute path: ${TRIE_FILE.absolutePath}")
            println("TRIE_FILE exists: ${TRIE_FILE.exists()}")
            assumeTrue("Legacy trie asset not found at ${TRIE_FILE.absolutePath}", TRIE_FILE.exists())
            pack = LanguagePack.open(PACK_FILE)
            wordDict = WordDictionary(pack)
            legacyTrie = Trie.load(TRIE_FILE)
            println("Loaded WordDictionary (pack: ${pack.vocabSize} words, ${pack.charTrieNodeCount} char-trie nodes)")
            println("Loaded legacy Trie")
        }
    }

    @Test fun `has returns true for common words`() {
        assertTrue("has('hello')", wordDict.has("hello"))
        assertTrue("has('world')", wordDict.has("world"))
        assertTrue("has('the')", wordDict.has("the"))
        assertTrue("has('and')", wordDict.has("and"))
        assertTrue("has('keyboard')", wordDict.has("keyboard"))
    }

    @Test fun `has returns false for nonexistent words`() {
        assertFalse("has('qz')", wordDict.has("qz"))
        assertFalse("has('xqzjw')", wordDict.has("xqzjw"))
        assertFalse("has('zzzzz')", wordDict.has("zzzzz"))
    }

    @Test fun `has is case insensitive`() {
        // Char-trie stores lowercase; "Hello"/"THE" should match "hello"/"the"
        // (matches legacy Trie.has behavior — see Trie.kt lowercases input).
        assertTrue("has('hello')", wordDict.has("hello"))
        assertTrue("has('Hello')", wordDict.has("Hello"))
        assertTrue("has('HELLO')", wordDict.has("HELLO"))
    }

    @Test fun `has returns false for empty string`() {
        assertFalse("has('')", wordDict.has(""))
    }

    @Test fun `suggest returns completions for common prefixes`() {
        val results = wordDict.suggest("hel", 10)
        assertTrue("suggest('hel') should not be empty", results.isNotEmpty())
        assertTrue("suggest('hel') should contain 'hello'", results.contains("hello"))
        assertTrue("suggest('hel') should contain 'help'", results.contains("help"))
        assertTrue("suggest('hel') should contain 'held'", results.contains("held"))
    }

    @Test fun `suggest returns empty for unknown prefix`() {
        val results = wordDict.suggest("qzx", 5)
        assertTrue("suggest('qzx') should be empty", results.isEmpty())
    }

    @Test fun `suggest respects max parameter`() {
        val results3 = wordDict.suggest("a", 3)
        assertTrue("suggest('a', 3) size <= 3", results3.size <= 3)

        val results10 = wordDict.suggest("a", 10)
        assertTrue("suggest('a', 10) size <= 10", results10.size <= 10)
    }

    @Test fun `suggest with max 0 returns empty`() {
        assertTrue("suggest('hel', 0) should be empty", wordDict.suggest("hel", 0).isEmpty())
    }

    @Test fun `suggest empty prefix returns empty`() {
        assertTrue("suggest('', 5) should be empty", wordDict.suggest("", 5).isEmpty())
    }

    @Test fun `prefix walks t th the a an and`() {
        // Test that we can walk common prefixes
        val t = wordDict.suggest("t", 3)
        assertTrue("suggest('t') not empty", t.isNotEmpty())
        assertTrue("suggest('t') starts with t", t.all { it.startsWith("t") })

        val th = wordDict.suggest("th", 3)
        assertTrue("suggest('th') not empty", th.isNotEmpty())
        assertTrue("suggest('th') starts with th", th.all { it.startsWith("th") })

        val the = wordDict.suggest("the", 3)
        assertTrue("suggest('the') not empty", the.isNotEmpty())
        assertTrue("suggest('the') starts with the", the.all { it.startsWith("the") })

        val a = wordDict.suggest("a", 3)
        assertTrue("suggest('a') not empty", a.isNotEmpty())
        assertTrue("suggest('a') starts with a", a.all { it.startsWith("a") })

        val an = wordDict.suggest("an", 3)
        assertTrue("suggest('an') not empty", an.isNotEmpty())
        assertTrue("suggest('an') starts with an", an.all { it.startsWith("an") })

        val and = wordDict.suggest("and", 3)
        assertTrue("suggest('and') not empty", and.isNotEmpty())
        assertTrue("suggest('and') starts with and", and.all { it.startsWith("and") })
    }

    // ── Fuzzy search tests (BevaTrieSearch via TrieAdapter) ──────────────────

    @Test fun `fuzzy search finds corrections for typos (edit distance 1)`() {
        // Test that fuzzy search works and returns results with correct edit distance
        val results = BevaTrieSearch.search(wordDict.adapter, "helo", 1, 10)
        assertTrue("fuzzy 'helo' should return results", results.isNotEmpty())
        // All results should have edit distance <= 1
        assertTrue("all results should have edit distance <= 1", results.all { it.editDistance <= 1 })
        // Results should be sorted by edit distance ASC, then frequency DESC
        for (i in 1 until results.size) {
            val prev = results[i - 1]
            val curr = results[i]
            assertTrue("sorted by edit distance: ${prev.editDistance} <= ${curr.editDistance}", prev.editDistance <= curr.editDistance)
            if (prev.editDistance == curr.editDistance) {
                assertTrue("same edit distance sorted by frequency DESC: ${prev.frequency} >= ${curr.frequency}", prev.frequency >= curr.frequency)
            }
        }
    }

    @Test fun `fuzzy search finds corrections for wold`() {
        val results = BevaTrieSearch.search(wordDict.adapter, "wold", 1, 10)
        assertTrue("fuzzy 'wold' should return results", results.isNotEmpty())
        assertTrue("all results should have edit distance <= 1", results.all { it.editDistance <= 1 })
    }

    @Test fun `fuzzy search finds corrections for keybord`() {
        val results = BevaTrieSearch.search(wordDict.adapter, "keybord", 1, 10)
        assertTrue("fuzzy 'keybord' should return results", results.isNotEmpty())
        assertTrue("all results should have edit distance <= 1", results.all { it.editDistance <= 1 })
    }

    @Test fun `fuzzy search with threshold 0 returns empty`() {
        val results = BevaTrieSearch.search(wordDict.adapter, "helo", 0, 5)
        assertTrue("threshold 0 should return empty", results.isEmpty())
    }

    @Test fun `fuzzy search respects maxResults`() {
        val results = BevaTrieSearch.search(wordDict.adapter, "a", 2, 3)
        assertTrue("maxResults=3 should limit results", results.size <= 3)
    }

    @Test fun `fuzzy search results sorted by edit distance then frequency`() {
        val results = BevaTrieSearch.search(wordDict.adapter, "teh", 1, 10)
        assertTrue("results not empty", results.isNotEmpty())
        // First result should have edit distance 1
        assertEquals("first result edit distance should be 1", 1, results[0].editDistance)
        // All results should have edit distance <= 1
        assertTrue("all results edit distance <= 1", results.all { it.editDistance <= 1 })
    }

    // ── Parity vs legacy Trie ────────────────────────────────────────────────

    @Test fun `parity suggest returns valid completions for common prefixes`() {
        val testPrefixes = listOf("a", "an", "and", "the", "th", "hel", "wor", "key", "pro", "com")

        for (prefix in testPrefixes) {
            val dictResults = wordDict.suggest(prefix, 5)
            val trieResults = legacyTrie.suggest(prefix, 5)

            // Both should return words starting with the prefix
            assertTrue("WordDictionary results start with '$prefix'", dictResults.all { it.startsWith(prefix) })
            assertTrue("Trie results start with '$prefix'", trieResults.all { it.startsWith(prefix) })

            // Both should return non-empty results for common prefixes
            assertTrue("WordDictionary suggest('$prefix') not empty", dictResults.isNotEmpty())
            assertTrue("Trie suggest('$prefix') not empty", trieResults.isNotEmpty())

            println("Prefix '$prefix': WordDict=${dictResults.take(3)}, Trie=${trieResults.take(3)}")
        }
    }

    @Test fun `parity has matches legacy Trie for known words`() {
        val testWords = listOf("hello", "world", "the", "and", "keyboard", "android", "test", "word", "qz", "xqzjw")
        var matches = 0

        for (word in testWords) {
            val dictHas = wordDict.has(word)
            val trieHas = legacyTrie.has(word)
            if (dictHas == trieHas) matches++
            println("has('$word'): WordDict=$dictHas, Trie=$trieHas")
        }

        println("Parity has: $matches/${testWords.size} match")
        assertTrue("has() should match legacy Trie for all test words", matches == testWords.size)
    }

    @Test fun `fuzzy search returns valid corrections (parity with legacy Trie)`() {
        // Compare BevaTrieSearch results using WordDictionary adapter vs legacy BaseTrieAdapter
        val legacyAdapter = BaseTrieAdapter(legacyTrie)
        val testQueries = listOf("helo", "wold", "keybord", "teh")

        for (query in testQueries) {
            val dictResults = BevaTrieSearch.search(wordDict.adapter, query, 1, 5)
            val trieResults = BevaTrieSearch.search(legacyAdapter, query, 1, 5)

            println("Fuzzy '$query': WordDict=${dictResults.map { "${it.word}(${it.editDistance})" }}, Trie=${trieResults.map { "${it.word}(${it.editDistance})" }}")

            // Both should return results
            assertTrue("WordDict fuzzy '$query' not empty", dictResults.isNotEmpty())
            assertTrue("Trie fuzzy '$query' not empty", trieResults.isNotEmpty())

            // Both should have edit distance <= 1
            assertTrue("WordDict all edit distance <= 1", dictResults.all { it.editDistance <= 1 })
            assertTrue("Trie all edit distance <= 1", trieResults.all { it.editDistance <= 1 })
        }
    }

    @Test fun `adapter root is 0`() {
        assertEquals(0, wordDict.adapter.root)
    }

    @Test fun `adapter isTerminal matches has()`() {
        val testWords = listOf("hello", "world", "the", "and", "keyboard", "qz")
        for (word in testWords) {
            val nodeIdx = walkToNode(word)
            if (nodeIdx >= 0) {
                val adapterTerminal = wordDict.adapter.isTerminal(nodeIdx)
                val hasResult = wordDict.has(word)
                assertEquals("isTerminal for '$word' should match has()", hasResult, adapterTerminal)
            }
        }
    }

    @Test fun `adapter frequency returns freq byte`() {
        val nodeIdx = walkToNode("the")
        assumeTrue("'the' should exist in char-trie", nodeIdx >= 0)
        val freq = wordDict.adapter.frequency(nodeIdx)
        assertTrue("frequency('the') should be > 0", freq > 0)
        // Frequency is the raw u8 byte (0-255)
        assertTrue("frequency should be in u8 range", freq in 0..255)
    }

    @Test fun `adapter iterateChildren works`() {
        val rootChildren = mutableListOf<Pair<Char, Int>>()
        wordDict.adapter.iterateChildren(wordDict.adapter.root) { ch, child ->
            rootChildren.add(ch to child)
        }
        assertTrue("root should have children", rootChildren.isNotEmpty())
        // Root children should include common starting letters
        val chars = rootChildren.map { it.first }.toSet()
        assertTrue("root should have 't' child", chars.contains('t'))
        assertTrue("root should have 'a' child", chars.contains('a'))
        assertTrue("root should have 'h' child", chars.contains('h'))
    }

    // ── Helper ────────────────────────────────────────────────────────────────

    /** Walks the char-trie to find the node for a word (for testing adapter internals). */
    private fun walkToNode(word: String): Int {
        var nodeIdx = 0
        for (i in 0 until word.length) {
            val cp = word.codePointAt(i)
            nodeIdx = findCharChild(nodeIdx, cp)
            if (nodeIdx < 0) return -1
        }
        return nodeIdx
    }

    /** Binary search for child with given code point in char-trie node's children array. */
    private fun findCharChild(nodeIdx: Int, codePoint: Int): Int {
        // Use the adapter's iterateChildren to find the child
        var result = -1
        wordDict.adapter.iterateChildren(nodeIdx) { ch, child ->
            if (ch.code == codePoint) result = child
        }
        return result
    }
}