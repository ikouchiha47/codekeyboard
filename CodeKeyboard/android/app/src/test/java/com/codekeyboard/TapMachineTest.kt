package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

class TapMachineTest {

    @Test fun `single tap returns false`() {
        val tm = TapMachine(doubleTapMs = 300L)
        assertFalse(tm.check("func", 1000))
    }

    @Test fun `same key within window returns true`() {
        val tm = TapMachine(doubleTapMs = 300L)
        tm.check("func", 1000)
        assertTrue(tm.check("func", 1200))
    }

    @Test fun `same key outside window returns false`() {
        val tm = TapMachine(doubleTapMs = 300L)
        tm.check("func", 1000)
        assertFalse(tm.check("func", 1500))
    }

    @Test fun `different key returns false`() {
        val tm = TapMachine(doubleTapMs = 300L)
        tm.check("func", 1000)
        assertFalse(tm.check("lower", 1200))
    }

    @Test fun `reset clears tracking so next tap is single`() {
        val tm = TapMachine(doubleTapMs = 300L)
        tm.check("func", 1000)
        tm.reset()
        assertFalse(tm.check("func", 1200))
    }

    @Test fun `exactly at boundary returns true`() {
        val tm = TapMachine(doubleTapMs = 300L)
        tm.check("func", 1000)
        assertTrue(tm.check("func", 1299))
    }

    @Test fun `just past boundary returns false`() {
        val tm = TapMachine(doubleTapMs = 300L)
        tm.check("func", 1000)
        assertFalse(tm.check("func", 1300))
    }

    @Test fun `two resets in a row is safe`() {
        val tm = TapMachine(doubleTapMs = 300L)
        tm.reset()
        tm.reset()
        assertFalse(tm.check("func", 1000))
    }

    @Test fun `three taps in a row counts as one double then one single`() {
        val tm = TapMachine(doubleTapMs = 300L)
        assertFalse(tm.check("func", 1000))
        assertTrue(tm.check("func", 1100))
        tm.reset()
        assertFalse(tm.check("func", 1200))
    }
}
