package com.codekeyboard

/**
 * PackBackedBigramModel — combines pack-backed static bigram seed with user-learned decay layer.
 *
 * The static seed (cold-start bigram predictions) comes from the CKLM pack via
 * [PackNgramModel(order=2)]. The user-learned layer (personalization, recency scoring,
 * persistence) comes from [BigramModel] and is preserved.
 *
 * This replaces the old BigramModel's static seed (from bigrams.json) with the
 * pack's context-trie follower lists, while keeping the user-learned decay layer intact.
 */
class PackBackedBigramModel(
    private val packNgram: PackNgramModel,  // order=2, pack-backed static seed
    private val userBigram: BigramModel,    // user-learned decay layer
) : BigramProvider {

    /** Returns top N next-word candidates given the previous committed word.
     *  Combines pack-backed static seed with user-learned layer (same formula as BigramModel).
     *  If prefix is non-empty, filters to candidates starting with prefix. */
    override fun nextWords(prevWord: String, prefix: String, n: Int): List<String> {
        val prev = prevWord.lowercase()
        val pfx = prefix.lowercase()

        val scores = mutableMapOf<String, Float>()

        // Static seed from pack (weight 0.7). Uses the pack's REAL decoded
        // follower scores. The static LM dominates so sensible suggestions
        // (the/to/that) rank high; the user layer only boosts words the user
        // has genuinely learned (see MIN_USER_DEE threshold in BigramModel).
        packNgram.nextWordsWithScores(prev, prefix = pfx, n = n * 2).forEach { (word, score) ->
            scores[word] = (scores[word] ?: 0f) + 0.7f * score
        }

        // User-learned layer from BigramModel (weight 0.3, Gboard-style cache
        // weight). Uses the REAL formula_p scores, gated by MIN_USER_DEE so a
        // single observation doesn't outrank the static LM.
        userBigram.userLayerScores(prev, prefix = pfx, n = n * 2).forEach { (word, score) ->
            scores[word] = (scores[word] ?: 0f) + 0.3f * score
        }

        return scores.entries
            .filter { pfx.isEmpty() || it.key.startsWith(pfx) }
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }
    }

    /** Records the transition prevWord → nextWord in the user-learned layer. */
    fun recordTransition(prevWord: String, nextWord: String) {
        userBigram.recordTransition(prevWord, nextWord)
    }

    /** Returns the support (confidence) for the given context from the pack. */
    fun support(prevWord: String): Int = packNgram.support(prevWord)

    /** Loads the user-learned layer from disk. */
    fun loadUserLayer() {
        userBigram.loadUserLayer()
    }

    /** Persists the user-learned layer to disk. */
    fun persistUserLayer() {
        userBigram.persistUserLayer()
    }
}