package com.codekeyboard

enum class LatchState { NONE, LATCHED, LOCKED }

class KeyboardState {

    companion object {
        val CYCLE_MODIFIERS      = setOf("shift", "ctrl", "alt")
        val TOGGLE_MODIFIERS     = setOf("caps")
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

    // ── Generic modifier storage ──────────────────────────────────────────
    private val _latch = mutableMapOf(
        "shift" to LatchState.NONE,
        "ctrl"  to LatchState.NONE,
        "alt"   to LatchState.NONE,
        "caps"  to LatchState.NONE,
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
    val caps:  LatchState get() = _latch["caps"]  ?: LatchState.NONE

    val shiftHeld: Boolean get() = "shift" in _hold
    val ctrlHeld:  Boolean get() = "ctrl"  in _hold
    val altHeld:   Boolean get() = "alt"   in _hold
    val metaHeld:  Boolean get() = "meta"  in _hold

    // ── Active checks ─────────────────────────────────────────────────────
    fun isModifierActive(name: String): Boolean =
        _latch[name] != LatchState.NONE || name in _hold

    val isShiftActive: Boolean get() = isModifierActive("shift") || isModifierActive("caps")
    val isCapsActive:  Boolean get() = caps != LatchState.NONE
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

    // ── Generic modifier cycling ─────────────────────────────────────────
    fun cycleModifier(name: String) {
        if (name == "caps") {
            _latch["caps"] = if (_latch["caps"] == LatchState.NONE) LatchState.LOCKED else LatchState.NONE
            return
        }
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
            action in LAYER_HOLDS          -> layerHeld = action
        }
    }

    fun releaseHold(action: String) {
        when {
            action in HOLD_STATE_MODIFIERS -> _hold.remove(action)
            action in LAYER_HOLDS          -> { if (layerHeld == action) layerHeld = null }
        }
    }

    // ── Char-committed / label resolution ─────────────────────────────────
    fun onCharCommitted() {
        for (name in CYCLE_MODIFIERS) {
            if (_latch[name] == LatchState.LATCHED) _latch[name] = LatchState.NONE
        }
        if (layerState == LatchState.LATCHED) {
            layer      = "base"
            layerState = LatchState.NONE
        }
        _tap.values.forEach { it.reset() }
        layerTap.reset()
    }

    fun resolveLabel(key: KeyDef): String? {
        val useShift = isShiftActive || isCapsActive
        val raw = if (useShift && key.shift != null) key.shift else key.label
        return if (raw.length == 1 && raw[0].isLetter()) {
            val upper = (isShiftActive && !isCapsActive) || (!isShiftActive && isCapsActive)
            if (upper) raw.uppercase() else raw.lowercase()
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
