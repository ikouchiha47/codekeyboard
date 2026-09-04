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
    fun suggest(prefix: String, k: Int, context: String = ""): List<String>
}

class MergedSuggestionStrategy(
    private val userAdapter: UserTrieAdapter,
    private val baseAdapter: TrieAdapter<Int>,
    private val packs: List<PackConfig>,
    private val correctionStore: UserCorrectionStore? = null,
) : SuggestionStrategy {

    override fun suggest(prefix: String, k: Int, context: String): List<String> {
        val exact = exactSuggest(prefix, k)
        if (exact.size >= k) return exact

        val threshold = FuzzyThreshold.forLength(prefix.length)
        if (threshold == 0) return exact

        val fuzzy = fuzzyFill(prefix, threshold, k - exact.size)
        val exactSet = exact.toSet()
        val candidates = exact + fuzzy.filter { it !in exactSet }

        // Prepend any stored correction for this exact typo (highest confidence).
        val stored = correctionStore?.lookup(prefix)
        return if (stored != null && stored !in exactSet)
            listOf(stored) + candidates.filter { it != stored }.take(k - 1)
        else
            candidates
    }

    private fun exactSuggest(prefix: String, k: Int): List<String> {
        val userResults = userAdapter.suggest(prefix, k)
        val seen = userResults.toMutableSet()
        val merged = userResults.toMutableList()
        if (merged.size >= k) return merged.take(k)

        // Each secondary pack's share is a direct fraction of k (e.g. 0.3 = 30% of k slots).
        // Primary gets whatever remains after secondaries. Changing k auto-scales everything.
        val secondaries = packs.drop(1)
        val primary = packs.firstOrNull() ?: return merged.take(k)

        val secondarySlots = secondaries.map { pack ->
            pack to (pack.share * k).roundToInt().coerceAtLeast(if (pack.share > 0f) 1 else 0)
        }
        val primarySlots = (k - secondarySlots.sumOf { it.second }).coerceAtLeast(1)

        // Fill primary slots first, then each secondary's slots.
        fun fillSlots(dict: PrefixDictionary, slots: Int) {
            var filled = 0
            for (w in dict.suggest(prefix, slots + 2)) {
                if (filled >= slots || merged.size >= k) break
                if (seen.add(w)) { merged.add(w); filled++ }
            }
        }

        fillSlots(primary.dict, primarySlots)
        for ((pack, slots) in secondarySlots) fillSlots(pack.dict, slots)

        // Backfill any unused slots from all packs (primary first).
        if (merged.size < k) {
            for (pack in packs) {
                if (merged.size >= k) break
                for (w in pack.dict.suggest(prefix, k)) {
                    if (merged.size >= k) break
                    if (seen.add(w)) merged.add(w)
                }
            }
        }
        return merged.take(k)
    }

    private fun fuzzyFill(word: String, threshold: Int, limit: Int): List<String> {
        val userFuzzy = BevaTrieSearch.search(userAdapter, word, threshold, Int.MAX_VALUE)
        // Collect corrections from all packs; weight adjusts effective distance.
        val allCorrections = packs.flatMap { pack ->
            pack.dict.correct(word, Int.MAX_VALUE).map { r ->
                if (pack.weight >= 1.0f) r
                else r.copy(weightedDistance = r.weightedDistance / pack.weight)
            }
        }
        val byWord = LinkedHashMap<String, FuzzyResult>()
        for (r in userFuzzy + allCorrections) {
            val existing = byWord[r.word]
            if (existing == null || r.weightedDistance < existing.weightedDistance) byWord[r.word] = r
        }
        return byWord.values.toList()
            .sortedWith(compareBy(
                { it.weightedDistance },
                { -commonPrefixLength(word, it.word) },
                { -it.frequency },
            ))
            .map { it.word }
            .take(limit)
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
