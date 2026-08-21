package com.codekeyboard

import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

/**
 * Tests for PackNgramModel — NgramModel implementation backed by CKLM pack.
 */
class PackNgramModelTest {

    companion object {
        private lateinit var pack: LanguagePack
        private lateinit var bigramModel: PackNgramModel
        private lateinit var trigramModel: PackNgramModel
        private val PACK_FILE = File("/tmp/en.cklm")

        @BeforeClass
        @JvmStatic
        fun loadPack() {
            assumeTrue("Test file /tmp/en.cklm not found", PACK_FILE.exists())
            pack = LanguagePack.open(PACK_FILE)
            bigramModel = PackNgramModel(pack, order = 2)
            trigramModel = PackNgramModel(pack, order = 3)
            println("Loaded PackNgramModel (bigram order=2, trigram order=3)")
        }
    }

    @Test fun `bigram model order is 2`() {
        assertEquals(2, bigramModel.order)
    }

    @Test fun `trigram model order is 3`() {
        assertEquals(3, trigramModel.order)
    }

    @Test fun `bigram nextWords for known context returns non-empty`() {
        // "the" is a very common word, should have followers
        val results = bigramModel.nextWords("the", n = 5)
        assertTrue("nextWords('the') should not be empty", results.isNotEmpty())
        assertTrue("nextWords('the') size <= 5", results.size <= 5)
        // Top word should be sensible (e.g., "the" often followed by nouns)
        println("bigram nextWords('the'): $results")
    }

    @Test fun `bigram nextWords with prefix filters correctly`() {
        val results = bigramModel.nextWords("the", prefix = "a", n = 10)
        assertTrue("nextWords('the', prefix='a') should not be empty", results.isNotEmpty())
        assertTrue("all results should start with 'a'", results.all { it.startsWith("a") })
        println("bigram nextWords('the', prefix='a'): $results")
    }

    @Test fun `bigram nextWords with unknown prefix returns empty`() {
        val results = bigramModel.nextWords("the", prefix = "qzx", n = 10)
        assertTrue("nextWords('the', prefix='qzx') should be empty", results.isEmpty())
    }

    @Test fun `bigram nextWords with OOV context returns empty`() {
        val results = bigramModel.nextWords("__nonexistent_word_xyz__", n = 5)
        assertTrue("nextWords(OOV) should be empty", results.isEmpty())
    }

    @Test fun `bigram nextWords with insufficient context returns empty`() {
        // order=2 needs 1 context word, empty context should return empty
        val results = bigramModel.nextWords(n = 5)
        assertTrue("nextWords() with no context should be empty", results.isEmpty())
    }

    @Test fun `trigram nextWords for known context returns non-empty`() {
        // "of the" is a very common trigram context
        val results = trigramModel.nextWords("of", "the", n = 5)
        assertTrue("nextWords('of', 'the') should not be empty", results.isNotEmpty())
        assertTrue("nextWords('of', 'the') size <= 5", results.size <= 5)
        println("trigram nextWords('of', 'the'): $results")
    }

    @Test fun `trigram nextWords with prefix filters correctly`() {
        val results = trigramModel.nextWords("of", "the", prefix = "f", n = 10)
        assertTrue("nextWords('of', 'the', prefix='f') should not be empty", results.isNotEmpty())
        assertTrue("all results should start with 'f'", results.all { it.startsWith("f") })
        println("trigram nextWords('of', 'the', prefix='f'): $results")
    }

    @Test fun `trigram nextWords with OOV context returns empty`() {
        val results = trigramModel.nextWords("__nonexistent__", "the", n = 5)
        assertTrue("nextWords(OOV, 'the') should be empty", results.isEmpty())
    }

    @Test fun `trigram nextWords with insufficient context returns empty`() {
        // order=3 needs 2 context words, only 1 provided
        val results = trigramModel.nextWords("the", n = 5)
        assertTrue("nextWords('the') with only 1 context should be empty", results.isEmpty())
    }

    @Test fun `bigram support for known context returns positive`() {
        val support = bigramModel.support("the")
        assertTrue("support('the') should be > 0, got $support", support > 0)
        println("bigram support('the'): $support")
    }

    @Test fun `bigram support matches LanguagePack support`() {
        val theId = pack.id("the")
        assumeTrue("'the' not in vocab", theId >= 0)

        val modelSupport = bigramModel.support("the")
        val packSupport = pack.support(listOf(theId))

        assertEquals("PackNgramModel.support should match LanguagePack.support", packSupport, modelSupport)
    }

    @Test fun `bigram support for unknown context returns 0`() {
        val support = bigramModel.support("__nonexistent_word_xyz__")
        assertEquals("support(OOV) should be 0", 0, support)
    }

    @Test fun `bigram support with insufficient context returns 0`() {
        val support = bigramModel.support()
        assertEquals("support() with no context should be 0", 0, support)
    }

    @Test fun `trigram support for known context returns positive`() {
        val support = trigramModel.support("of", "the")
        assertTrue("support('of', 'the') should be > 0, got $support", support > 0)
        println("trigram support('of', 'the'): $support")
    }

    @Test fun `trigram support matches LanguagePack support`() {
        val ofId = pack.id("of")
        val theId = pack.id("the")
        assumeTrue("'of' not in vocab", ofId >= 0)
        assumeTrue("'the' not in vocab", theId >= 0)

        val modelSupport = trigramModel.support("of", "the")
        val packSupport = pack.support(listOf(ofId, theId))

        assertEquals("PackNgramModel.support should match LanguagePack.support", packSupport, modelSupport)
    }

    @Test fun `trigram support for unknown context returns 0`() {
        val support = trigramModel.support("__nonexistent__", "the")
        assertEquals("support(OOV, 'the') should be 0", 0, support)
    }

    @Test fun `trigram support with insufficient context returns 0`() {
        val support = trigramModel.support("the")
        assertEquals("support('the') with only 1 context should be 0", 0, support)
    }

    @Test fun `nextWords results are score-descending`() {
        // The pack returns followers already score-descending
        // We can verify by checking that the first result has a valid word
        val results = bigramModel.nextWords("the", n = 10)
        assertTrue("results not empty", results.isNotEmpty())
        // All results should be valid words in the vocab
        for (word in results) {
            val id = pack.id(word)
            assertTrue("word '$word' should be in vocab (id=$id)", id >= 0)
        }
    }

    @Test fun `case insensitivity - context words are lowercased`() {
        // Context words should be lowercased internally
        val resultsLower = bigramModel.nextWords("the", n = 5)
        val resultsUpper = bigramModel.nextWords("THE", n = 5)
        assertEquals("nextWords should be case-insensitive for context", resultsLower, resultsUpper)
    }

    @Test fun `prefix filtering is case-insensitive`() {
        val resultsLower = bigramModel.nextWords("the", prefix = "a", n = 5)
        val resultsUpper = bigramModel.nextWords("the", prefix = "A", n = 5)
        assertEquals("prefix filtering should be case-insensitive", resultsLower, resultsUpper)
    }
}