package com.codekeyboard

/**
 * Layout-aware weighted edit distance + candidate ranking (ADR-013).
 *
 * Standard Levenshtein, but the cost of a substitution is supplied by the
 * [KeyAdjacency] of the active keymap: substituting a physically adjacent key
 * (fat-finger) costs 0.5, a distant key costs 1.0, and identity costs 0. This
 * makes proximity-plausible corrections rank above random noise at the same
 * edit distance.
 */
class ProximityScorer(private val adjacency: KeyAdjacency) {

    /**
     * Weighted edit distance between [input] and [candidate].
     * delete/insert cost 1.0; substitution cost from [KeyAdjacency].
     */
    fun score(input: String, candidate: String): Float {
        val m = input.length
        val n = candidate.length
        val dp = Array(m + 1) { FloatArray(n + 1) }

        for (i in 0..m) dp[i][0] = i.toFloat()
        for (j in 0..n) dp[0][j] = j.toFloat()

        for (i in 1..m) {
            for (j in 1..n) {
                val subCost = adjacency.substitutionCost(input[i - 1], candidate[j - 1])
                dp[i][j] = minOf(
                    dp[i - 1][j] + 1f,            // delete from input
                    dp[i][j - 1] + 1f,            // insert into input
                    dp[i - 1][j - 1] + subCost,   // substitute (layout-aware)
                )
            }
        }
        return dp[m][n]
    }

    /**
     * Ranks [candidates] for the typed [input] ascending by weighted distance,
     * breaking ties by frequency descending (higher-frequency word wins).
     */
    fun rank(input: String, candidates: List<FuzzyResult>): List<FuzzyResult> {
        return candidates.sortedWith(
            compareBy<FuzzyResult> { score(input, it.word) }
                .thenByDescending { it.frequency },
        )
    }
}