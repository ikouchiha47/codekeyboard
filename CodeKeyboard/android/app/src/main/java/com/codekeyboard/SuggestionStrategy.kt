package com.codekeyboard

interface SuggestionStrategy {
    fun suggest(prefix: String, k: Int): List<String>
}

class MergedSuggestionStrategy(
    private val userTrie: UserTrie,
    private val baseTrie: Trie,
) : SuggestionStrategy {

    private val userAdapter = UserTrieAdapter(userTrie)
    private val baseAdapter = BaseTrieAdapter(baseTrie)

    override fun suggest(prefix: String, k: Int): List<String> {
        val exact = exactSuggest(prefix, k)
        if (exact.size >= k) return exact

        val threshold = FuzzyThreshold.forLength(prefix.length)
        if (threshold == 0) return exact

        val fuzzy = fuzzyFill(prefix, threshold, k - exact.size)
        val exactSet = exact.toSet()
        return exact + fuzzy.filter { it !in exactSet }
    }

    private fun exactSuggest(prefix: String, k: Int): List<String> {
        val userResults = userTrie.suggest(prefix, k)
        val baseResults = baseTrie.suggest(prefix, k)
        val userWords = userResults.map { it.word }.toSet()
        return (userResults.map { it.word } + baseResults.filter { it !in userWords }).take(k)
    }

    private fun fuzzyFill(word: String, threshold: Int, limit: Int): List<String> {
        val userFuzzy = BevaTrieSearch.search(userAdapter, word, threshold, limit)
        val baseFuzzy = BevaTrieSearch.search(baseAdapter, word, threshold, limit)
        val userWords = userFuzzy.map { it.word }.toSet()
        return (userFuzzy + baseFuzzy.filter { it.word !in userWords })
            .sortedWith(compareBy({ it.editDistance }, { -it.frequency }))
            .map { it.word }
            .take(limit)
    }
}

class BaseSuggestionStrategy(private val baseTrie: Trie) : SuggestionStrategy {
    override fun suggest(prefix: String, k: Int): List<String> = baseTrie.suggest(prefix, k)
}
