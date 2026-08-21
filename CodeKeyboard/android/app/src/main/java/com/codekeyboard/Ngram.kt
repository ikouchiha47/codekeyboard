package com.codekeyboard

import android.content.Context
import org.json.JSONObject
import java.io.File

/**
 * Shared contract for a single order of n-gram next-word prediction
 * (bigram, trigram, pentagram, ...). One signature for every order — each
 * implementation only requires as many trailing context words as it needs
 * (`order - 1`), via a variadic parameter, rather than each order having a
 * different method shape (`nextWords(prevWord)` vs `nextWords(w1, w2)` vs
 * ...). This is what lets [Ngram] hold a mixed list of different orders and
 * call all of them the same way.
 *
 * Deliberately separate from [BigramModel] — that class also owns the
 * user-learned decay layer (formula_d/formula_p, persistence, recency
 * scoring; see its kdoc) which doesn't apply to a static trigram/pentagram
 * seed the same way yet. Per the project's ADR immutability convention,
 * this is a new parallel abstraction rather than a retrofit of working
 * code; whether/how [BigramModel] should eventually implement this
 * interface too is a separate decision, not made here.
 */
interface NgramModel {
    /** Total words in the unit: 2 = bigram, 3 = trigram, 5 = pentagram. */
    val order: Int

    /**
     * @param context trailing previously-committed words, oldest-to-newest.
     *   Only the last (order - 1) are used; implementations should ignore
     *   any extra words rather than require an exact-length call.
     */
    fun nextWords(vararg context: String, prefix: String = "", n: Int = 5): List<String>

    /**
     * Total observed count backing this context, or 0 if unknown. This is
     * the confidence signal [Ngram]'s cascade compares across tiers (ADR-008
     * task L) — [nextWords]'s own scores are normalized per-context (every
     * context's top candidate reads as ~1.0 regardless of how much evidence
     * backs it), so they can't be compared across different contexts or
     * different tiers on their own; support can.
     */
    fun support(vararg context: String): Int
}

// ── DEAD CODE (ADR-010 tasks L/M) ────────────────────────────────────────────
// NgramEntry / NgramDataSource / JsonNgramDataSource were the JSON-backed
// seed-data plumbing for the removed Trigram/AbstractNgram. The pack path
// (PackNgramModel) reads directly from LanguagePack, so these have no
// consumers. Kept commented for reference; delete once confirmed unused.
//
// /** One context's scored followers plus the total observed count backing
//  *  them — the latter is what makes cross-context/cross-tier comparison
//  *  possible (see [NgramModel.support]). */
// data class NgramEntry(val followers: List<Pair<String, Float>>, val support: Int)
//
// /**
//  * Pluggable seed-data loader for [NgramModel] implementations — kept as an
//  * interface (rather than each model reading JSON directly) so the storage
//  * format can move to something else later (e.g. a packed binary trie, like
//  * [Trie]'s TRIF format) without touching model/scoring code.
//  */
// interface NgramDataSource {
//     /** contextKey (space-joined, lowercase, (order-1) words) -> entry. */
//     fun load(): Map<String, NgramEntry>
// }
//
// /** [NgramDataSource] backed by the `{"context": {"followers": [["word", score],
//  *  ...], "support": N}}` JSON shape trigrams.json uses (ADR-008 task L). Only
//  *  Trigram/AbstractNgram read this — unlike bigrams.json, there's no
//  *  pre-existing parser to stay compatible with, so this shape was free to
//  *  add "support" to directly rather than needing a companion file. */
// class JsonNgramDataSource private constructor(private val reader: () -> String) : NgramDataSource {
//
//     constructor(context: Context, assetName: String) : this(
//         { context.assets.open(assetName).bufferedReader().readText() }
//     )
//
//     // Android-free loader for JVM unit tests, mirrors Trie.load(File)/BigramModel's File constructor.
//     constructor(file: File) : this({ file.readText() })
//
//     override fun load(): Map<String, NgramEntry> {
//         val obj = JSONObject(reader())
//         val result = mutableMapOf<String, NgramEntry>()
//         val keys = obj.keys()
//         while (keys.hasNext()) {
//             val key = keys.next()
//             val entryObj = obj.getJSONObject(key)
//             val arr = entryObj.getJSONArray("followers")
//             val followers = (0 until arr.length()).map { i ->
//                 val pair = arr.getJSONArray(i)
//                 pair.getString(0) to pair.getDouble(1).toFloat()
//             }
//             result[key] = NgramEntry(followers, entryObj.getInt("support"))
//         }
//         return result
//     }
// }

/**
 * Orchestrates a priority-ordered cascade of [NgramModel]s — e.g.
 * `Ngram(listOf(Trigram(...), BigramModelAdapter(...)))`. For each model
 * that has enough context and a non-empty answer, blends its candidates
 * into the final ranking weighted by that tier's [NgramModel.support]
 * relative to the others' — a tier backed by more real evidence
 * contributes more, but never unconditionally overrides a tier with less
 * (unlike the original "first non-empty tier wins" design, which let a
 * thin trigram context override a strong bigram one; see ADR-008 task L
 * for the measured cases this fixes). Mirrors the interpolation-over-
 * backoff lesson from n-gram smoothing itself (ADR-001) — always blend
 * rather than hard-switch.
 */
class Ngram(private val models: List<NgramModel>) {

    /** Largest context window any model in the cascade needs (highest order - 1).
     *  Callers size their rolling context window off this, so adding a
     *  Pentagram later widens it automatically (Open/Closed). */
    val maxContextNeeded: Int = (models.maxOfOrNull { it.order } ?: 1) - 1

    fun nextWords(context: List<String>, prefix: String = "", n: Int = 5): List<String> {
        val candidates = models.mapNotNull { model ->
            val needed = model.order - 1
            if (context.size < needed) return@mapNotNull null
            val tail = context.takeLast(needed).toTypedArray()
            val results = model.nextWords(*tail, prefix = prefix, n = n)
            if (results.isEmpty()) return@mapNotNull null
            Triple(model, results, model.support(*tail))
        }
        if (candidates.isEmpty()) return emptyList()
        if (candidates.size == 1) return candidates[0].second

        val totalSupport = candidates.sumOf { it.third }.coerceAtLeast(1)
        val weighted = candidates.flatMap { (_, results, support) ->
            val weight = support.toDouble() / totalSupport
            results.mapIndexed { idx, word -> word to weight * (results.size - idx) }
        }
        // Drop self-referential continuations: a suggested word that equals a
        // context word (e.g. "i" after "i like") is a low-quality artifact of
        // the raw n-gram data, not a useful suggestion.
        val contextSet = context.map { it.lowercase() }.toSet()
        return weighted.groupBy({ it.first }, { it.second })
            .mapValues { it.value.sum() }
            .entries
            .filterNot { it.key.lowercase() in contextSet }
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }
    }
}
