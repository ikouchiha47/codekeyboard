package com.codekeyboard

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

class BigramModel(private val context: Context) {

    // seed[prevWord] = list of (nextWord, score) sorted by score desc
    private val seed = mutableMapOf<String, List<Pair<String, Float>>>()

    // user[prevWord] = mutable list of (nextWord, count) sorted by count desc
    private val user = mutableMapOf<String, MutableList<Pair<String, Int>>>()

    private val executor = Executors.newSingleThreadExecutor()
    private val userFile get() = File(context.filesDir, "user_bigrams.json")

    companion object {
        private const val MAX_USER_FOLLOWERS = 20
        private const val SEED_WEIGHT = 0.4f
        private const val USER_WEIGHT = 0.6f
    }

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
            val keys = obj.keys()
            while (keys.hasNext()) {
                val prev = keys.next()
                val arr = obj.getJSONArray(prev)
                val followers = (0 until arr.length()).map { i ->
                    val pair = arr.getJSONArray(i)
                    pair.getString(0) to pair.getInt(1)
                }.toMutableList()
                user[prev] = followers
            }
        } catch (e: Exception) {
            android.util.Log.e("BigramModel", "Failed to load user bigrams: $e")
        }
    }

    // Returns top N next-word candidates given the previous committed word.
    // If prefix is non-empty, filters to candidates starting with prefix.
    fun nextWords(prevWord: String, prefix: String = "", n: Int = 5): List<String> {
        val prev = prevWord.lowercase()
        val pfx = prefix.lowercase()

        val scores = mutableMapOf<String, Float>()

        seed[prev]?.forEach { (word, score) ->
            scores[word] = (scores[word] ?: 0f) + SEED_WEIGHT * score
        }

        user[prev]?.let { followers ->
            val maxCount = followers.firstOrNull()?.second?.toFloat() ?: 1f
            followers.forEach { (word, count) ->
                val score = count / maxCount
                scores[word] = (scores[word] ?: 0f) + USER_WEIGHT * score
            }
        }

        return scores.entries
            .filter { pfx.isEmpty() || it.key.startsWith(pfx) }
            .sortedByDescending { it.value }
            .take(n)
            .map { it.key }
    }

    // Called on every word commit. Records the transition prevWord → nextWord.
    fun recordTransition(prevWord: String, nextWord: String) {
        if (prevWord.isBlank() || nextWord.isBlank()) return
        val prev = prevWord.lowercase()
        val next = nextWord.lowercase()
        val followers = user.getOrPut(prev) { mutableListOf() }
        val idx = followers.indexOfFirst { it.first == next }
        if (idx >= 0) {
            followers[idx] = next to followers[idx].second + 1
        } else {
            followers.add(next to 1)
        }
        followers.sortByDescending { it.second }
        if (followers.size > MAX_USER_FOLLOWERS) followers.removeAt(followers.size - 1)
        persistAsync()
    }

    private fun persistAsync() {
        executor.submit {
            try {
                val obj = JSONObject()
                user.forEach { (prev, followers) ->
                    val arr = org.json.JSONArray()
                    followers.forEach { (word, count) ->
                        arr.put(org.json.JSONArray().apply { put(word); put(count) })
                    }
                    obj.put(prev, arr)
                }
                userFile.writeText(obj.toString())
            } catch (e: Exception) {
                android.util.Log.e("BigramModel", "Failed to persist: $e")
            }
        }
    }
}
