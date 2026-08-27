package com.codekeyboard

/**
 * PackNgramModel — NgramModel implementation backed by a CKLM language pack.
 *
 * Uses LanguagePack's context-trie follower lists (already score-ranked) and
 * the new per-context support field for truthful confidence signals.
 *
 * - order = 2 (bigram): uses 1-word context (unigram context in pack terms)
 * - order = 3 (trigram): uses 2-word context (bigram context in pack terms)
 * - order = 4 (4-gram): uses 3-word context (trigram context in pack terms)
 *
 * The pack's context-trie depth semantics:
 * - depth 0 (root) = unigram context (no preceding words)
 * - depth 1 = bigram context (1 preceding word)
 * - depth 2 = trigram context (2 preceding words)
 * - depth 3 = 4-gram context (3 preceding words)
 *
 * So for order=2 we use depth-1 nodes, order=3 depth-2, order=4 depth-3.
 */
class PackNgramModel(
    private val pack: LanguagePack,
    override val order: Int
) : NgramModel {

    init {
        require(order == 2 || order == 3 || order == 4) {
            "PackNgramModel only supports order 2 (bigram), 3 (trigram), or 4 (4-gram), got $order"
        }
    }

    /**
     * Returns top N next-word candidates for the given context.
     *
     * Takes the last (order - 1) context words (oldest-to-newest), maps them
     * to word IDs via the pack's vocabulary, and retrieves the ranked follower
     * list from the context-trie. If a prefix is provided, filters to words
     * starting with that prefix.
     *
     * @param context trailing previously-committed words, oldest-to-newest.
     *   Only the last (order - 1) are used.
     * @param prefix optional prefix to filter candidates (e.g. "hel" → "hello", "help")
     * @param n max number of candidates to return
     * @return list of words (already score-descending from the pack)
     */
    override fun nextWords(vararg context: String, prefix: String, n: Int): List<String> {
        if (n <= 0) return emptyList()

        val needed = order - 1
        if (context.size < needed) return emptyList()

        // Take the last (order - 1) words as the context
        val contextWords = context.takeLast(needed)

        // Map context words to word IDs
        val contextIds = contextWords.map { word ->
            pack.id(word.lowercase())
        }

        // If any word is OOV, return empty
        if (contextIds.any { it < 0 }) return emptyList()

        // Get ranked followers from the pack
        val followers = pack.followers(contextIds)
        if (followers.isEmpty()) return emptyList()

        // Filter by prefix if provided
        val pfx = prefix.lowercase()
        val filtered = if (pfx.isEmpty()) {
            followers
        } else {
            followers.filter { (wordId, _) ->
                val word = pack.word(wordId)
                word.startsWith(pfx)
            }
        }

        // Return top n words (already score-descending)
        return filtered.take(n).map { (wordId, _) -> pack.word(wordId) }
    }

    /**
     * Returns top N next-word candidates with their decoded scores.
     *
     * Same as [nextWords] but preserves the pack's real follower scores
     * (decoded from the byte-log-prob), so callers can blend them with
     * other tiers (e.g. the user-learned layer) using actual probabilities
     * rather than position heuristics.
     *
     * @return list of (word, decodedScore) in score-descending order
     */
    fun nextWordsWithScores(vararg context: String, prefix: String = "", n: Int = 5): List<Pair<String, Float>> {
        if (n <= 0) return emptyList()

        val needed = order - 1
        if (context.size < needed) return emptyList()

        val contextWords = context.takeLast(needed)
        val contextIds = contextWords.map { word -> pack.id(word.lowercase()) }
        if (contextIds.any { it < 0 }) return emptyList()

        val followers = pack.followers(contextIds)
        if (followers.isEmpty()) return emptyList()

        val pfx = prefix.lowercase()
        val filtered = if (pfx.isEmpty()) {
            followers
        } else {
            followers.filter { (wordId, _) -> pack.word(wordId).startsWith(pfx) }
        }

        return filtered.take(n).map { (wordId, score) -> pack.word(wordId) to score }
    }

    /**
     * Returns the total observed count (support) for the given context.
     *
     * This is the confidence signal used by [Ngram]'s cascade to weight
     * different tiers against each other. The pack stores this per-context
     * in the context-trie node's support field.
     *
     * @param context trailing previously-committed words, oldest-to-newest.
     *   Only the last (order - 1) are used.
     * @return support count, or 0 if context is unknown or OOV
     */
    override fun support(vararg context: String): Int {
        val needed = order - 1
        if (context.size < needed) return 0

        val contextWords = context.takeLast(needed)
        val contextIds = contextWords.map { word ->
            pack.id(word.lowercase())
        }

        if (contextIds.any { it < 0 }) return 0

        return pack.support(contextIds)
    }
}