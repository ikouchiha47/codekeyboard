package com.codekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TDD spec from ADR-013 Task B — QWERTY-adjacency weighted ranking.
 * All tests must FAIL before KeyAdjacency/ProximityScorer exist.
 */
class ProximityScorerTest {

    private val adjacency = QwertyAdjacency()
    private val scorer = ProximityScorer(adjacency)

    @Test fun P1_adjacentSubstitutionRanksAboveNonAdjacent() {
        // 'd' is adjacent to 'e' on QWERTY; 'z' is not.
        val scoreAdjacent    = scorer.score("detdctive", "detective")  // d↔e adjacent
        val scoreNonAdjacent = scorer.score("detzctive", "detective")  // z↔e not adjacent
        assertTrue(
            "adjacent sub should rank above non-adjacent: $scoreAdjacent vs $scoreNonAdjacent",
            scoreAdjacent < scoreNonAdjacent,
        )
    }

    @Test fun P2_higherFrequencyWinsTie() {
        val lowFreq  = FuzzyResult("search", editDistance = 1, frequency = 10)
        val highFreq = FuzzyResult("search", editDistance = 1, frequency = 1000)
        val ranked = scorer.rank("seach", listOf(lowFreq, highFreq))
        assertEquals("higher frequency should rank first on a score tie", highFreq, ranked.first())
    }

    @Test fun P3_adjacentCostIsHalf() {
        // 'd' and 'e' are QWERTY neighbours
        assertEquals("adjacent substitution cost should be 0.5", 0.5f, adjacency.substitutionCost('d', 'e'))
    }

    @Test fun P4_nonAdjacentCostIsFull() {
        // 'z' and 'e' are not neighbours
        assertEquals("non-adjacent substitution cost should be 1.0", 1.0f, adjacency.substitutionCost('z', 'e'))
    }

    @Test fun P5_exactMatchCostZero() {
        assertEquals(0f, adjacency.substitutionCost('a', 'a'))
    }

    @Test fun P6_noAdjacencyTreatsAllSubstitutionsAsFullCost() {
        val flat = NoAdjacency
        assertEquals(1.0f, flat.substitutionCost('d', 'e'))  // adjacent on QWERTY but NoAdjacency ignores that
        assertEquals(1.0f, flat.substitutionCost('z', 'e'))
    }
}