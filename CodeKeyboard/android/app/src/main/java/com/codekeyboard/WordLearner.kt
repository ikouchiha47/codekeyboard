package com.codekeyboard

fun interface BaseDictionary {
    fun isKnownWord(word: String): Boolean
}

class WordLearner(
    private val userTrie: UserTrie,
    private val dictionary: BaseDictionary,
) {
    // Called when space/enter/punctuation commits the raw composing buffer.
    // Only learn the word if it already exists in the base dictionary — raw
    // buffer commits are ambiguous (could be a typo or a partial word).
    fun learnFromFlush(word: String) {
        if (!isLearnable(word)) return
        if (!dictionary.isKnownWord(word)) return
        userTrie.insert(word)
    }

    // Called when the user explicitly taps a suggestion. Always learn it —
    // an explicit tap is an unambiguous signal of intent.
    fun learnFromTap(word: String) {
        if (!isLearnable(word)) return
        userTrie.insert(word)
    }

    private fun isLearnable(word: String) =
        word.length > 1 && !word.startsWith(";")
}
