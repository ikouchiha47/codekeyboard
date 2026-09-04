package com.codekeyboard

import com.github.benmanes.caffeine.cache.Caffeine
import java.io.File

/**
 * Stores typo→correction pairs learned from Android's onCommitCorrection.
 * Backed by a Caffeine W-TinyLFU cache (20K entries max) — low-frequency
 * one-off typos are evicted automatically; frequently repeated corrections
 * survive indefinitely within the cap.
 *
 * Persisted as tab-separated lines: `typo\tcorrection`
 */
class UserCorrectionStore(private val file: File) {

    private val cache = Caffeine.newBuilder()
        .maximumSize(20_000)
        .build<String, String>()

    init { load() }

    fun record(typo: String, correction: String) {
        if (typo.isBlank() || correction.isBlank() || typo == correction) return
        cache.put(typo.lowercase(), correction)
    }

    fun lookup(word: String): String? = cache.getIfPresent(word.lowercase())

    fun save() {
        val entries = cache.asMap()
        file.bufferedWriter().use { w ->
            for ((typo, correction) in entries) w.write("$typo\t$correction\n")
        }
    }

    private fun load() {
        if (!file.exists()) return
        file.forEachLine { line ->
            val tab = line.indexOf('\t')
            if (tab > 0) cache.put(line.substring(0, tab), line.substring(tab + 1))
        }
    }
}
