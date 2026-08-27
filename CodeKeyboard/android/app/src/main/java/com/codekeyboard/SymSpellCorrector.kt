package com.codekeyboard

/**
 * SymSpell query path (ADR-013 Task E).
 *
 * Given a typed word, generate its delete-variants, look each up in the
 * [SymSpellIndex], collect candidate dictionary words, then score + rank them
 * with the layout-aware [ProximityScorer].
 *
 * Same return type as BevaTrieSearch ([FuzzyResult]) so it can merge cleanly
 * into the existing correction path.
 */
class SymSpellCorrector(
    private val index: SymSpellIndex,
    private val adjacency: KeyAdjacency,
    private val maxDist: Int = 2,
) {
    private val scorer = ProximityScorer(adjacency)

    /**
     * Returns candidate corrections for [input], best first.
     * Empty for words shorter than the fuzzy threshold (len <= 3), or when
     * nothing is reachable within [maxDist].
     */
    fun correct(input: String): List<FuzzyResult> {
        val threshold = FuzzyThreshold.forLength(input.length)
        if (threshold == 0) return emptyList()

        val inputLower = input.lowercase()
        val seen = mutableSetOf<String>()
        val candidates = mutableListOf<FuzzyResult>()

        // The input itself (dist 0) + every delete-variant of it.
        val variants = SymSpellIndex.generateDeletes(inputLower, maxDist) + inputLower

        for (variant in variants) {
            for (word in index.lookup(variant)) {
                if (seen.add(word)) {
                    val dist = scorer.score(inputLower, word)
                    if (dist <= maxDist.toFloat()) {
                        // Frequency is not available here; the caller (WordDictionary)
                        // can enrich with pack unigram scores before final ranking.
                        candidates.add(FuzzyResult(word, dist.toInt(), frequency = 0))
                    }
                }
            }
        }

        return scorer.rank(inputLower, candidates).take(10)
    }
}