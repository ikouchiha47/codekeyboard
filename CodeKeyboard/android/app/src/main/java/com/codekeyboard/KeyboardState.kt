package com.codekeyboard

/**
 * Three-state latch cycle used for both modifiers and layers.
 *
 *   NONE → LATCHED → LOCKED → NONE
 *
 *   LATCHED: active for the next character key press, then auto-clears
 *   LOCKED:  stays active until explicitly toggled off
 */
enum class LatchState { NONE, LATCHED, LOCKED }

/**
 * Owns all runtime keyboard state: current layer and modifier keys.
 *
 * Rules (matching keyboard-sofle-layers.html behaviour):
 *   - Layer keys cycle NONE → LATCHED → LOCKED → NONE on each tap
 *   - Pressing a different layer key while one is active returns to base first
 *   - After a character key is committed, latched (not locked) modifiers clear
 *   - After a character key is committed, a latched (not locked) layer returns to base
 *   - Caps behaves as a toggle (NONE ↔ LOCKED); it does not have a LATCHED state
 */
class KeyboardState {

    var layer: String = "base"
        private set
    var layerState: LatchState = LatchState.NONE
        private set

    var shift: LatchState = LatchState.NONE
        private set
    var caps: LatchState = LatchState.NONE   // only NONE / LOCKED
        private set
    var ctrl: LatchState = LatchState.NONE
        private set
    var alt: LatchState = LatchState.NONE
        private set

    // ── Layer ─────────────────────────────────────────────────────────────────

    /** Cycle the named layer. Returns true if a re-render is needed. */
    fun cycleLayer(name: String): Boolean {
        if (layer == name) {
            layerState = when (layerState) {
                LatchState.NONE    -> LatchState.LATCHED
                LatchState.LATCHED -> LatchState.LOCKED
                LatchState.LOCKED  -> { layer = "base"; LatchState.NONE }
            }
        } else {
            // Switching to a different layer always starts from base
            layer      = name
            layerState = LatchState.LATCHED
        }
        return true
    }

    /** Call after a character key is committed. Clears latched-only state. */
    fun onCharCommitted() {
        if (shift == LatchState.LATCHED) shift = LatchState.NONE
        if (ctrl  == LatchState.LATCHED) ctrl  = LatchState.NONE
        if (alt   == LatchState.LATCHED) alt   = LatchState.NONE
        if (layerState == LatchState.LATCHED) {
            layer      = "base"
            layerState = LatchState.NONE
        }
    }

    // ── Modifiers ─────────────────────────────────────────────────────────────

    fun cycleShift() { shift = shift.next() }
    fun cycleCaps()  { caps  = if (caps == LatchState.NONE) LatchState.LOCKED else LatchState.NONE }
    fun cycleCtrl()  { ctrl  = ctrl.next() }
    fun cycleAlt()   { alt   = alt.next() }

    // ── Resolved state helpers ────────────────────────────────────────────────

    val isShiftActive: Boolean get() = shift != LatchState.NONE
    val isCapsActive:  Boolean get() = caps  != LatchState.NONE
    val isCtrlActive:  Boolean get() = ctrl  != LatchState.NONE
    val isAltActive:   Boolean get() = alt   != LatchState.NONE

    /**
     * Resolve which label to display / commit for [key] given current modifiers.
     * Returns null if the key has no character to commit (action-only key).
     */
    fun resolveLabel(key: KeyDef): String? {
        val useShift = isShiftActive || isCapsActive
        val raw = if (useShift && key.shift != null) key.shift else key.label
        // Alpha: apply caps/shift casing
        return if (raw.length == 1 && raw[0].isLetter()) {
            val upper = (isShiftActive && !isCapsActive) || (!isShiftActive && isCapsActive)
            if (upper) raw.uppercase() else raw.lowercase()
        } else {
            raw
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────────

    fun reset() {
        layer      = "base"
        layerState = LatchState.NONE
        shift      = LatchState.NONE
        caps       = LatchState.NONE
        ctrl       = LatchState.NONE
        alt        = LatchState.NONE
    }
}

// ── Extension ─────────────────────────────────────────────────────────────────

private fun LatchState.next() = when (this) {
    LatchState.NONE    -> LatchState.LATCHED
    LatchState.LATCHED -> LatchState.LOCKED
    LatchState.LOCKED  -> LatchState.NONE
}
