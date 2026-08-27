package com.codekeyboard

/**
 * PhraseCompleter — multi-word chip suggestions from the pack's phrase terminals
 * (ADR-012 task I).
 *
 * The CKLM pack stores phrase terminals as trie nodes with `phrase_score > 0`
 * (see LanguagePack.phrases). Given the trailing committed context, this finds
 * multi-word continuations (e.g. context "i like" → chip "to the" / "it" from a
 * stored phrase "i like to the") and returns them as full-phrase strings.
 *
 * The IME can render these as suggestion chips that insert multiple words at
 * once (vs a single next-word suggestion).
 */
class PhraseCompleter(private val pack: LanguagePack) {

    /**
     * Returns up to [n] multi-word continuations for the trailing committed
     * [context] words (oldest-to-newest). Each result is the phrase EXTENSION
     * beyond the context — i.e. the words the user would gain by accepting the
     * chip — plus the full phrase and its score.
     *
     * @param context trailing previously-committed words.
     * @param maxExtension max words a phrase may extend beyond the context.
     * @return list of (extensionWords, fullPhrase, score), ranked by score desc.
     */
    fun complete(context: List<String>, maxExtension: Int = 3, n: Int = 3):
            List<Triple<String, String, Float>> {
        if (context.isEmpty()) return emptyList()

        // Map context words to IDs; skip if any is out-of-vocab (no path exists).
        val contextIds = context.map { pack.id(it.lowercase()) }
        if (contextIds.any { it < 0 }) return emptyList()

        val phrases = pack.phrases(contextIds, maxExtension = maxExtension)
        if (phrases.isEmpty()) return emptyList()

        // Each phrase path = contextIds + extensionIds. Build the extension
        // word string and full phrase string from the pack vocab.
        return phrases
            .sortedByDescending { it.second }
            .take(n)
            .mapNotNull { (path, score) ->
                val extension = path.drop(contextIds.size)
                if (extension.isEmpty()) return@mapNotNull null
                val extWords = extension.map { pack.word(it) }
                val fullWords = path.map { pack.word(it) }
                Triple(extWords.joinToString(" "), fullWords.joinToString(" "), score)
            }
    }
}