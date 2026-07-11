package com.codekeyboard

class TapMachine(
    private val doubleTapMs: Long = 300L
) {
    private var lastTapTime = 0L
    private var lastTapKey = ""

    fun check(name: String, now: Long): Boolean {
        val isDouble = name == lastTapKey && now - lastTapTime in 0 until doubleTapMs
        lastTapTime = now
        lastTapKey = name
        return isDouble
    }

    fun reset() {
        lastTapTime = 0L
        lastTapKey = ""
    }
}
