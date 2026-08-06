package com.codekeyboard

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors
import kotlin.math.exp
import kotlin.math.pow

/**
 * Two-layer bigram next-word prediction.
 *
 * ## Decay model (ported from librime algo/dynamics.h)
 *
 * Each (prev, next) user entry carries a `dee` value — a floating-point
 * accumulator representing recency-weighted activity — and the global
 * tick (total commits) at which it was last updated.
 *
 * ### formula_d: update dee on each commit
 *
 *   dee_new = dee + amplitude * exp((lastTick - currentTick) / HALF_LIFE)
 *
 * - (lastTick - currentTick) is always negative (past < present), so the
 *   exponent is in (-∞, 0), producing a value in (0, 1].
 * - When the entry was used very recently (lastTick ≈ currentTick), the
 *   exponent → 0 and the boost ≈ amplitude (1.0).
 * - When the entry was used long ago (large gap), the boost → 0.
 * - HALF_LIFE = 200: a gap of 200 commits halves the boost.
 *
 * ### formula_p: convert dee to [0, 1] score
 *
 *   kM  = 1 / (1 - exp(-0.005))  ≈ 200.67
 *   m   = s - (s - u) * pow((1 - exp(-globalTick / 10_000.0)), 10)
 *   score = if (dee < 20)
 *               m + (0.5 - m) * (dee / kM)
 *           else
 *               m + (1 - m) * (4.0.pow(dee / kM) - 1) / 3
 *
 * - m interpolates between s (optimistic ceiling, 1.0) and u (floor, 0.05)
 *   as globalTick grows. At tick 0, m = s; at tick → ∞, m → u. This models
 *   "confidence grows with data": early on we score generously, later the
 *   model becomes more selective.
 * - The piecewise branch on dee:
 *   - dee < 20 (low activity): linear push from m toward 0.5. Low dee,
 *     low score.
 *   - dee ≥ 20 (high activity): exponential push from m toward 1.0.
 *     High recent activity saturates the score near 1.0.
 *
 * ### Combined user score
 *
 *   combined = SEED_WEIGHT * seedScore + USER_WEIGHT * formula_p(...)
 *
 * The user layer dominates (0.6 weight) once it has observed the pair at
 * least a few times. The seed layer (0.4 weight) fills the cold-start gap.
 */
class BigramModel(private val context: Context) {

    // seed[prevWord] = list of (nextWord, score) sorted by score desc
    private val seed = mutableMapOf<String, List<Pair<String, Float>>>()

    // user[prevWord] = mutable list of UserEntry sorted by dee desc
    private val user = mutableMapOf<String, MutableList<UserEntry>>()

    // Global commit counter — incremented on every recordTransition call.
    // Persisted alongside user entries.
    private var globalTick: Int = 0

    private val executor = Executors.newSingleThreadExecutor()
    private val userFile get() = File(context.filesDir, "user_bigrams.json")

    companion object {
        private const val MAX_USER_FOLLOWERS = 20
        private const val SEED_WEIGHT = 0.4f
        private const val USER_WEIGHT = 0.6f

        // librime dynamics constants
        private const val HALF_LIFE = 200.0      // commits; gap of 200 halves the dee boost
        private const val DEE_AMPLITUDE = 1.0    // boost added per commit
        private const val SCORE_CEIL = 1.0       // formula_p s parameter
        private const val SCORE_FLOOR = 0.05     // formula_p u parameter
        private val KM = 1.0 / (1.0 - exp(-0.005))  // ≈ 200.67
    }

    /** Per-(prev, next) state for the user-learned layer. */
    private data class UserEntry(
        val word: String,
        val dee: Double,    // recency-weighted activity accumulator
        val lastTick: Int,  // globalTick at which this entry was last updated
    )

    fun load() {
        loadSeed()
        loadUserBigrams()
    }

    private fun loadSeed() {
        try {
            val json = context.assets.open("bigrams.json").bufferedReader().readText()
            val obj = JSONObject(json)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val prev = keys.next()
                val arr = obj.getJSONArray(prev)
                val followers = (0 until arr.length()).map { i ->
                    val pair = arr.getJSONArray(i)
                    pair.getString(0) to pair.getDouble(1).toFloat()
                }
                seed[prev] = followers
            }
        } catch (e: Exception) {
            android.util.Log.e("BigramModel", "Failed to load seed: $e")
        }
    }

    private fun loadUserBigrams() {
        try {
            if (!userFile.exists()) return
            val obj = JSONObject(userFile.readText())
            globalTick = obj.optInt("tick", 0)
            val entries = obj.optJSONObject("entries") ?: return
            val keys = entries.keys()
            while (keys.hasNext()) {
                val prev = keys.next()
                val arr = entries.getJSONArray(prev)
                val followers = (0 until arr.length()).map { i ->
                    val entry = arr.getJSONArray(i)
                    UserEntry(
                        word = entry.getString(0),
                        dee = entry.getDouble(1),
                        lastTick = entry.getInt(2),
                    )
                }.toMutableList()
                user[prev] = followers
            }
        } catch (e: Exception) {
            android.util.Log.e("BigramModel", "Failed to load user bigrams: $e")
        }
    }

    /**
     * formula_d from librime algo/dynamics.h.
     *
     * Computes updated dee after a commit at currentTick, given the previous
     * dee and the tick at which this entry was last activated.
     */
    private fun formulaD(dee: Double, currentTick: Int, lastTick: Int): Double {
        return dee + DEE_AMPLITUDE * exp((lastTick - currentTick).toDouble() / HALF_LIFE)
    }

    /**
     * formula_p from librime algo/dynamics.h.
     *
     * Converts dee into a [0, 1] score. See class-level kdoc for full derivation.
     */
    private fun formulaP(dee: Double): Double {
        val m = SCORE_CEIL - (SCORE_CEIL - SCORE_FLOOR) *
                (1.0 - exp(-globalTick / 10_000.0)).pow(10)
        return if (dee < 20.0) {
            m + (0.5 - m) * (dee / KM)
        } else {
            m + (1.0 - m) * (4.0.pow(dee / KM) - 1.0) / 3.0
        }
    }

    /**
     * Returns top N next-word candidates given the previous committed word.
     * If prefix is non-empty, filters to candidates starting with prefix.
     */
    fun nextWords(prevWord: String, prefix: String = "", n: Int = 5): List<String> {
        val prev = prevWord.lowercase()
        val pfx = prefix.lowercase()

        val scores = mutableMapOf<String, Float>()

        seed[prev]?.forEach { (word, score) ->
            scores[word] = (scores[word] ?: 0f) + SEED_WEIGHT * score
        }

        user[prev]?.forEach { entry ->
            val userScore = formulaP(entry.dee).toFloat()
            scores[entry.word] = (scores[entry.word] ?: 0f) + USER_WEIGHT * userScore
        }

        return scores.entries
            .filter { pfx.isEmpty() || it.key.startsWith(pfx) }
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }
    }

    /**
     * Records the transition prevWord → nextWord.
     * Updates dee for the entry using formula_d, increments globalTick.
     */
    fun recordTransition(prevWord: String, nextWord: String) {
        if (prevWord.isBlank() || nextWord.isBlank()) return
        val prev = prevWord.lowercase()
        val next = nextWord.lowercase()

        val followers = user.getOrPut(prev) { mutableListOf() }
        val idx = followers.indexOfFirst { it.word == next }

        val newDee: Double
        if (idx >= 0) {
            val existing = followers[idx]
            newDee = formulaD(existing.dee, globalTick, existing.lastTick)
            followers[idx] = existing.copy(dee = newDee, lastTick = globalTick)
        } else {
            // First observation: dee starts at the base amplitude boost
            newDee = formulaD(0.0, globalTick, globalTick)
            followers.add(UserEntry(word = next, dee = newDee, lastTick = globalTick))
        }

        followers.sortByDescending { it.dee }
        if (followers.size > MAX_USER_FOLLOWERS) followers.removeAt(followers.size - 1)

        globalTick++
        persistAsync()
    }

    private fun persistAsync() {
        val tickSnapshot = globalTick
        val userSnapshot = user.mapValues { (_, v) -> v.toList() }
        executor.submit {
            try {
                val entriesObj = JSONObject()
                userSnapshot.forEach { (prev, followers) ->
                    val arr = org.json.JSONArray()
                    followers.forEach { entry ->
                        arr.put(org.json.JSONArray().apply {
                            put(entry.word)
                            put(entry.dee)
                            put(entry.lastTick)
                        })
                    }
                    entriesObj.put(prev, arr)
                }
                val root = JSONObject()
                root.put("tick", tickSnapshot)
                root.put("entries", entriesObj)
                userFile.writeText(root.toString())
            } catch (e: Exception) {
                android.util.Log.e("BigramModel", "Failed to persist: $e")
            }
        }
    }
}
