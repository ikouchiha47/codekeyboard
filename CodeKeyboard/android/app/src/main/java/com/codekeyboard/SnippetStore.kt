package com.codekeyboard

object SnippetStore {

    private val DEFAULTS = listOf("em", "ph", "addr", "me", "gh", "li")

    fun init() {
        if (KeyboardSettings.getBoolean("snippets_seeded", false)) return
        DEFAULTS.forEach { key -> KeyboardSettings.setString("snippet_$key", "") }
        KeyboardSettings.setBoolean("snippets_seeded", true)
    }

    fun matching(prefix: String): List<String> {
        if (prefix.isEmpty()) return allNonEmpty().map { (_, v) -> v }.take(3)
        return allNonEmpty()
            .filter { (key, _) -> key.startsWith(prefix) }
            .map { (_, value) -> value }
            .take(3)
    }

    fun set(shortcode: String, expansion: String) {
        KeyboardSettings.setString("snippet_$shortcode", expansion)
    }

    fun get(shortcode: String): String =
        KeyboardSettings.getString("snippet_$shortcode", "")

    fun all(): List<Pair<String, String>> =
        DEFAULTS.map { key -> key to KeyboardSettings.getString("snippet_$key", "") }

    private fun allNonEmpty(): List<Pair<String, String>> =
        all().filter { (_, v) -> v.isNotEmpty() }
}
