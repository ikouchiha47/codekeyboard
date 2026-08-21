package com.codekeyboard

/**
 * Adapter-pattern bridge (ADR-008) that lets the existing, unmodified
 * [BigramModel] participate in the [Ngram] cascade as its order-2 tier.
 *
 * [BigramModel] predates the [NgramModel] interface and — per the project's
 * ADR immutability convention — must not be modified to implement it. This
 * adapter implements the interface instead and delegates straight through to
 * the real instance, so the cascade's bigram tier is byte-for-byte the same
 * personalized behavior as today (seed + user-learned decay layer), not a
 * second, personalization-blind reader of bigrams.json.
 */
class BigramModelAdapter(private val bigramModel: BigramModel) : NgramModel {

    override val order: Int = 2

    override fun nextWords(vararg context: String, prefix: String, n: Int): List<String> {
        val prevWord = context.lastOrNull() ?: return emptyList()
        return bigramModel.nextWords(prevWord, prefix = prefix, n = n)
    }

    // Delegates to BigramModel's new additive support() method (ADR-008 task L)
    // — 0 if loadSupport() was never called, which degrades gracefully in
    // Ngram's blend (this tier just contributes no weight, not an error).
    override fun support(vararg context: String): Int {
        val prevWord = context.lastOrNull() ?: return 0
        return bigramModel.support(prevWord)
    }
}