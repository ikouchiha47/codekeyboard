package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

// Pure JVM tests for snippet store logic.
// All methods mirror SnippetStore behaviour on an in-memory map so no Android
// context or SharedPreferences is needed.
class SnippetStoreTest {

    // ── In-memory store mirroring SnippetStore ────────────────────────────────

    private val store = mutableMapOf<String, String>()

    private val SHORTCODE_RE = Regex("^[a-z0-9_]+$")

    private fun add(shortcode: String, expansion: String): Boolean {
        if (shortcode.isBlank() || expansion.isBlank()) return false
        if (!SHORTCODE_RE.matches(shortcode)) return false
        if (store.containsKey(shortcode)) return false
        store[shortcode] = expansion
        return true
    }

    private fun update(shortcode: String, expansion: String): Boolean {
        if (shortcode.isBlank() || expansion.isBlank()) return false
        store[shortcode] = expansion
        return true
    }

    private fun delete(shortcode: String) { store.remove(shortcode) }

    private fun exists(shortcode: String) = store.containsKey(shortcode)

    private fun matching(prefix: String): List<String> {
        val nonEmpty = store.entries.filter { it.value.isNotEmpty() }
        if (prefix.isEmpty()) return nonEmpty.map { it.value }.take(3)
        return nonEmpty
            .filter { it.key.startsWith(prefix) }
            .map { it.value }
            .take(3)
    }

    // ── add() ─────────────────────────────────────────────────────────────────

    @Test fun `add stores shortcode and expansion`() {
        assertTrue(add("em", "alex@example.com"))
        assertEquals("alex@example.com", store["em"])
    }

    @Test fun `add returns false for blank shortcode`() {
        assertFalse(add("", "alex@example.com"))
        assertFalse(add("  ", "alex@example.com"))
    }

    @Test fun `add returns false for blank expansion`() {
        assertFalse(add("em", ""))
        assertFalse(add("em", "  "))
    }

    @Test fun `add returns false on collision`() {
        add("em", "first@example.com")
        assertFalse(add("em", "second@example.com"))
        assertEquals("first@example.com", store["em"])
    }

    @Test fun `add returns false for both blank`() {
        assertFalse(add("", ""))
    }

    @Test fun `add rejects shortcode with space`() {
        assertFalse(add("my key", "value"))
        assertFalse(store.containsKey("my key"))
    }

    @Test fun `add rejects shortcode with uppercase`() {
        assertFalse(add("Em", "value"))
    }

    @Test fun `add rejects shortcode with special characters`() {
        assertFalse(add("em!", "value"))
        assertFalse(add("em@", "value"))
        assertFalse(add(";em", "value"))
    }

    @Test fun `add accepts alphanumeric and underscore shortcode`() {
        assertTrue(add("my_key2", "value"))
        assertEquals("value", store["my_key2"])
    }

    // ── update() ─────────────────────────────────────────────────────────────

    @Test fun `update overwrites existing value`() {
        add("em", "old@example.com")
        assertTrue(update("em", "new@example.com"))
        assertEquals("new@example.com", store["em"])
    }

    @Test fun `update returns false for blank expansion`() {
        add("em", "alex@example.com")
        assertFalse(update("em", ""))
        assertEquals("alex@example.com", store["em"])
    }

    @Test fun `update returns false for blank shortcode`() {
        assertFalse(update("", "alex@example.com"))
    }

    @Test fun `update does not clear existing value on blank expansion`() {
        add("em", "alex@example.com")
        update("em", "")
        assertEquals("alex@example.com", store["em"])
    }

    // ── delete() ─────────────────────────────────────────────────────────────

    @Test fun `delete removes shortcode`() {
        add("em", "alex@example.com")
        delete("em")
        assertFalse(exists("em"))
    }

    @Test fun `delete of non-existent key is no-op`() {
        delete("unknown")
        assertFalse(exists("unknown"))
    }

    // ── exists() ─────────────────────────────────────────────────────────────

    @Test fun `exists true after add`() {
        add("ph", "+1 555")
        assertTrue(exists("ph"))
    }

    @Test fun `exists false before add`() {
        assertFalse(exists("ph"))
    }

    @Test fun `exists false after delete`() {
        add("ph", "+1 555")
        delete("ph")
        assertFalse(exists("ph"))
    }

    // ── matching() ───────────────────────────────────────────────────────────

    @Test fun `matching empty prefix returns up to 3 non-empty values`() {
        add("em", "a@b.com"); add("ph", "+1"); add("me", "Alex"); add("gh", "github")
        assertEquals(3, matching("").size)
    }

    @Test fun `matching prefix filters by shortcode`() {
        add("em", "a@b.com"); add("ph", "+1")
        assertEquals(listOf("a@b.com"), matching("e"))
    }

    @Test fun `matching prefix with no match returns empty`() {
        add("em", "a@b.com")
        assertEquals(emptyList<String>(), matching("x"))
    }

    @Test fun `matching excludes empty values`() {
        store["em"] = ""
        assertEquals(emptyList<String>(), matching("e"))
    }

    @Test fun `matching after delete excludes deleted key`() {
        add("em", "a@b.com")
        delete("em")
        assertEquals(emptyList<String>(), matching("e"))
    }

    @Test fun `value containing semicolon stored as-is`() {
        add("url", "https://example.com/a;b")
        assertEquals(listOf("https://example.com/a;b"), matching("url"))
    }

    @Test fun `very long expansion stored and returned as-is`() {
        val long = "x".repeat(10_000)
        add("lg", long)
        assertEquals(listOf(long), matching("lg"))
    }
}
