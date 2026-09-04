package com.codekeyboard

import kotlin.math.roundToInt

/**
 * Minimal interface for prefix-based word lookup.
 * Implemented by both [Trie] (legacy) and [WordDictionary] (pack-backed).
 */
interface PrefixDictionary {
    fun suggest(prefix: String, max: Int): List<String>
    fun has(word: String): Boolean

    /**
     * Correction path (ADR-013). Default: no corrections. Pack-backed
     * implementations (WordDictionary) return layout-aware SymSpell +
     * BevaTrieSearch candidates, best first.
     */
    fun correct(word: String, maxResults: Int): List<FuzzyResult> = emptyList()
}

/**
 * Configuration for one language pack within [MergedSuggestionStrategy].
 *
 * @param lang     BCP-47 language tag (e.g. "en", "hi") — matches the .cklm asset name.
 * @param dict     Loaded dictionary for prefix completion and fuzzy correction.
 * @param weight   Relative ranking weight (1.0 = normal; <1.0 de-prioritises this pack).
 * @param maxOrder Highest n-gram order to use for this pack's Ngram cascade (1=unigram,
 *                 2=bigram, 3=trigram). Consumed by the IME at load time; not used
 *                 inside MergedSuggestionStrategy itself.
 */
data class PackConfig(
    val lang: String,
    val dict: PrefixDictionary,
    val weight: Float = 1.0f,
    val share: Float = 1.0f,
    val maxOrder: Int = 3,
)

/**
 * Interface for bigram next-word prediction with context.
 * Implemented by [BigramModel] (legacy) and [PackBackedBigramModel] (pack-backed).
 */
interface BigramProvider {
    fun nextWords(prevWord: String, prefix: String, n: Int): List<String>
}

interface SuggestionStrategy {
    companion object {
        const val DEFAULT_SUGGESTION_COUNT = 7
    }
    fun suggest(prefix: String, k: Int = DEFAULT_SUGGESTION_COUNT, context: String = ""): List<String>
}

class MergedSuggestionStrategy(
    private val userAdapter: UserTrieAdapter,
    private val baseAdapter: TrieAdapter<Int>,
    private val packs: List<PackConfig>,
    private val correctionStore: UserCorrectionStore? = null,
) : SuggestionStrategy {

    override fun suggest(prefix: String, k: Int, context: String): List<String> {
        val merged = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        fun add(word: String) { if (seen.add(word)) merged.add(word) }
        fun addAll(words: List<String>) { words.forEach { add(it) } }
        fun remaining() = k - merged.size

        // Tier 1: user stored correction for this exact typo
        correctionStore?.lookup(prefix)?.let { add(it) }

        // Tier 2: user learned words (exact prefix)
        addAll(userAdapter.suggest(prefix, k))

        val primary = packs.firstOrNull()
        val secondaries = packs.drop(1)
        val threshold = FuzzyThreshold.forLength(prefix.length)

        // Tier 3: primary (en) exact prefix
        if (primary != null && remaining() > 0) {
            val primarySlots = if (secondaries.isEmpty()) remaining()
                else (k - secondaries.sumOf { (it.share * k).roundToInt().coerceAtLeast(1) }).coerceAtLeast(1)
            addAll(primary.dict.suggest(prefix, primarySlots + 2).take(primarySlots + 2))
        }

        // Tier 4: secondary (hi) exact prefix — before fuzzy so hi words aren't crowded out
        for (pack in secondaries) {
            if (remaining() <= 0) break
            val slots = (pack.share * k).roundToInt().coerceAtLeast(1).coerceAtMost(remaining())
            addAll(pack.dict.suggest(prefix, slots + 2).take(slots + 2))
        }

        // Tier 5: primary (en) fuzzy/proximity
        if (primary != null && remaining() > 0 && threshold > 0) {
            val enFuzzy = primaryFuzzy(prefix, threshold, primary, seen)
            addAll(enFuzzy.take(remaining()))
        }

        // Tier 6: secondary (hi) fuzzy/proximity
        if (remaining() > 0 && threshold > 0) {
            val secFuzzy = secondaryFuzzy(prefix, threshold, secondaries, seen)
            addAll(secFuzzy.take(remaining()))
        }

        return merged.take(k)
    }

    private fun primaryFuzzy(word: String, threshold: Int, primary: PackConfig, seen: Set<String>): List<String> {
        val userFuzzy = BevaTrieSearch.search(userAdapter, word, threshold, Int.MAX_VALUE)
        val enCorrections = primary.dict.correct(word, Int.MAX_VALUE)
        return mergeByDistance(word, userFuzzy + enCorrections, seen)
    }

    private fun secondaryFuzzy(word: String, threshold: Int, secondaries: List<PackConfig>, seen: Set<String>): List<String> {
        val corrections = secondaries.flatMap { pack ->
            pack.dict.correct(word, Int.MAX_VALUE).map { r ->
                if (pack.weight >= 1.0f) r
                else r.copy(weightedDistance = r.weightedDistance / pack.weight)
            }
        }
        return mergeByDistance(word, corrections, seen)
    }

    private fun mergeByDistance(word: String, results: List<FuzzyResult>, seen: Set<String> = emptySet()): List<String> {
        val byWord = LinkedHashMap<String, FuzzyResult>()
        for (r in results) {
            if (r.word in seen) continue
            if (r.word.equals(word, ignoreCase = true)) continue
            // Skip prefix-extension relationships — exact suggest already covers these
            if (r.word.startsWith(word) || word.startsWith(r.word)) continue
            val existing = byWord[r.word]
            if (existing == null || r.weightedDistance < existing.weightedDistance) byWord[r.word] = r
        }
        return byWord.values.toList()
            .sortedWith(compareBy(
                { it.weightedDistance },
                { -it.frequency },
                { -commonPrefixLength(word, it.word) },
            ))
            .map { it.word }
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }
}

class BaseSuggestionStrategy(private val baseDict: PrefixDictionary) : SuggestionStrategy {
    override fun suggest(prefix: String, k: Int, context: String): List<String> =
        baseDict.suggest(prefix, k)
}

// Promotes bigram candidates to the top when context (previous word) is provided.
class BigramAwareSuggestionStrategy(
    private val base: SuggestionStrategy,
    private val bigram: BigramProvider,
) : SuggestionStrategy {

    override fun suggest(prefix: String, k: Int, context: String): List<String> {
        val baseResults = base.suggest(prefix, k + 5)
        if (context.isEmpty()) return baseResults.take(k)
        val bigramMatches = bigram.nextWords(context, prefix, k)
        val promoted = bigramMatches + baseResults.filter { it !in bigramMatches }
        return promoted.take(k)
    }
}
