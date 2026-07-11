package com.codekeyboard

enum class LatchState { NONE, LATCHED, LOCKED }

class KeyboardState {

    var layer: String = "base"
        private set
    var layerState: LatchState = LatchState.NONE
        private set

    var shift: LatchState = LatchState.NONE
        private set
    var caps: LatchState = LatchState.NONE
        private set
    var ctrl: LatchState = LatchState.NONE
        private set
    var alt: LatchState = LatchState.NONE
        private set

    // Transient hold-tap state (active only while finger is held)
    var layerHeld: String? = null
    var shiftHeld: Boolean = false
    var ctrlHeld: Boolean = false
    var altHeld: Boolean = false
    var metaHeld: Boolean = false

    // Label of the key currently being held (for visual feedback)
    var heldKeyLabel: String? = null

    private val layerTap = TapMachine()
    private val shiftTap = TapMachine()
    private val ctrlTap  = TapMachine()
    private val altTap   = TapMachine()

    val effectiveLayer: String get() = layerHeld ?: layer

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

    fun onCharCommitted() {
        if (shift == LatchState.LATCHED) shift = LatchState.NONE
        if (ctrl  == LatchState.LATCHED) ctrl  = LatchState.NONE
        if (alt   == LatchState.LATCHED) alt   = LatchState.NONE
        if (layerState == LatchState.LATCHED) {
            layer      = "base"
            layerState = LatchState.NONE
        }
        layerTap.reset()
        shiftTap.reset()
        ctrlTap.reset()
        altTap.reset()
    }

    fun cycleShift() {
        val now = System.currentTimeMillis()
        val isDouble = shiftTap.check("shift", now)

        if (shift == LatchState.LOCKED) {
            shift = LatchState.NONE
            return
        }

        if (isDouble) {
            shift = LatchState.LOCKED
            shiftTap.reset()
            return
        }

        if (shift == LatchState.LATCHED) {
            shift = LatchState.NONE
            return
        }

        shift = LatchState.LATCHED
    }

    fun cycleCaps() {
        caps = if (caps == LatchState.NONE) LatchState.LOCKED else LatchState.NONE
    }

    fun cycleCtrl() {
        val now = System.currentTimeMillis()
        val isDouble = ctrlTap.check("ctrl", now)

        if (ctrl == LatchState.LOCKED) {
            ctrl = LatchState.NONE
            return
        }

        if (isDouble) {
            ctrl = LatchState.LOCKED
            ctrlTap.reset()
            return
        }

        if (ctrl == LatchState.LATCHED) {
            ctrl = LatchState.NONE
            return
        }

        ctrl = LatchState.LATCHED
    }

    fun cycleAlt() {
        val now = System.currentTimeMillis()
        val isDouble = altTap.check("alt", now)

        if (alt == LatchState.LOCKED) {
            alt = LatchState.NONE
            return
        }

        if (isDouble) {
            alt = LatchState.LOCKED
            altTap.reset()
            return
        }

        if (alt == LatchState.LATCHED) {
            alt = LatchState.NONE
            return
        }

        alt = LatchState.LATCHED
    }

    val isShiftActive: Boolean get() = shift != LatchState.NONE || shiftHeld
    val isCapsActive:  Boolean get() = caps  != LatchState.NONE
    val isCtrlActive:  Boolean get() = ctrl  != LatchState.NONE || ctrlHeld
    val isAltActive:   Boolean get() = alt   != LatchState.NONE || altHeld
    val isMetaActive:  Boolean get() = metaHeld

    fun applyHold(action: String) {
        when (action) {
            "ctrl"   -> ctrlHeld   = true
            "shift"  -> shiftHeld  = true
            "alt"    -> altHeld    = true
            "meta"   -> metaHeld   = true
            "lower", "raise", "adj", "func" -> layerHeld = action
        }
    }

    fun releaseHold(action: String) {
        when (action) {
            "ctrl"   -> ctrlHeld   = false
            "shift"  -> shiftHeld  = false
            "alt"    -> altHeld    = false
            "meta"   -> metaHeld   = false
            "lower", "raise", "adj", "func" -> {
                if (layerHeld == action) layerHeld = null
            }
        }
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
        shift      = LatchState.NONE
        caps       = LatchState.NONE
        ctrl       = LatchState.NONE
        alt        = LatchState.NONE
        layerHeld  = null
        shiftHeld  = false
        ctrlHeld   = false
        altHeld    = false
        metaHeld   = false
        heldKeyLabel = null
        layerTap.reset()
        shiftTap.reset()
        ctrlTap.reset()
        altTap.reset()
    }
}
