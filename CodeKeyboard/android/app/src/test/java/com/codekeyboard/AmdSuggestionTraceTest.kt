package com.codekeyboard

import org.junit.Test

/**
 * Traces the full suggest("amd") execution path to show exactly why "and"
 * does or doesn't appear in suggestions.
 */
class AmdSuggestionTraceTest {

    private val userTrie = UserTrie().also {
        repeat(10) { _ -> it.insert("and") }
        repeat(3)  { _ -> it.insert("amp") }
        repeat(1)  { _ -> it.insert("amd") }
    }
    private val userAdapter = UserTrieAdapter(userTrie)

    @Test fun `trace suggest amd step by step`() {
        val query = "amd"

        println("=== suggest(\"$query\") trace ===")

        // Step 1: exact prefix match
        val exactUser = userTrie.suggest(query, 5)
        println("[1] userTrie.suggest(\"$query\") exact prefix: ${exactUser.map { "${it.word}(freq=${it.frequency})" }}")

        // Step 2: threshold decision — this is the gate
        val threshold = FuzzyThreshold.forLength(query.length)
        println("[2] FuzzyThreshold.forLength(len=${query.length}) = $threshold")

        if (threshold == 0) {
            println("[3] threshold == 0 → MergedSuggestionStrategy returns exact only, fuzzy SKIPPED")
            println("    final suggestions for \"$query\": ${exactUser.map { it.word }}")
            println("    => \"and\" does NOT appear")
        } else {
            val fuzzy = BevaTrieSearch.search(userAdapter, query, threshold, Int.MAX_VALUE)
                .sortedWith(compareBy({ it.editDistance }, { -it.frequency }))
            println("[3] BevaTrieSearch(\"$query\", threshold=$threshold): ${fuzzy.map { "${it.word}(d=${it.editDistance})" }}")
        }

        // Show what WOULD happen at threshold=1
        println()
        println("=== if threshold were 1 ===")
        val fuzzyAt1 = BevaTrieSearch.search(userAdapter, query, 1, Int.MAX_VALUE)
            .sortedWith(compareBy({ it.editDistance }, { -it.frequency }))
        println("BevaTrieSearch(\"$query\", threshold=1) = ${fuzzyAt1.map { "${it.word}(d=${it.editDistance},freq=${it.frequency})" }}")

        println()
        println("levenshtein(\"amd\", \"and\") = ${levenshtein("amd", "and")}")
        println("levenshtein(\"amd\", \"amp\") = ${levenshtein("amd", "amp")}")
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length) {
            dp[i][j] = if (a[i - 1] == b[j - 1]) dp[i - 1][j - 1]
                       else minOf(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1]) + 1
        }
        return dp[a.length][b.length]
    }

    @Test fun `trace suggest ai step by step`() {
        val query = "ai"
        println("=== suggest(\"$query\") trace ===")

        val exactUser = userTrie.suggest(query, 5)
        println("[1] userTrie.suggest(\"$query\") exact prefix: ${exactUser.map { "${it.word}(freq=${it.frequency})" }}")

        val threshold = FuzzyThreshold.forLength(query.length)
        println("[2] FuzzyThreshold.forLength(len=${query.length}) = $threshold")
        println("    => 'aid', 'aim' appear via EXACT PREFIX match, not fuzzy")
    }
}
