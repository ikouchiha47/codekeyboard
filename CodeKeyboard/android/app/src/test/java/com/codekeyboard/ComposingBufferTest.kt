package com.codekeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ComposingBufferTest {

    private lateinit var buf: ComposingBuffer

    @Before
    fun setUp() {
        buf = ComposingBuffer()
    }

    @Test
    fun `append builds word`() {
        listOf("h", "e", "l", "l", "o").forEach { buf.append(it) }
        assertEquals("hello", buf.text)
    }

    @Test
    fun `backspace removes last char`() {
        buf.append("h"); buf.append("e"); buf.append("l")
        buf.backspace()
        assertEquals("he", buf.text)
    }

    @Test
    fun `backspace on empty returns false`() {
        assertFalse(buf.backspace())
        assertEquals("", buf.text)
    }

    @Test
    fun `backspace to empty sets isEmpty`() {
        buf.append("a")
        buf.backspace()
        assertEquals("", buf.text)
        assertTrue(buf.isEmpty)
    }

    @Test
    fun `flush returns text and clears`() {
        buf.append("h"); buf.append("e"); buf.append("l"); buf.append("l"); buf.append("o")
        val result = buf.flush()
        assertEquals("hello", result)
        assertTrue(buf.isEmpty)
    }

    @Test
    fun `flush on empty returns empty string`() {
        val result = buf.flush()
        assertEquals("", result)
        assertTrue(buf.isEmpty)
    }

    @Test
    fun `clear empties buffer`() {
        buf.append("h"); buf.append("e"); buf.append("l"); buf.append("l"); buf.append("o")
        buf.clear()
        assertTrue(buf.isEmpty)
    }

    @Test
    fun `append after flush starts fresh`() {
        buf.append("h"); buf.append("i")
        buf.flush()
        buf.append("b"); buf.append("y"); buf.append("e")
        assertEquals("bye", buf.text)
    }

    @Test
    fun `backspace does not go below empty`() {
        assertFalse(buf.backspace())
        assertFalse(buf.backspace())
        assertFalse(buf.backspace())
        assertEquals("", buf.text)
    }

    @Test
    fun `append punctuation treated as char`() {
        buf.append("!")
        assertEquals("!", buf.text)
    }
}
