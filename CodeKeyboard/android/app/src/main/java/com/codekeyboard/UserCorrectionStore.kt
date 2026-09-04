package com.codekeyboard

import java.io.File

class UserCorrectionStore(
    private val file: File,
    private val cache: EvictionCache<String, String> = TwoQueueCache(20_000)
) {

    init { load() }

    fun record(typo: String, correction: String) {
        if (typo.isBlank() || correction.isBlank() || typo == correction) return
        cache.put(typo.lowercase(), correction)
    }

    fun lookup(word: String): String? = cache.get(word.lowercase())

    fun save() {
        file.bufferedWriter().use { w ->
            cache.entries().forEach { (k, v) -> w.write("$k\t$v\n") }
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
