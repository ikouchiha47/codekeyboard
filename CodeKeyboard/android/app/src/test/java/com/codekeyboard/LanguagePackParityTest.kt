package com.codekeyboard

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.random.Random

/**
 * Parity test for LanguagePack CKLM v1 reader.
 * Verifies that the reader's followers(context) matches the quantized JSON
 * for a large sample of contexts streamed from the source trigram JSON.
 */
class LanguagePackParityTest {

    companion object {
        private lateinit var pack: LanguagePack
        private val PACK_FILE = File("/tmp/en.cklm")
        private val JSON_FILE = File("/var/folders/2_/4mhmjvl14tbdpwyskd146vmc0000gn/T/opencode/cklm_verify/swiftkey_tri_cap10.json")

        // Compiler's quantization constants (from compile_cklm.py quantize_log10)
        private var log10Min: Float = 0f
        private var log10Max: Float = 0f

        @BeforeClass
        @JvmStatic
        fun loadPack() {
            assumeTrue("Test file /tmp/en.cklm not found", PACK_FILE.exists())
            assumeTrue("Source JSON not found", JSON_FILE.exists())
            pack = LanguagePack.open(PACK_FILE)
            log10Min = pack.scoreRange.first
            log10Max = pack.scoreRange.second
            println("Loaded pack: scoreRange = [$log10Min, $log10Max]")
        }

        /**
         * Replicates the compiler's quantize_log10 exactly.
         * Returns the u8 byte value.
         */
        fun quantizeLog10(score: Float): Int {
            if (score <= 0f) return 0
            val log10Score = log10(score.toDouble()).toFloat()
            if (log10Max == log10Min) return 255
            val byteVal = Math.round((log10Score - log10Min) / (log10Max - log10Min) * 255f)
            return byteVal.coerceIn(0, 255)
        }

        /**
         * Replicates the reader's decodeScore exactly.
         * Returns the decoded float score from a u8 byte.
         */
        fun decodeScore(byte: Int): Float {
            if (log10Max == log10Min) return 1.0f
            val normalized = byte / 255.0
            val log10Score = log10Min + normalized * (log10Max - log10Min)
            return (10.0.pow(log10Score.toDouble())).toFloat()
        }

        /**
         * Quantizes a JSON follower list and returns (wordId, decodedScore) pairs
         * in the same order as the reader would return them (score-descending, preserving original order for ties).
         */
        fun quantizeJsonFollowers(jsonFollowers: List<List<Any>>): List<Pair<Int, Float>> {
            val quantized = mutableListOf<Quad<Int, Float, Int, Int>>() // (wordId, decodedScore, byteVal, originalIndex)
            for ((idx, follower) in jsonFollowers.withIndex()) {
                val word = follower[0] as String
                val scoreFloat = (follower[1] as Number).toFloat()
                val wordId = pack.id(word)
                if (wordId >= 0) {
                    val byteVal = quantizeLog10(scoreFloat)
                    val decodedScore = decodeScore(byteVal)
                    quantized.add(Quad(wordId, decodedScore, byteVal, idx))
                }
            }
            // Sort by decoded score descending, then by original index (preserves JSON order for ties)
            quantized.sortWith(compareByDescending<Quad<Int, Float, Int, Int>> { it.second }
                .thenBy { it.fourth })
            return quantized.map { it.first to it.second }
        }

        // Helper class for 4-tuple
        data class Quad<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
    }

    @Test fun `parity test reader followers match quantized JSON for large sampled context set`() {
        // Known high-support contexts to always include (anchor points)
        val anchorContexts = setOf(
            "of the", "in the", "to the", "for the", "on the",
            "to be", "at the", "and the", "in a", "with the",
            "is the", "that the", "as the", "by the", "from the"
        )

        // Stream the JSON and collect sampled contexts using reservoir sampling
        val sampledContexts = streamAndSampleContexts(JSON_FILE, anchorContexts, targetSampleSize = 400)

        println("Sampled ${sampledContexts.size} contexts for parity testing")

        var exactMatches = 0
        var totalCompared = 0
        var oovSkipped = 0
        var missingInReader = 0
        var mismatches = mutableListOf<String>()

        for ((ctxKey, jsonFollowers, support) in sampledContexts) {
            val words = ctxKey.split(" ")
            if (words.size != 2) continue // Only test 2-word (trigram) contexts

            val wordIds = words.map { pack.id(it) }
            if (wordIds.any { it < 0 }) {
                oovSkipped++
                continue
            }

            // Get reader's followers
            val readerFollowers = pack.followers(wordIds)
            if (readerFollowers.isEmpty()) {
                missingInReader++
                continue
            }

            // Quantize JSON followers using compiler's formula
            val quantizedFollowers = quantizeJsonFollowers(jsonFollowers)

            // Compare: wordIds must match in order, scores must match within tolerance
            val minLen = minOf(readerFollowers.size, quantizedFollowers.size)
            var match = true
            var mismatchDetail = ""

            for (i in 0 until minLen) {
                val (rWordId, rScore) = readerFollowers[i]
                val (qWordId, qScore) = quantizedFollowers[i]
                if (rWordId != qWordId) {
                    match = false
                    mismatchDetail = "wordId mismatch at index $i: reader=$rWordId (${pack.word(rWordId)}), quantized=$qWordId (${pack.word(qWordId)})"
                    break
                }
                val scoreDiff = abs(rScore - qScore)
                if (scoreDiff > 1e-4f) {
                    match = false
                    mismatchDetail = "score mismatch at index $i: reader=$rScore, quantized=$qScore, diff=$scoreDiff"
                    break
                }
            }

            if (match && readerFollowers.size == quantizedFollowers.size) {
                exactMatches++
            } else {
                if (match) {
                    mismatchDetail = "length mismatch: reader=${readerFollowers.size}, quantized=${quantizedFollowers.size}"
                }
                mismatches.add("$ctxKey (support=$support): $mismatchDetail")
            }
            totalCompared++
        }

        println("Parity test results:")
        println("  Total contexts sampled: ${sampledContexts.size}")
        println("  Total contexts compared: $totalCompared")
        println("  Exact matches: $exactMatches")
        println("  OOV skipped: $oovSkipped")
        println("  Missing in reader: $missingInReader")
        println("  Mismatches: ${mismatches.size}")

        if (mismatches.isNotEmpty()) {
            println("  Mismatches (first 20):")
            mismatches.take(20).forEach { println("    $it") }
        }

        val matchRate = if (totalCompared > 0) exactMatches.toDouble() / totalCompared * 100 else 0.0
        println("  Match rate: ${"%.2f".format(matchRate)}%")

        // We expect high match rate but allow some mismatches due to:
        // - Compiler pruning (vocab limited to 65535, unigram followers limited to 255)
        // - Float rounding differences
        // - JSON may have more followers than stored (reader returns all stored)
        assertTrue("Match rate should be >= 90%, got ${"%.2f".format(matchRate)}%", matchRate >= 90.0)
    }

    /**
     * Streams the large JSON file and samples contexts using reservoir sampling.
     * Uses Jackson's streaming parser to avoid loading the entire 220 MB file into memory.
     *
     * Strategy:
     * - Single pass through the file
     * - Reservoir sampling for even spatial distribution
     * - Always include anchor contexts (known high-support ones)
     * - Maintains bounded memory (only stores targetSampleSize contexts)
     */
    private fun streamAndSampleContexts(
        jsonFile: File,
        anchorContexts: Set<String>,
        targetSampleSize: Int
    ): List<Triple<String, List<List<Any>>, Int>> {
        val factory = JsonFactory()
        val reservoir = mutableListOf<Triple<String, List<List<Any>>, Int>>()
        val anchorFound = mutableSetOf<String>()
        var keyCount = 0L
        val random = Random(42) // Fixed seed for reproducibility

        FileInputStream(jsonFile).use { inputStream ->
            val parser = factory.createParser(inputStream)
            parser.configure(JsonParser.Feature.ALLOW_UNQUOTED_FIELD_NAMES, true)
            parser.configure(JsonParser.Feature.ALLOW_SINGLE_QUOTES, true)

            // Expect START_OBJECT
            require(parser.nextToken() == JsonToken.START_OBJECT) { "Expected START_OBJECT" }

            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                val ctxKey = parser.currentName
                keyCount++

                // Parse the value object: {"followers": [...], "support": N}
                require(parser.nextToken() == JsonToken.START_OBJECT) { "Expected START_OBJECT for value" }

                var followers: List<List<Any>>? = null
                var support = 0

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    val fieldName = parser.currentName
                    parser.nextToken() // move to value

                    when (fieldName) {
                        "followers" -> {
                            followers = parseFollowersArray(parser)
                        }
                        "support" -> {
                            support = parser.intValue
                        }
                    }
                }

                val isAnchor = ctxKey in anchorContexts
                if (isAnchor) {
                    anchorFound.add(ctxKey)
                }

                // Always include anchors
                if (isAnchor && followers != null) {
                    reservoir.add(Triple(ctxKey, followers, support))
                }
                // Reservoir sampling for non-anchors
                else if (followers != null) {
                    if (reservoir.size < targetSampleSize) {
                        reservoir.add(Triple(ctxKey, followers, support))
                    } else {
                        // Replace with probability targetSampleSize / keyCount
                        val replaceIdx = random.nextInt(keyCount.toInt())
                        if (replaceIdx < targetSampleSize) {
                            reservoir[replaceIdx] = Triple(ctxKey, followers, support)
                        }
                    }
                }
            }

            parser.close()
        }

        println("Streamed $keyCount total keys, reservoir size: ${reservoir.size} (anchors found: ${anchorFound.size}/${anchorContexts.size})")
        if (anchorFound.size < anchorContexts.size) {
            println("Warning: Missing anchors: ${anchorContexts - anchorFound}")
        }

        return reservoir
    }

    /** Parses the followers array: [[word, score], [word, score], ...] */
    private fun parseFollowersArray(parser: JsonParser): List<List<Any>> {
        require(parser.currentToken == JsonToken.START_ARRAY) { "Expected START_ARRAY for followers" }
        val followers = mutableListOf<List<Any>>()

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            require(parser.currentToken == JsonToken.START_ARRAY) { "Expected START_ARRAY for follower entry" }

            // Parse [word, score]
            require(parser.nextToken() == JsonToken.VALUE_STRING) { "Expected word string" }
            val word = parser.text

            require(parser.nextToken() == JsonToken.VALUE_NUMBER_FLOAT || parser.currentToken == JsonToken.VALUE_NUMBER_INT) { "Expected score number" }
            val score = parser.decimalValue // Use decimalValue to preserve precision

            require(parser.nextToken() == JsonToken.END_ARRAY) { "Expected END_ARRAY for follower entry" }

            followers.add(listOf(word, score))
        }

        return followers
    }
}