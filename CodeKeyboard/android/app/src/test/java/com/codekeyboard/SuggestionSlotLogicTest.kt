package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

class SuggestionSlotLogicTest {

    // Mirrors SuggestionBarView.update() slot assignment — pure function, no Android.
    private fun buildSlots(word: String, suggestions: List<String>): Triple<String, String, String> {
        val s0 = if (word.isNotEmpty()) suggestions.getOrNull(0) ?: word else ""
        val s1 = suggestions.getOrNull(1) ?: ""
        val s2 = suggestions.getOrNull(2) ?: ""
        return Triple(s0, s1, s2)
    }

    private fun highlighted(word: String, suggestions: List<String>): Boolean =
        suggestions.isNotEmpty() && word.isNotEmpty()

    @Test fun `empty word and no suggestions`() {
        assertEquals(Triple("", "", ""), buildSlots("", emptyList()))
    }

    @Test fun `word with no suggestions shows word in slot0`() {
        assertEquals(Triple("xyz", "", ""), buildSlots("xyz", emptyList()))
    }

    @Test fun `word with 1 suggestion fills slot0 only`() {
        assertEquals(Triple("help", "", ""), buildSlots("hel", listOf("help")))
    }

    @Test fun `word with 2 suggestions fills slot0 and slot1`() {
        assertEquals(Triple("help", "helps", ""), buildSlots("hel", listOf("help", "helps")))
    }

    @Test fun `word with 3 suggestions fills all slots`() {
        assertEquals(
            Triple("help", "helps", "helper"),
            buildSlots("hel", listOf("help", "helps", "helper"))
        )
    }

    @Test fun `word equals top suggestion`() {
        assertEquals(Triple("help", "", ""), buildSlots("help", listOf("help")))
    }

    @Test fun `empty word with non-empty suggestions produces empty slots`() {
        assertEquals(Triple("", "", ""), buildSlots("", listOf("help")))
    }

    @Test fun `slot0 highlighted only when suggestions non-empty and word non-empty`() {
        assertFalse(highlighted("", emptyList()))
        assertFalse(highlighted("xyz", emptyList()))
        assertFalse(highlighted("", listOf("help")))
        assertTrue(highlighted("hel", listOf("help")))
        assertTrue(highlighted("hel", listOf("help", "helps", "helper")))
    }
}
