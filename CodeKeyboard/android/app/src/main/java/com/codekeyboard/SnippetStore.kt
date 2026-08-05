package com.codekeyboard

object SnippetStore {

    private val DEFAULTS = listOf("em", "ph", "addr", "me", "gh", "li")
    private const val PREFIX = "snippet_"

    fun init() {
        if (KeyboardSettings.getBoolean("snippets_seeded", false)) return
        DEFAULTS.forEach { key -> KeyboardSettings.setString("$PREFIX$key", "") }
        KeyboardSettings.setBoolean("snippets_seeded", true)
    }

    fun matching(prefix: String): List<String> {
        if (prefix.isEmpty()) return allNonEmpty().map { (_, v) -> v }.take(3)
        return allNonEmpty()
            .filter { (key, _) -> key.startsWith(prefix) }
            .map { (_, value) -> value }
            .take(3)
    }

    // Returns false if shortcode or expansion is blank, or shortcode already exists.
    fun add(shortcode: String, expansion: String): Boolean {
        if (shortcode.isBlank() || expansion.isBlank()) return false
        if (exists(shortcode)) return false
        KeyboardSettings.setString("$PREFIX$shortcode", expansion)
        return true
    }

    // Returns false if shortcode or expansion is blank. Overwrites existing value.
    fun update(shortcode: String, expansion: String): Boolean {
        if (shortcode.isBlank() || expansion.isBlank()) return false
        KeyboardSettings.setString("$PREFIX$shortcode", expansion)
        return true
    }

    fun delete(shortcode: String) {
        KeyboardSettings.remove("$PREFIX$shortcode")
    }

    fun get(shortcode: String): String =
        KeyboardSettings.getString("$PREFIX$shortcode", "")

    fun exists(shortcode: String): Boolean =
        KeyboardSettings.allKeys().contains("$PREFIX$shortcode")

    fun allShortcodes(): List<String> =
        KeyboardSettings.allKeys()
            .filter { it.startsWith(PREFIX) }
            .map { it.removePrefix(PREFIX) }
            .sorted()

    fun all(): List<Pair<String, String>> =
        allShortcodes().map { key -> key to KeyboardSettings.getString("$PREFIX$key", "") }

    private fun allNonEmpty(): List<Pair<String, String>> =
        all().filter { (_, v) -> v.isNotEmpty() }
}
