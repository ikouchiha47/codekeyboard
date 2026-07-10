package com.codekeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class KeyboardStateTest {

    private lateinit var state: KeyboardState

    @Before fun setUp() { state = KeyboardState() }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test fun `initial layer is base`() {
        assertEquals("base", state.layer)
    }

    @Test fun `initial modifiers are all inactive`() {
        assertFalse(state.isShiftActive)
        assertFalse(state.isCapsActive)
        assertFalse(state.isCtrlActive)
        assertFalse(state.isAltActive)
    }

    // ── Layer cycling ─────────────────────────────────────────────────────────

    @Test fun `first tap on layer latches it`() {
        state.cycleLayer("lower")
        assertEquals("lower", state.layer)
        assertEquals(LatchState.LATCHED, state.layerState)
    }

    @Test fun `second tap on same layer locks it`() {
        state.cycleLayer("lower")
        state.cycleLayer("lower")
        assertEquals("lower", state.layer)
        assertEquals(LatchState.LOCKED, state.layerState)
    }

    @Test fun `third tap on locked layer returns to base`() {
        state.cycleLayer("lower")
        state.cycleLayer("lower")
        state.cycleLayer("lower")
        assertEquals("base", state.layer)
        assertEquals(LatchState.NONE, state.layerState)
    }

    @Test fun `tapping different layer from latched returns to base then latches new`() {
        state.cycleLayer("lower")                  // lower LATCHED
        state.cycleLayer("raise")                  // raise LATCHED (not lower→LOCKED)
        assertEquals("raise", state.layer)
        assertEquals(LatchState.LATCHED, state.layerState)
    }

    @Test fun `latched layer returns to base after char committed`() {
        state.cycleLayer("lower")   // LATCHED
        state.onCharCommitted()
        assertEquals("base", state.layer)
        assertEquals(LatchState.NONE, state.layerState)
    }

    @Test fun `locked layer stays after char committed`() {
        state.cycleLayer("lower")
        state.cycleLayer("lower")  // LOCKED
        state.onCharCommitted()
        assertEquals("lower", state.layer)
        assertEquals(LatchState.LOCKED, state.layerState)
    }

    // ── Modifier cycling ──────────────────────────────────────────────────────

    @Test fun `shift cycles NONE → LATCHED → LOCKED → NONE`() {
        assertEquals(LatchState.NONE, state.shift)
        state.cycleShift(); assertEquals(LatchState.LATCHED, state.shift)
        state.cycleShift(); assertEquals(LatchState.LOCKED,  state.shift)
        state.cycleShift(); assertEquals(LatchState.NONE,    state.shift)
    }

    @Test fun `caps toggles NONE ↔ LOCKED (no LATCHED state)`() {
        state.cycleCaps(); assertEquals(LatchState.LOCKED, state.caps)
        state.cycleCaps(); assertEquals(LatchState.NONE,   state.caps)
    }

    @Test fun `latched shift clears after char committed`() {
        state.cycleShift()   // LATCHED
        state.onCharCommitted()
        assertEquals(LatchState.NONE, state.shift)
    }

    @Test fun `locked shift stays after char committed`() {
        state.cycleShift(); state.cycleShift()  // LOCKED
        state.onCharCommitted()
        assertEquals(LatchState.LOCKED, state.shift)
    }

    @Test fun `latched ctrl clears after char committed`() {
        state.cycleCtrl()
        state.onCharCommitted()
        assertEquals(LatchState.NONE, state.ctrl)
    }

    // ── Label resolution ──────────────────────────────────────────────────────

    @Test fun `lowercase alpha without modifiers`() {
        val key = KeyDef("a")
        assertEquals("a", state.resolveLabel(key))
    }

    @Test fun `shift produces uppercase alpha`() {
        state.cycleShift()
        assertEquals("A", state.resolveLabel(KeyDef("a")))
    }

    @Test fun `caps produces uppercase alpha`() {
        state.cycleCaps()
        assertEquals("A", state.resolveLabel(KeyDef("a")))
    }

    @Test fun `shift + caps cancels out (lowercase)`() {
        state.cycleShift()
        state.cycleCaps()
        assertEquals("a", state.resolveLabel(KeyDef("a")))
    }

    @Test fun `shift selects shift label for non-alpha`() {
        state.cycleShift()
        val key = KeyDef("1", shift = "!")
        assertEquals("!", state.resolveLabel(key))
    }

    @Test fun `no shift uses primary label for non-alpha`() {
        val key = KeyDef("1", shift = "!")
        assertEquals("1", state.resolveLabel(key))
    }

    @Test fun `caps does not affect non-alpha shift label`() {
        state.cycleCaps()
        val key = KeyDef("1", shift = "!")
        // caps applies shift label for non-alpha too (matches isShiftActive || isCapsActive check)
        assertEquals("!", state.resolveLabel(key))
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test fun `reset clears all state`() {
        state.cycleShift(); state.cycleCtrl(); state.cycleLayer("lower")
        state.reset()
        assertEquals("base", state.layer)
        assertFalse(state.isShiftActive)
        assertFalse(state.isCtrlActive)
    }
}
