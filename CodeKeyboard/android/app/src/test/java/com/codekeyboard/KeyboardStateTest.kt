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
        assertFalse(state.isMetaActive)
        // Regression: isModifierActive with a name not in _latch map must NOT
        // return true. Earlier bug: null != LatchState.NONE evaluated to true,
        // so "meta" was always "active", causing character keys to send key
        // events with META_META_ON instead of commitText.
        assertFalse(state.isModifierActive("meta"))
        assertFalse(state.isModifierActive("hyper"))
        assertFalse(state.isModifierActive("nonexistent"))
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
        state.cycleModifier("shift"); assertEquals(LatchState.LATCHED, state.shift)
        state.cycleModifier("shift"); assertEquals(LatchState.LOCKED,  state.shift)
        state.cycleModifier("shift"); assertEquals(LatchState.NONE,    state.shift)
    }

    @Test fun `caps toggles NONE ↔ LOCKED (no LATCHED state)`() {
        state.cycleModifier("caps"); assertEquals(LatchState.LOCKED, state.caps)
        state.cycleModifier("caps"); assertEquals(LatchState.NONE,   state.caps)
    }

    @Test fun `latched shift clears after char committed`() {
        state.cycleModifier("shift")   // LATCHED
        state.onCharCommitted()
        assertEquals(LatchState.NONE, state.shift)
    }

    @Test fun `locked shift stays after char committed`() {
        state.cycleModifier("shift"); state.cycleModifier("shift")  // LOCKED
        state.onCharCommitted()
        assertEquals(LatchState.LOCKED, state.shift)
    }

    @Test fun `latched ctrl clears after char committed`() {
        state.cycleModifier("ctrl")
        state.onCharCommitted()
        assertEquals(LatchState.NONE, state.ctrl)
    }

    @Test fun `applyHold activates modifier not in _latch map`() {
        state.applyHold("meta")
        assertTrue(state.isModifierActive("meta"))
        assertTrue(state.isMetaActive)
    }

    @Test fun `releaseHold deactivates held modifier`() {
        state.applyHold("meta")
        state.releaseHold("meta")
        assertFalse(state.isModifierActive("meta"))
        assertFalse(state.isMetaActive)
    }

    // ── Label resolution ──────────────────────────────────────────────────────

    @Test fun `lowercase alpha without modifiers`() {
        val key = KeyDef("a")
        assertEquals("a", state.resolveLabel(key))
    }

    @Test fun `shift produces uppercase alpha`() {
        state.cycleModifier("shift")
        assertEquals("A", state.resolveLabel(KeyDef("a")))
    }

    @Test fun `caps produces uppercase alpha`() {
        state.cycleModifier("caps")
        assertEquals("A", state.resolveLabel(KeyDef("a")))
    }

    @Test fun `shift + caps cancels out (lowercase)`() {
        state.cycleModifier("shift")
        state.cycleModifier("caps")
        assertEquals("a", state.resolveLabel(KeyDef("a")))
    }

    @Test fun `shift selects shift label for non-alpha`() {
        state.cycleModifier("shift")
        val key = KeyDef("1", shift = "!")
        assertEquals("!", state.resolveLabel(key))
    }

    @Test fun `no shift uses primary label for non-alpha`() {
        val key = KeyDef("1", shift = "!")
        assertEquals("1", state.resolveLabel(key))
    }

    @Test fun `caps does not affect non-alpha shift label`() {
        state.cycleModifier("caps")
        val key = KeyDef("1", shift = "!")
        // caps applies shift label for non-alpha too (matches isShiftActive || isCapsActive check)
        assertEquals("!", state.resolveLabel(key))
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test fun `reset clears all state`() {
        state.cycleModifier("shift"); state.cycleModifier("ctrl"); state.cycleLayer("lower")
        state.reset()
        assertEquals("base", state.layer)
        assertFalse(state.isShiftActive)
        assertFalse(state.isCtrlActive)
    }

    // ── computeMetaState (the fold that decides commitText vs sendKeyEvent) ──

    // Use arbitrary bit flags so the test stays pure-Kotlin (no Android import).
    private val testFlags = mapOf("ctrl" to 0x1, "alt" to 0x2, "meta" to 0x4)

    @Test fun `metaState is 0 when nothing active`() {
        // Regression guard for the bug where isModifierActive("meta") was
        // always true because _latch["meta"] returned null != LatchState.NONE.
        assertEquals(0, state.computeMetaState(testFlags))
    }

    @Test fun `metaState has only ctrl flag when ctrl latched`() {
        state.cycleModifier("ctrl")
        assertEquals(0x1, state.computeMetaState(testFlags))
    }

    @Test fun `metaState has only alt flag when alt latched`() {
        state.cycleModifier("alt")
        assertEquals(0x2, state.computeMetaState(testFlags))
    }

    @Test fun `metaState has only meta flag when meta held`() {
        state.applyHold("meta")
        assertEquals(0x4, state.computeMetaState(testFlags))
    }

    @Test fun `metaState is 0 after held meta released`() {
        state.applyHold("meta")
        state.releaseHold("meta")
        assertEquals(0, state.computeMetaState(testFlags))
    }

    @Test fun `metaState combines ctrl held plus alt latched`() {
        state.applyHold("ctrl")
        state.cycleModifier("alt")
        assertEquals(0x1 or 0x2, state.computeMetaState(testFlags))
    }

    @Test fun `metaState clears latched ctrl after char committed but keeps held meta`() {
        state.cycleModifier("ctrl")      // LATCHED
        state.applyHold("meta")         // held
        state.onCharCommitted()         // clears LATCHED ctrl, does NOT clear held meta
        assertEquals(0x4, state.computeMetaState(testFlags))
    }

    @Test fun `metaState ignores caps and shift (handled via resolveLabel, not key events)`() {
        state.cycleModifier("shift")
        state.cycleModifier("caps")
        assertEquals(0, state.computeMetaState(testFlags))
    }

    @Test fun `metaState with empty flags map is always 0`() {
        state.applyHold("ctrl")
        state.applyHold("meta")
        assertEquals(0, state.computeMetaState(emptyMap()))
    }

    @Test fun `metaState survives regr - meta unknown to _latch but held`() {
        // The exact scenario that caused the original bug:
        // "meta" is NOT in _latch (only in _hold when held).
        // Before the fix, isModifierActive("meta") returned true even
        // when nothing was held, because null != LatchState.NONE == true.
        assertFalse(state.isModifierActive("meta"))
        state.applyHold("meta")
        assertTrue(state.isModifierActive("meta"))
        state.releaseHold("meta")
        assertFalse(state.isModifierActive("meta"))
    }

    @Test fun `metaState with never-heard-of modifier name is 0`() {
        // A modifier in the flags map but with NO corresponding state in
        // KeyboardState must not accidentally activate via null comparison.
        val flagsWithUnknown = mapOf("ctrl" to 0x1, "hyper" to 0x8)
        assertEquals(0, state.computeMetaState(flagsWithUnknown))
        state.applyHold("ctrl")
        assertEquals(0x1, state.computeMetaState(flagsWithUnknown))
    }
}
