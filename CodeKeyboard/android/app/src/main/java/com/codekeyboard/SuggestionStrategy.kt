package com.codekeyboard

interface SuggestionStrategy {
    fun suggest(prefix: String, k: Int): List<String>
}

class MergedSuggestionStrategy(
    private val userTrie: UserTrie,
    private val baseTrie: Trie,
) : SuggestionStrategy {
    override fun suggest(prefix: String, k: Int): List<String> {
        val userResults = userTrie.suggest(prefix, k)
        val baseResults = baseTrie.suggest(prefix, k)
        val userWords = userResults.map { it.word }.toSet()
        return (userResults.map { it.word } + baseResults.filter { it !in userWords }).take(k)
    }
}

class BaseSuggestionStrategy(private val baseTrie: Trie) : SuggestionStrategy {
    override fun suggest(prefix: String, k: Int): List<String> = baseTrie.suggest(prefix, k)
}
