package com.codekeyboard

/**
 * Minimal interface for prefix-based word lookup.
 * Implemented by both [Trie] (legacy) and [WordDictionary] (pack-backed).
 */
interface PrefixDictionary {
    fun suggest(prefix: String, max: Int): List<String>
    fun has(word: String): Boolean
}

/**
 * Interface for bigram next-word prediction with context.
 * Implemented by [BigramModel] (legacy) and [PackBackedBigramModel] (pack-backed).
 */
interface BigramProvider {
    fun nextWords(prevWord: String, prefix: String, n: Int): List<String>
}

interface SuggestionStrategy {
    fun suggest(prefix: String, k: Int, context: String = ""): List<String>
}

class MergedSuggestionStrategy(
    private val userAdapter: UserTrieAdapter,
    private val baseAdapter: TrieAdapter<Int>,
    private val baseDict: PrefixDictionary,
) : SuggestionStrategy {

    override fun suggest(prefix: String, k: Int, context: String): List<String> {
        val exact = exactSuggest(prefix, k)
        if (exact.size >= k) return exact

        val threshold = FuzzyThreshold.forLength(prefix.length)
        if (threshold == 0) return exact

        val fuzzy = fuzzyFill(prefix, threshold, k - exact.size)
        val exactSet = exact.toSet()
        return exact + fuzzy.filter { it !in exactSet }
    }

    private fun exactSuggest(prefix: String, k: Int): List<String> {
        val userResults = userAdapter.suggest(prefix, k)
        val baseResults = baseDict.suggest(prefix, k)
        val userWords = userResults.toSet()
        return (userResults + baseResults.filter { it !in userWords }).take(k)
    }

    private fun fuzzyFill(word: String, threshold: Int, limit: Int): List<String> {
        // Collect ALL words within threshold from both tries — BEVA's edit-vector
        // pruning cuts dead subtrees so this is efficient. Early-exit on count
        // produces wrong results because DFS order is alphabetical, not by quality.
        val userFuzzy = BevaTrieSearch.search(userAdapter, word, threshold, Int.MAX_VALUE)
        val baseFuzzy = BevaTrieSearch.search(baseAdapter, word, threshold, Int.MAX_VALUE)
        val userWords = userFuzzy.map { it.word }.toSet()
        return (userFuzzy + baseFuzzy.filter { it.word !in userWords })
            .sortedWith(compareBy(
                { it.editDistance },
                { -commonPrefixLength(word, it.word) },
                { -it.frequency },
            ))
            .map { it.word }
            .take(limit)
    }

    // Words that share a longer common prefix with the query are ranked higher
    // within the same edit distance bucket (handles base trie with no frequency).
    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }
}

class BaseSuggestionStrategy(private val baseDict: PrefixDictionary) : SuggestionStrategy {
    override fun suggest(prefix: String, k: Int, context: String): List<String> =
        baseDict.suggest(prefix, k)
}

// Promotes bigram candidates to the top when context (previous word) is provided.
class BigramAwareSuggestionStrategy(
    private val base: SuggestionStrategy,
    private val bigram: BigramProvider,
) : SuggestionStrategy {

    override fun suggest(prefix: String, k: Int, context: String): List<String> {
        val baseResults = base.suggest(prefix, k + 5)
        if (context.isEmpty()) return baseResults.take(k)
        val bigramMatches = bigram.nextWords(context, prefix, k)
        val promoted = bigramMatches + baseResults.filter { it !in bigramMatches }
        return promoted.take(k)
    }
}
