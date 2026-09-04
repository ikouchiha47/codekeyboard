package com.codekeyboard

enum class LatchState { NONE, LATCHED, LOCKED }

class KeyboardState {

    companion object {
        val CYCLE_MODIFIERS      = setOf("shift", "ctrl", "alt")
        val HOLD_STATE_MODIFIERS = setOf("ctrl", "shift", "alt", "meta")
        val LAYER_HOLDS          = setOf("lower", "raise", "adj", "func")
    }

    var layer: String = "base"
        private set
    var layerState: LatchState = LatchState.NONE
        private set

    // Transient hold-tap state (active only while finger is held)
    var layerHeld: String? = null

    // Label of the key currently being held (for visual feedback)
    var heldKeyLabel: String? = null

    // ── Sentence Case ────────────────────────────────────────────────────
    // Settings-mirrored toggle — set by the IME layer from KeyboardSettings so
    // this class stays free of Android/SharedPreferences dependencies.
    //
    // Implemented as a real Shift latch (not a bespoke flag) so it gets
    // everything Shift already has for free: the key lights up, every
    // existing isShiftActive check respects it, and tapping Shift cancels it
    // exactly like cancelling a manually-latched Shift.
    var sentenceCaseEnabled: Boolean = true

    // Set when sentence-ending punctuation (. ! ?) is committed; cleared on next char.
    // If the next char is a space, we arm Sentence Case shift.
    private var _pendingSentenceArm: Boolean = false

    // ── Generic modifier storage ──────────────────────────────────────────
    private val _latch = mutableMapOf(
        "shift" to LatchState.NONE,
        "ctrl"  to LatchState.NONE,
        "alt"   to LatchState.NONE,
    )
    private val _hold = mutableSetOf<String>()
    private val _tap  = mapOf(
        "shift" to TapMachine(),
        "ctrl"  to TapMachine(),
        "alt"   to TapMachine(),
    )

    // ── Convenience properties (backward-compat) ──────────────────────────
    val shift: LatchState get() = _latch["shift"] ?: LatchState.NONE
    val ctrl:  LatchState get() = _latch["ctrl"]  ?: LatchState.NONE
    val alt:   LatchState get() = _latch["alt"]   ?: LatchState.NONE

    val shiftHeld: Boolean get() = "shift" in _hold
    val ctrlHeld:  Boolean get() = "ctrl"  in _hold
    val altHeld:   Boolean get() = "alt"   in _hold
    val metaHeld:  Boolean get() = "meta"  in _hold

    // ── Active checks ─────────────────────────────────────────────────────
    fun isModifierActive(name: String): Boolean =
        (_latch[name] ?: LatchState.NONE) != LatchState.NONE || name in _hold

    val isShiftActive: Boolean get() = isModifierActive("shift")
    val isCtrlActive:  Boolean get() = isModifierActive("ctrl")
    val isAltActive:   Boolean get() = isModifierActive("alt")
    val isMetaActive:  Boolean get() = "meta" in _hold

    val effectiveLayer: String get() = layerHeld ?: layer

    /** Names of modifiers currently active — for building meta state flags. */
    val activeModifierNames: Set<String>
        get() = buildSet {
            for ((name, state) in _latch) {
                if (state != LatchState.NONE) add(name)
            }
            addAll(_hold)
        }

    /**
     * Fold active modifiers into a combined meta-state integer.
     *
     * [flags] maps modifier name → bit-flag (e.g. KeyEvent.META_CTRL_ON).
     * Returns 0 when no modifier is active, which signals "use commitText"
     * instead of sending a key event.
     */
    fun computeMetaState(flags: Map<String, Int>): Int =
        flags.entries.fold(0) { acc, (name, flag) ->
            if (isModifierActive(name)) acc or flag else acc
        }

    // ── Generic modifier cycling ─────────────────────────────────────────
    fun cycleModifier(name: String) {
        val tap = _tap[name] ?: return
        val now = System.currentTimeMillis()
        val isDouble = tap.check(name, now)

        when (_latch[name]) {
            LatchState.LOCKED -> _latch[name] = LatchState.NONE
            else -> {
                if (isDouble) {
                    _latch[name] = LatchState.LOCKED
                    tap.reset()
                } else if (_latch[name] == LatchState.LATCHED) {
                    _latch[name] = LatchState.NONE
                } else {
                    _latch[name] = LatchState.LATCHED
                }
            }
        }
    }

    // ── Layer cycling (stays special — layers switch, not just toggle) ──
    fun cycleLayer(name: String): Boolean {
        val now = System.currentTimeMillis()
        val isDouble = layerTap.check(name, now)

        if (layerState == LatchState.LOCKED && layer == name) {
            layer = "base"; layerState = LatchState.NONE
            return true
        }

        if (isDouble) {
            layer = name; layerState = LatchState.LOCKED
            layerTap.reset()
            return true
        }

        if (layerState == LatchState.LATCHED && layer == name) {
            layer = "base"; layerState = LatchState.NONE
            return true
        }

        if (layerState != LatchState.NONE && layer != name) {
            layer = name; layerState = LatchState.LATCHED
            return true
        }

        layer = name; layerState = LatchState.LATCHED
        return true
    }

    // ── Hold-tap state transitions ──────────────────────────────────────
    fun applyHold(action: String) {
        when {
            action in HOLD_STATE_MODIFIERS -> _hold.add(action)
            action in LAYER_HOLDS -> {
                if (layerHeld == null) {
                    layerHeld = action
                } else {
                    // Tri-layer: order of thumb presses determines which overlay layer.
                    // Hold LWR first then RSE → ADJ (adjust/media).
                    // Hold RSE first then LWR → FUNC (editor actions).
                    val tri = when {
                        layerHeld == "lower" && action == "raise" -> "adj"
                        layerHeld == "raise" && action == "lower" -> "func"
                        else -> null
                    }
                    if (tri != null) layerHeld = tri
                }
            }
        }
    }

    fun releaseHold(action: String) {
        when {
            action in HOLD_STATE_MODIFIERS -> _hold.remove(action)
            action in LAYER_HOLDS -> {
                if (layerHeld == action) {
                    layerHeld = null
                } else if (layerHeld in setOf("adj", "func") && action in setOf("lower", "raise")) {
                    // Releasing either constituent key exits the tri-layer.
                    layerHeld = null
                }
            }
        }
    }

    // ── Char-committed / label resolution ─────────────────────────────────
    // Set when a sentence-ending punctuation mark was just committed, cleared
    // on the next commit. Shift only arms once a space actually follows it
    // (standard ". " convention) — arming immediately on the punctuation
    // itself would capitalize text typed right after "." with no space
    // (e.g. mid-word in "e.g.foo") and made the Shift key visibly light up
    // before the sentence had actually ended.
    /**
     * [committedText] is what was just sent to the input field (a single
     * character, or a whole flushed word). Pass null for commits that aren't
     * real typed text (e.g. a Ctrl+letter shortcut) — Shift's latch then
     * clears unconditionally, matching the pre-Sentence-Case behavior.
     *
     * A latched Shift only clears here when the committed text was actually
     * a letter — a digit or extra space right after Sentence Case arms Shift
     * (see below) shouldn't silently cancel it before the real next letter
     * arrives. Same rule applies whether the latch came from a manual Shift
     * tap or from Sentence Case; they're the same state.
     */
    fun onCharCommitted(committedText: String? = null) {
        val committedChar = committedText?.lastOrNull()
        val isLetter = committedChar?.isLetter() == true
        val clearShift = committedText == null || isLetter

        // Sentence Case: ". " / "! " / "? " sequence arms shift for the next letter.
        if (committedChar == ' ' && _pendingSentenceArm) {
            armSentenceCaseShift()
        }
        _pendingSentenceArm = committedChar == '.' || committedChar == '!' || committedChar == '?'

        if (clearShift && _latch["shift"] == LatchState.LATCHED) {
            _latch["shift"] = LatchState.NONE
        }
        for (name in CYCLE_MODIFIERS) {
            if (name == "shift") continue
            if (_latch[name] == LatchState.LATCHED) _latch[name] = LatchState.NONE
        }
        if (layerState == LatchState.LATCHED) {
            layer      = "base"
            layerState = LatchState.NONE
        }
        _tap.values.forEach { it.reset() }
        layerTap.reset()
    }

    /**
     * Arms Sentence Case's Shift latch for the very next letter, without
     * clobbering an existing manual Shift state (latched or locked). Called by the IME layer
     * when a field gains focus and appears to be at a sentence start (empty,
     * or already ending in ". "/"! "/"? ").
     */
    fun armSentenceCaseShift() {
        if (sentenceCaseEnabled && _latch["shift"] == LatchState.NONE) {
            _latch["shift"] = LatchState.LATCHED
        }
    }

    fun resolveLabel(key: KeyDef): String? {
        val useShiftValue = isShiftActive && !key.ignoreLockedShift
        val raw = if (useShiftValue && key.shift != null) key.shift else key.label
        return if (raw.length == 1 && raw[0].isLetter()) {
            if (isShiftActive) raw.uppercase() else raw.lowercase()
        } else {
            raw
        }
    }

    fun reset() {
        layer      = "base"
        layerState = LatchState.NONE
        layerHeld  = null
        heldKeyLabel = null
        for (key in _latch.keys) _latch[key] = LatchState.NONE
        _hold.clear()
        _tap.values.forEach { it.reset() }
        layerTap.reset()
    }

    // Layer tap machine (separate from modifier tap machines)
    private val layerTap = TapMachine()
}
