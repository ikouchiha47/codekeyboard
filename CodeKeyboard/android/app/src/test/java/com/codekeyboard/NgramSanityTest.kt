package com.codekeyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Test-only stand-in for a future Bigram.kt (not requested this session) —
// exists here purely to exercise Ngram's cascade with 2 real orders.
private class TestBigram(dataSource: NgramDataSource) : AbstractNgram(order = 2, dataSource = dataSource)

private fun jsonFile(content: String): File =
    File.createTempFile("ngram_test", ".json").apply { writeText(content) }

class NgramSanityTest {

    @Test fun `single tier is used unchanged when only one tier has enough context or data`() {
        val trigramJson = jsonFile("""{"i want": {"followers": [["to", 0.9], ["a", 0.5]], "support": 100}}""")
        val bigramJson = jsonFile(
            """{"want": {"followers": [["some", 0.7]], "support": 5},
                "coffee": {"followers": [["please", 0.6]], "support": 50}}"""
        )
        val trigram = Trigram(JsonNgramDataSource(trigramJson))
        val bigram = TestBigram(JsonNgramDataSource(bigramJson))
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
        val trigramJson = jsonFile("""{"i want": {"followers": [["to", 0.9], ["a", 0.5]], "support": 100}}""")
        val bigramJson = jsonFile("""{"want": {"followers": [["some", 0.7]], "support": 5}}""")
        val trigram = Trigram(JsonNgramDataSource(trigramJson))
        val bigram = TestBigram(JsonNgramDataSource(bigramJson))
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
        val trigramJson = jsonFile("""{"let me": {"followers": [["tell", 1.0]], "support": 10}}""")
        val bigramJson = jsonFile("""{"me": {"followers": [["know", 1.0]], "support": 4000}}""")
        val trigram = Trigram(JsonNgramDataSource(trigramJson))
        val bigram = TestBigram(JsonNgramDataSource(bigramJson))
        val ngram = Ngram(listOf(trigram, bigram))

        val top = ngram.nextWords(listOf("let", "me"), n = 1)
        assertEquals(listOf("know"), top)
    }

    @Test fun `malformed or missing asset degrades to empty and cascade falls through`() {
        val malformedJson = File.createTempFile("malformed_trigram", ".json")
        malformedJson.writeText("this is not json {")

        val missingFile = File.createTempFile("missing_trigram", ".json")
        missingFile.delete()

        val malformedTrigram = Trigram(JsonNgramDataSource(malformedJson))
        val missingTrigram = Trigram(JsonNgramDataSource(missingFile))

        // No crash on a malformed asset — tier degrades to "no data".
        assertEquals(emptyList<String>(), malformedTrigram.nextWords("i", "want"))
        assertEquals(0, malformedTrigram.support("i", "want"))
        // No crash on a missing asset — tier degrades to "no data".
        assertEquals(emptyList<String>(), missingTrigram.nextWords("i", "want"))

        // Cascade falls through a degraded trigram tier to the bigram tier.
        val bigramJson = jsonFile("""{"want": {"followers": [["some", 0.7]], "support": 5}}""")
        val ngram = Ngram(listOf(malformedTrigram, TestBigram(JsonNgramDataSource(bigramJson))))
        assertEquals(listOf("some"), ngram.nextWords(listOf("want")))
    }

    @Test fun `maxContextNeeded derives from highest order in list`() {
        val trigram = Trigram(JsonNgramDataSource(jsonFile("{}")))
        val bigram = TestBigram(JsonNgramDataSource(jsonFile("{}")))
        assertEquals(2, Ngram(listOf(trigram, bigram)).maxContextNeeded)
        assertEquals(1, Ngram(listOf(bigram)).maxContextNeeded)
        assertEquals(0, Ngram(emptyList()).maxContextNeeded)
    }

    @Test fun `BigramModelAdapter delegates nextWords and support to the real BigramModel`() {
        val seedJson = File.createTempFile("adapter_seed", ".json")
        seedJson.writeText("""{"want": [["some", 0.7], ["more", 0.5]]}""")
        val userFile = File.createTempFile("adapter_user", ".json").apply { writeText("{}") }
        val bigram = BigramModel(seedJson, userFile).also { it.load() }
        val adapter = BigramModelAdapter(bigram)

        assertEquals(2, adapter.order)
        assertEquals(listOf("some", "more"), adapter.nextWords("want"))
        assertEquals(listOf("some"), adapter.nextWords("want", prefix = "so"))
        assertEquals(emptyList<String>(), adapter.nextWords())

        // support() defaults to 0 before loadSupport() is called — degrades
        // gracefully rather than throwing, matching AbstractNgram's pattern.
        assertEquals(0, adapter.support("want"))
        assertEquals(0, adapter.support())

        val supportFile = File.createTempFile("adapter_support", ".json")
        supportFile.writeText("""{"want": 12345}""")
        bigram.loadSupport(supportFile)
        assertEquals(12345, adapter.support("want"))
        assertEquals(0, adapter.support("unknown"))
    }
}
