package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

// Pure JVM tests for snippet matching logic.
// Mirrors SnippetStore.matching() directly on a list so no Android context is needed.
class SnippetStoreTest {

    private fun matching(entries: List<Pair<String, String>>, prefix: String): List<String> {
        val nonEmpty = entries.filter { (_, v) -> v.isNotEmpty() }
        if (prefix.isEmpty()) return nonEmpty.map { (_, v) -> v }.take(3)
        return nonEmpty
            .filter { (key, _) -> key.startsWith(prefix) }
            .map { (_, v) -> v }
            .take(3)
    }

    private val sample = listOf(
        "em"   to "alex@example.com",
        "ph"   to "+1 555 123 4567",
        "addr" to "123 Main St",
        "me"   to "Alex Day",
        "gh"   to "https://github.com/ikouchiha47",
        "li"   to "",
    )

    @Test fun `matching empty prefix returns all non-empty values`() {
        val result = matching(sample, "")
        assertEquals(listOf("alex@example.com", "+1 555 123 4567", "123 Main St"), result)
    }

    @Test fun `matching empty prefix caps at 3`() {
        assertEquals(3, matching(sample, "").size)
    }

    @Test fun `matching prefix e matches em`() {
        assertEquals(listOf("alex@example.com"), matching(sample, "e"))
    }

    @Test fun `matching prefix em matches em`() {
        assertEquals(listOf("alex@example.com"), matching(sample, "em"))
    }

    @Test fun `matching prefix a matches addr`() {
        assertEquals(listOf("123 Main St"), matching(sample, "a"))
    }

    @Test fun `matching prefix x returns empty`() {
        assertEquals(emptyList<String>(), matching(sample, "x"))
    }

    @Test fun `empty value excluded from results`() {
        val result = matching(sample, "")
        assertFalse(result.any { it.isEmpty() })
    }

    @Test fun `all empty values returns empty list`() {
        val allEmpty = listOf("em" to "", "ph" to "")
        assertEquals(emptyList<String>(), matching(allEmpty, ""))
    }

    @Test fun `value containing semicolon stored as-is`() {
        val entries = listOf("url" to "https://example.com/a;b")
        assertEquals(listOf("https://example.com/a;b"), matching(entries, "url"))
    }

    @Test fun `very long value returned as-is`() {
        val long = "x".repeat(10_000)
        val entries = listOf("lg" to long)
        assertEquals(listOf(long), matching(entries, "lg"))
    }
}
