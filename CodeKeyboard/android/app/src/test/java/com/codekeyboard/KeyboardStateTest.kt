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

    @Test fun `tapping different layer from locked latches new without locking it`() {
        // Lock lower, then tap raise — should latch raise, not keep lower locked
        state.cycleLayer("lower")
        state.cycleLayer("lower")                  // lower LOCKED (double-tap)
        assertEquals(LatchState.LOCKED, state.layerState)
        state.cycleLayer("raise")
        assertEquals("raise", state.layer)
        assertEquals(LatchState.LATCHED, state.layerState)
    }

    @Test fun `third tap on latched layer (after lock) returns to base`() {
        // Tap 1 → LATCHED, double-tap → LOCKED, tap again → base.
        // This is distinct from the sequential three-tap test and confirms
        // the locked→base transition is reachable via the real call path.
        state.cycleLayer("lower")                  // LATCHED
        state.cycleLayer("lower")                  // double-tap → LOCKED
        assertEquals(LatchState.LOCKED, state.layerState)
        state.cycleLayer("lower")                  // tap while locked → base
        assertEquals("base", state.layer)
        assertEquals(LatchState.NONE, state.layerState)
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

    @Test fun `locked shift (double-tap, the caps-lock behavior) produces uppercase alpha`() {
        state.cycleModifier("shift") // LATCHED
        state.cycleModifier("shift") // double-tap within window -> LOCKED
        assertEquals(LatchState.LOCKED, state.shift)
        assertEquals("A", state.resolveLabel(KeyDef("a")))
    }

    @Test fun `shift selects shift label for non-alpha`() {
        state.cycleModifier("shift")
        val key = KeyDef("1", shift = "!")
        assertEquals("!", state.resolveLabel(key))
    }

    @Test fun `ignoreLockedShift key keeps its primary label under locked shift`() {
        state.cycleModifier("shift") // LATCHED
        state.cycleModifier("shift") // double-tap -> LOCKED
        val comma = KeyDef(",", shift = "<", ignoreLockedShift = true)
        assertEquals(",", state.resolveLabel(comma))
    }

    @Test fun `ignoreLockedShift key keeps its primary label under a temporary (latched) shift too`() {
        state.cycleModifier("shift") // LATCHED, not LOCKED
        val comma = KeyDef(",", shift = "<", ignoreLockedShift = true)
        assertEquals(",", state.resolveLabel(comma))
    }

    @Test fun `ignoreLockedShift has no effect on a key without it set`() {
        state.cycleModifier("shift")
        state.cycleModifier("shift") // LOCKED
        val semicolon = KeyDef(";", shift = ":")
        assertEquals(":", state.resolveLabel(semicolon))
    }

    @Test fun `no shift uses primary label for non-alpha`() {
        val key = KeyDef("1", shift = "!")
        assertEquals("1", state.resolveLabel(key))
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    @Test fun `reset clears all state`() {
        state.cycleModifier("shift"); state.cycleModifier("ctrl"); state.cycleLayer("lower")
        state.reset()
        assertEquals("base", state.layer)
        assertFalse(state.isShiftActive)
        assertFalse(state.isCtrlActive)
    }

    // ── Sentence Case (implemented as a real Shift latch) ──────────────────────

    @Test fun `armSentenceCaseShift latches shift`() {
        state.armSentenceCaseShift()
        assertTrue(state.isShiftActive)
        assertEquals("A", state.resolveLabel(KeyDef("a")))
    }

    @Test fun `armSentenceCaseShift does nothing when disabled`() {
        state.sentenceCaseEnabled = false
        state.armSentenceCaseShift()
        assertFalse(state.isShiftActive)
    }

    @Test fun `armSentenceCaseShift does not downgrade an already-locked shift`() {
        state.cycleModifier("shift") // -> LATCHED
        state.cycleModifier("shift") // double-tap within the window -> LOCKED
        state.armSentenceCaseShift()
        assertEquals(LatchState.LOCKED, state.shift)
    }

    @Test fun `armSentenceCaseShift does not override an already-latched shift`() {
        state.cycleModifier("shift") // LATCHED
        state.armSentenceCaseShift()
        assertEquals(LatchState.LATCHED, state.shift) // unchanged, not double-latched or locked
    }

    @Test fun `committing a letter clears the sentence-case shift latch`() {
        state.armSentenceCaseShift()
        state.onCharCommitted("a")
        assertFalse(state.isShiftActive)
    }

    @Test fun `committing a period alone does not latch shift yet`() {
        // Arming happens on the SPACE that follows a sentence-ending mark, not
        // on the mark itself — otherwise text typed with no space after "."
        // (e.g. "e.g.foo") would get capitalized, and the Shift key would
        // visibly light up mid-punctuation before the sentence actually ended.
        state.onCharCommitted(".")
        assertFalse(state.isShiftActive)
    }

    @Test fun `committing a period then a space latches shift for the next letter`() {
        state.onCharCommitted(".")
        assertFalse(state.isShiftActive)
        state.onCharCommitted(" ")
        assertTrue(state.isShiftActive)
    }

    @Test fun `committing exclamation or question mark then a space latches shift`() {
        state.onCharCommitted("!")
        state.onCharCommitted(" ")
        assertTrue(state.isShiftActive)
        state.onCharCommitted("a") // consume it
        assertFalse(state.isShiftActive)
        state.onCharCommitted("?")
        state.onCharCommitted(" ")
        assertTrue(state.isShiftActive)
    }

    @Test fun `a non-space character after sentence-ending punctuation cancels the pending arm`() {
        // "e.g.foo" — the "." is immediately followed by another letter, not a
        // space, so this was never a real sentence break and should not arm.
        state.onCharCommitted(".")
        state.onCharCommitted("f")
        assertFalse(state.isShiftActive)
    }

    @Test fun `committing a digit after the space-armed latch leaves it untouched`() {
        state.onCharCommitted(".")
        state.onCharCommitted(" ")
        assertTrue(state.isShiftActive)
        state.onCharCommitted("5")
        assertTrue(state.isShiftActive)
    }

    @Test fun `committing a digit still clears a manually-latched shift's tap machine but not the latch itself`() {
        // Same rule applies to a plain manual Shift tap, not just Sentence Case —
        // typing a digit right after tapping Shift shouldn't silently cancel it.
        state.cycleModifier("shift")
        assertEquals(LatchState.LATCHED, state.shift)
        state.onCharCommitted("5")
        assertEquals(LatchState.LATCHED, state.shift)
    }

    @Test fun `disabled does not latch shift on sentence-ending punctuation`() {
        state.sentenceCaseEnabled = false
        state.onCharCommitted(".")
        state.onCharCommitted(" ")
        assertFalse(state.isShiftActive)
    }

    @Test fun `null committed text clears shift unconditionally (ctrl+letter shortcut path)`() {
        state.armSentenceCaseShift()
        state.onCharCommitted(null)
        assertFalse(state.isShiftActive)
    }

    @Test fun `full sentence flow — arm, type, disarm, arm again`() {
        // "Hi. there" — after committing "." + " ", the "t" should be forced
        // uppercase; after that letter, the latch clears until the next ". ".
        assertFalse(state.isShiftActive)
        state.onCharCommitted(".")
        assertFalse(state.isShiftActive) // not yet — waiting on the space
        state.onCharCommitted(" ")
        assertTrue(state.isShiftActive)
        assertEquals("T", state.resolveLabel(KeyDef("t")))
        state.onCharCommitted("T")
        assertFalse(state.isShiftActive)
        assertEquals("h", state.resolveLabel(KeyDef("h")))
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

    @Test fun `metaState ignores shift (handled via resolveLabel, not key events)`() {
        state.cycleModifier("shift")
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
