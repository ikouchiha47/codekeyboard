package com.codekeyboard

import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * TDD spec from ADR-013 Task B — candidate reachability via SymSpell.
 * All tests must FAIL before SymSpellIndex/SymSpellCorrector exist.
 */
class SymSpellTest {

    private val vocab = setOf(
        "search", "detective", "the", "hello", "world", "test", "keyboard",
    )
    private lateinit var corrector: SymSpellCorrector

    @Before
    fun setUp() {
        val index = SymSpellIndex.build(vocab, maxDist = 2)
        corrector = SymSpellCorrector(index, QwertyAdjacency(), maxDist = 2)
    }

    @Test fun T1_exactMatch() {
        val results = corrector.correct("search")
        assertTrue("exact 'search' should be found at dist 0", results.any { it.word == "search" && it.editDistance == 0 })
    }

    @Test fun T2_singleDelete_missingChar() {
        // "seach" = "search" minus 'r'
        val results = corrector.correct("seach")
        assertTrue("'seach' should surface 'search'", results.any { it.word == "search" })
    }

    @Test fun T3_singleInsert_extraChar() {
        // "seaarch" = "search" with extra 'a'
        val results = corrector.correct("seaarch")
        assertTrue("'seaarch' should surface 'search'", results.any { it.word == "search" })
    }

    @Test fun T4_multiKeySlide_srwach() {
        // finger slide: 'r' and 'w' smeared into "srwach" — 2 deletes → "sach"
        val results = corrector.correct("srwach")
        assertTrue("'srwach' should surface 'search' (multi-key slide)", results.any { it.word == "search" })
    }

    @Test fun T5_multiKeySlide_swsrch() {
        val results = corrector.correct("swsrch")
        assertTrue("'swsrch' should surface 'search' (multi-key slide)", results.any { it.word == "search" })
    }

    @Test fun T6_adjacentSubstitution_detdctive() {
        // 'e' → 'd' (adjacent on QWERTY)
        val results = corrector.correct("detdctive")
        assertTrue("'detdctive' should surface 'detective'", results.any { it.word == "detective" })
    }

    @Test fun T7_unknownWord_returnsEmpty() {
        val results = corrector.correct("xqzpwv")
        assertTrue("garbage input should return no candidates", results.isEmpty())
    }

    @Test fun T8_shortWord_noCorrection() {
        // words len <= 3: threshold = 0, no fuzzy — must NOT return unrelated words
        val results = corrector.correct("th")
        assertTrue("'th' must not surface unrelated words (threshold 0)", results.all { it.editDistance == 0 })
    }
}