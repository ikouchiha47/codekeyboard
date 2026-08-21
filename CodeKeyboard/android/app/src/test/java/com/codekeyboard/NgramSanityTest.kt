package com.codekeyboard

import org.junit.Assert.assertEquals
import org.junit.Test

// Test-only fake NgramModel implementations — NgramSanityTest exercises the
// Ngram cascade's blending logic (ADR-008 task L), not any specific data
// source. The JSON-backed Trigram/BigramModelAdapter were removed (ADR-010
// tasks L/M); the cascade itself is still the production path.
private class FakeNgram(
    override val order: Int,
    private val data: Map<List<String>, Pair<List<String>, Int>>,
) : NgramModel {
    override fun nextWords(vararg context: String, prefix: String, n: Int): List<String> {
        val key = context.toList()
        val (words, _) = data[key] ?: return emptyList()
        val pfx = prefix.lowercase()
        return words.filter { pfx.isEmpty() || it.startsWith(pfx) }.take(n)
    }

    override fun support(vararg context: String): Int =
        data[context.toList()]?.second ?: 0
}

class NgramSanityTest {

    @Test fun `single tier is used unchanged when only one tier has enough context or data`() {
        val trigram = FakeNgram(3, mapOf(
            listOf("i", "want") to (listOf("to", "a") to 100),
        ))
        val bigram = FakeNgram(2, mapOf(
            listOf("want") to (listOf("some") to 5),
            listOf("coffee") to (listOf("please") to 50),
        ))
        val ngram = Ngram(listOf(trigram, bigram))

        // Only 1 word of context: trigram needs 2, doesn't qualify — bigram alone answers.
        assertEquals(listOf("some"), ngram.nextWords(listOf("want")))

        // 2 words of context, but trigram has no data for "my coffee" — bigram alone answers.
        assertEquals(listOf("please"), ngram.nextWords(listOf("my", "coffee")))

        // No context at all: nothing qualifies.
        assertEquals(emptyList<String>(), ngram.nextWords(emptyList()))

        println("All single-tier-fallback assertions passed")
    }

    @Test fun `blends both tiers weighted by support instead of one overriding the other`() {
        // Trigram heavily outweighs bigram in support (100 vs 5) — this is the
        // ADR-008 task L fix: previously trigram would have overridden bigram
        // unconditionally just for answering first, regardless of support.
        val trigram = FakeNgram(3, mapOf(
            listOf("i", "want") to (listOf("to", "a") to 100),
        ))
        val bigram = FakeNgram(2, mapOf(
            listOf("want") to (listOf("some") to 5),
        ))
        val ngram = Ngram(listOf(trigram, bigram))

        // Limited to top-2: the heavily-favored trigram tier's words win the top spots.
        assertEquals(listOf("to", "a"), ngram.nextWords(listOf("i", "want"), n = 2))

        // Unlimited: the minority (bigram) tier's word still appears in the blend,
        // proving both tiers contributed rather than one being discarded outright.
        val full = ngram.nextWords(listOf("i", "want"), n = 5)
        assertEquals(listOf("to", "a", "some"), full)

        println("Blend-weighted-by-support assertion passed")
    }

    @Test fun `a low-support higher-order tier no longer blindly overrides a well-supported lower tier`() {
        // The exact failure mode ADR-008 measured: a thin trigram context
        // (support=10) previously overrode a strong bigram context (support=4000)
        // just by answering first. Now the bigram's answer should dominate.
        val trigram = FakeNgram(3, mapOf(
            listOf("let", "me") to (listOf("tell") to 10),
        ))
        val bigram = FakeNgram(2, mapOf(
            listOf("me") to (listOf("know") to 4000),
        ))
        val ngram = Ngram(listOf(trigram, bigram))

        val top = ngram.nextWords(listOf("let", "me"), n = 1)
        assertEquals(listOf("know"), top)
    }

    @Test fun `tier with no data degrades to empty and cascade falls through`() {
        // A tier that returns empty (no data for the context) must not block
        // the cascade — the lower tier answers.
        val emptyTrigram = FakeNgram(3, emptyMap())
        val bigram = FakeNgram(2, mapOf(
            listOf("want") to (listOf("some") to 5),
        ))
        val ngram = Ngram(listOf(emptyTrigram, bigram))
        assertEquals(listOf("some"), ngram.nextWords(listOf("want")))
    }

    @Test fun `maxContextNeeded derives from highest order in list`() {
        val trigram = FakeNgram(3, emptyMap())
        val bigram = FakeNgram(2, emptyMap())
        assertEquals(2, Ngram(listOf(trigram, bigram)).maxContextNeeded)
        assertEquals(1, Ngram(listOf(bigram)).maxContextNeeded)
        assertEquals(0, Ngram(emptyList()).maxContextNeeded)
    }
}