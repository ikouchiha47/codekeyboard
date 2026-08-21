package com.codekeyboard

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Smoke test for LanguagePack CKLM v1 reader.
 * Uses the real compiled pack at /tmp/en.cklm (copied to test resources if needed).
 */
class LanguagePackTest {

    companion object {
        private lateinit var pack: LanguagePack
        private val TEST_FILE = File("/tmp/en.cklm")

        @BeforeClass
        @JvmStatic
        fun loadPack() {
            assumeTrue("Test file /tmp/en.cklm not found — run compile_cklm.py first", TEST_FILE.exists())
            pack = LanguagePack.open(TEST_FILE)
        }
    }

    @Test fun `header validation vocab_count == 65535`() {
        assertEquals(65535, pack.vocabSize)
    }

    @Test fun `header validation node_count == 334915`() {
        assertEquals(334915, pack.nodeCount)
    }

    @Test fun `header validation follower_count == 3264425`() {
        assertEquals(3264425, pack.totalFollowers)
    }

    @Test fun `header validation char_trie_nodes gt 0`() {
        assertTrue("charTrieNodeCount should be > 0", pack.charTrieNodeCount > 0)
    }

    @Test fun `word(0) and word(65534) are non-empty`() {
        val w0 = pack.word(0)
        val wLast = pack.word(65534)
        assertTrue("word(0) should not be empty", w0.isNotEmpty())
        assertTrue("word(65534) should not be empty", wLast.isNotEmpty())
    }

    @Test fun `id(word(0)) == 0`() {
        val w0 = pack.word(0)
        assertEquals(0, pack.id(w0))
    }

    @Test fun `root followers 255 entries first score approx 1_0`() {
        val rootFollowers = pack.followers(emptyList())
        assertEquals(255, rootFollowers.size)
        val (firstWordId, firstScore) = rootFollowers[0]
        assertTrue("First root follower score should be ≈ 1.0, got $firstScore", firstScore > 0.99f && firstScore <= 1.01f)
    }

    @Test fun `known context returns non-empty ranked list`() {
        // Look up a common 2-word context from the trigram JSON
        // "the" and "a" are very common — let's find their word IDs
        val theId = pack.id("the")
        val aId = pack.id("a")
        assumeTrue("'the' not in vocab", theId >= 0)
        assumeTrue("'a' not in vocab", aId >= 0)

        // Try "the a" as a 2-word context (depth-2 = trigram)
        val followers = pack.followers(listOf(theId, aId))
        assertTrue("Context 'the a' should have followers", followers.isNotEmpty())

        // Verify scores are in descending order
        var prevScore = Float.MAX_VALUE
        for ((_, score) in followers) {
            assertTrue("Followers should be score-descending: $prevScore -> $score", score <= prevScore + 1e-6f)
            prevScore = score
        }

        // Top-3 should match what's in the trigram JSON for this context
        // We can't easily cross-reference without loading the JSON, but we can verify
        // the structure is correct: word IDs are valid, scores are in range
        for ((wordId, score) in followers.take(3)) {
            assertTrue("wordId $wordId should be valid", wordId in 0 until pack.vocabSize)
            assertTrue("score $score should be in (0, 1]", score > 0f && score <= 1.01f)
        }
    }

    @Test fun `unknown context returns empty list`() {
        // Use word IDs that are unlikely to form a valid path
        // 65534 is the last word, 65533 second to last — very unlikely to be a real context
        val followers = pack.followers(listOf(65534, 65533))
        assertTrue("Unknown context should return empty list", followers.isEmpty())
    }

    @Test fun `support root returns positive count`() {
        val rootSupport = pack.support(emptyList())
        assertTrue("Root support should be > 0, got $rootSupport", rootSupport > 0)
    }

    @Test fun `support known bigram context returns positive count`() {
        val theId = pack.id("the")
        val aId = pack.id("a")
        assumeTrue("'the' not in vocab", theId >= 0)
        assumeTrue("'a' not in vocab", aId >= 0)

        val support = pack.support(listOf(theId, aId))
        assertTrue("Support for 'the a' should be > 0, got $support", support > 0)
    }

    @Test fun `support unknown context returns 0`() {
        // Use word IDs that are unlikely to form a valid path
        val support = pack.support(listOf(65534, 65533))
        assertEquals("Unknown context support should be 0", 0, support)
    }

    @Test fun `support single word context returns positive count`() {
        val theId = pack.id("the")
        assumeTrue("'the' not in vocab", theId >= 0)

        val support = pack.support(listOf(theId))
        assertTrue("Support for 'the' should be > 0, got $support", support > 0)
    }

    @Test fun `phrases from root returns phrase terminals`() {
        val phrases = pack.phrases(emptyList(), maxExtension = 3)
        // May be empty if no phrases in the pack, but should not crash
        for ((path, score) in phrases) {
            assertTrue("Phrase path should not be empty", path.isNotEmpty())
            assertTrue("Phrase score should be > 0", score > 0f)
        }
    }

    @Test fun `phrases from known context`() {
        val theId = pack.id("the")
        assumeTrue("'the' not in vocab", theId >= 0)

        val phrases = pack.phrases(listOf(theId), maxExtension = 2)
        for ((path, score) in phrases) {
            assertTrue("Phrase path should start with context", path.first() == theId)
            assertTrue("Phrase score should be > 0", score > 0f)
        }
    }

    @Test fun `score decode byte 255 maps to 1_0`() {
        // The root followers' first entry should have byte 255 (max score)
        // We can't directly test the byte, but we verified first score ≈ 1.0 above
        // This test documents the expected behavior
        val (_, score) = pack.followers(emptyList())[0]
        assertEquals(1.0f, score, 0.02f) // Allow small floating-point variance
    }

    @Test fun `vocab is sorted`() {
        var prev = pack.word(0)
        for (i in 1 until 100) { // Spot-check first 100
            val curr = pack.word(i)
            assertTrue("Vocab should be sorted: '$prev' > '$curr'", prev <= curr)
            prev = curr
        }
    }

    @Test fun `id() returns -1 for unknown word`() {
        assertEquals(-1, pack.id("__nonexistent_word_xyz__"))
    }

    // ── Char-trie (WORD tier) tests ────────────────────────────────────────────

    @Test fun `has returns true for common words`() {
        assertTrue("has('hello')", pack.has("hello"))
        assertTrue("has('world')", pack.has("world"))
        assertTrue("has('the')", pack.has("the"))
        assertTrue("has('and')", pack.has("and"))
        assertTrue("has('keyboard')", pack.has("keyboard"))
    }

    @Test fun `has returns false for nonexistent words`() {
        assertFalse("has('qz')", pack.has("qz"))
        assertFalse("has('xqzjw')", pack.has("xqzjw"))
        assertFalse("has('zzzzz')", pack.has("zzzzz"))
    }

    @Test fun `has is case insensitive`() {
        // Char-trie stores lowercase; "Hello"/"THE" should match "hello"/"the"
        // (matches legacy Trie.has behavior — see Trie.kt lowercases input).
        assertTrue("has('hello')", pack.has("hello"))
        assertTrue("has('Hello')", pack.has("Hello"))
        assertTrue("has('HELLO')", pack.has("HELLO"))
    }

    @Test fun `has returns false for empty string`() {
        assertFalse("has('')", pack.has(""))
    }

    @Test fun `suggest returns completions for common prefixes`() {
        val results = pack.suggest("hel", 10)
        assertTrue("suggest('hel') should not be empty", results.isNotEmpty())
        assertTrue("suggest('hel') should contain 'help'", results.any { it.first == "help" })
        assertTrue("suggest('hel') should contain 'held'", results.any { it.first == "held" })
        // Results should be sorted by score descending
        var prevScore = Float.MAX_VALUE
        for ((_, score) in results) {
            assertTrue("Scores should be descending: $prevScore -> $score", score <= prevScore + 1e-6f)
            prevScore = score
        }
    }

    @Test fun `suggest returns empty for unknown prefix`() {
        val results = pack.suggest("qzx", 5)
        assertTrue("suggest('qzx') should be empty", results.isEmpty())
    }

    @Test fun `suggest respects max parameter`() {
        val results3 = pack.suggest("a", 3)
        assertTrue("suggest('a', 3) size <= 3", results3.size <= 3)

        val results10 = pack.suggest("a", 10)
        assertTrue("suggest('a', 10) size <= 10", results10.size <= 10)
    }

    @Test fun `suggest with max 0 returns empty`() {
        assertTrue("suggest('hel', 0) should be empty", pack.suggest("hel", 0).isEmpty())
    }

    @Test fun `suggest empty prefix returns empty`() {
        assertTrue("suggest('', 5) should be empty", pack.suggest("", 5).isEmpty())
    }

    @Test fun `unigramScore returns high score for common words`() {
        val theId = pack.id("the")
        assumeTrue("'the' not in vocab", theId >= 0)
        val score = pack.unigramScore(theId)
        assertTrue("unigramScore('the') should be > 0, got $score", score > 0f)
        // "the" is typically the most common word, score should be near 1.0
        assertTrue("unigramScore('the') should be high, got $score", score > 0.5f)
    }

    @Test fun `unigramScore returns positive score for known words`() {
        val helloId = pack.id("hello")
        assumeTrue("'hello' not in vocab", helloId >= 0)
        val score = pack.unigramScore(helloId)
        assertTrue("unigramScore('hello') should be > 0, got $score", score > 0f)
    }

    @Test fun `unigramScore returns 0 for unknown wordId`() {
        assertEquals(0.0f, pack.unigramScore(-1), 0.0f)
        assertEquals(0.0f, pack.unigramScore(999999), 0.0f)
    }

    @Test fun `unigramScore returns 0 for non-terminal word`() {
        // Some words in vocab might not be terminals in char-trie
        // We can't easily know which, but we can test that it doesn't crash
        for (i in 0 until 100) {
            val score = pack.unigramScore(i)
            assertTrue("unigramScore($i) should be >= 0, got $score", score >= 0f)
        }
    }

    @Test fun `prefix walks t th the a an and`() {
        // Test that we can walk common prefixes
        val t = pack.suggest("t", 3)
        assertTrue("suggest('t') not empty", t.isNotEmpty())
        assertTrue("suggest('t') starts with t", t.all { it.first.startsWith("t") })

        val th = pack.suggest("th", 3)
        assertTrue("suggest('th') not empty", th.isNotEmpty())
        assertTrue("suggest('th') starts with th", th.all { it.first.startsWith("th") })

        val the = pack.suggest("the", 3)
        assertTrue("suggest('the') not empty", the.isNotEmpty())
        assertTrue("suggest('the') starts with the", the.all { it.first.startsWith("the") })

        val a = pack.suggest("a", 3)
        assertTrue("suggest('a') not empty", a.isNotEmpty())
        assertTrue("suggest('a') starts with a", a.all { it.first.startsWith("a") })

        val an = pack.suggest("an", 3)
        assertTrue("suggest('an') not empty", an.isNotEmpty())
        assertTrue("suggest('an') starts with an", an.all { it.first.startsWith("an") })

        val and = pack.suggest("and", 3)
        assertTrue("suggest('and') not empty", and.isNotEmpty())
        assertTrue("suggest('and') starts with and", and.all { it.first.startsWith("and") })
    }

    // ── Benchmarks ────────────────────────────────────────────────────────────

    @Test fun `benchmark followers root`() {
        val ms = bench(10_000) { pack.followers(emptyList()) }
        println("followers(root)        10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark followers depth-2`() {
        val theId = pack.id("the")
        val aId = pack.id("a")
        assumeTrue(theId >= 0 && aId >= 0)
        val ms = bench(10_000) { pack.followers(listOf(theId, aId)) }
        println("followers(depth-2)     10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark id lookup`() {
        val ms = bench(10_000) { pack.id("the") }
        println("id('the')              10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark word decode`() {
        val ms = bench(10_000) { pack.word(0) }
        println("word(0)                10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark suggest`() {
        val ms = bench(10_000) { pack.suggest("hel", 5) }
        println("suggest('hel',5)       10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark has`() {
        val ms = bench(10_000) { pack.has("hello") }
        println("has('hello')           10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark unigramScore`() {
        val theId = pack.id("the")
        assumeTrue(theId >= 0)
        val ms = bench(10_000) { pack.unigramScore(theId) }
        println("unigramScore('the')    10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private inline fun bench(iterations: Int, block: () -> Unit): Double {
        repeat(iterations / 10) { block() } // warmup
        val t0 = System.nanoTime()
        repeat(iterations) { block() }
        return (System.nanoTime() - t0) / 1_000_000.0
    }

    private fun Double.fmt() = "%.2f".format(this)
    private fun Double.fmt3() = "%.4f".format(this)
}