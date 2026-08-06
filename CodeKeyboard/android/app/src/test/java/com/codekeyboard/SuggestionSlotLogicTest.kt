package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

// Mirrors SuggestionBarView.update() slot logic:
// slot 0 = typed word (always), slots 1..N = suggestions excluding the typed word.
class SuggestionSlotLogicTest {

    private fun buildSlots(word: String, suggestions: List<String>): List<String> {
        if (word.isEmpty()) return emptyList()
        return listOf(word) + suggestions.filter { it != word }
    }

    private fun slot0Highlighted(items: List<String>): Boolean = items.size > 1

    @Test fun `empty word produces no slots`() {
        assertEquals(emptyList<String>(), buildSlots("", emptyList()))
    }

    @Test fun `word with no suggestions shows only the word`() {
        assertEquals(listOf("xyz"), buildSlots("xyz", emptyList()))
    }

    @Test fun `slot 0 is always the typed word`() {
        val slots = buildSlots("hel", listOf("help", "helps", "helper"))
        assertEquals("hel", slots[0])
    }

    @Test fun `suggestions follow the typed word`() {
        val slots = buildSlots("hel", listOf("help", "helps", "helper"))
        assertEquals(listOf("hel", "help", "helps", "helper"), slots)
    }

    @Test fun `typed word is excluded from suggestion slots if it appears in suggestions`() {
        // trie may return the exact word as a suggestion — it should not be duplicated
        val slots = buildSlots("help", listOf("help", "helpful", "helpless"))
        assertEquals(listOf("help", "helpful", "helpless"), slots)
        assertEquals(3, slots.size)
    }

    @Test fun `word with one suggestion shows two slots`() {
        val slots = buildSlots("he", listOf("her"))
        assertEquals(listOf("he", "her"), slots)
    }

    @Test fun `word with two suggestions shows three slots`() {
        val slots = buildSlots("he", listOf("her", "here"))
        assertEquals(listOf("he", "her", "here"), slots)
    }

    @Test fun `slot 0 highlighted only when suggestions present`() {
        assertFalse(slot0Highlighted(buildSlots("xyz", emptyList())))
        assertTrue(slot0Highlighted(buildSlots("he", listOf("her", "here"))))
    }

    @Test fun `empty word with non-empty suggestions produces no slots`() {
        assertEquals(emptyList<String>(), buildSlots("", listOf("help")))
    }

    @Test fun `five suggestions all appear after typed word`() {
        val suggs = listOf("help", "helps", "helper", "helpful", "helpless")
        val slots = buildSlots("hel", suggs)
        assertEquals(6, slots.size)
        assertEquals("hel", slots[0])
        assertEquals(suggs, slots.drop(1))
    }
}
