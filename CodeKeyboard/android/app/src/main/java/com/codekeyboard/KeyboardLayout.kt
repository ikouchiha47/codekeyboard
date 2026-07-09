package com.codekeyboard

/**
 * Base definition of a single key.
 * Used by both split and flat layouts.
 */
data class KeyDef(
    val label: String,
    val action: String? = null,
    val shift: String? = null,
    val width: Float = 1f,
    val stagger: Int = 0
)

/**
 * Represents one row of keys.
 */
typealias KeyRow = List<KeyDef>

/**
 * Data for a split keyboard (left + right halves).
 */
data class SplitLayoutData(
    val left: List<KeyRow>,
    val right: List<KeyRow>,
    val staggerLeft: List<Int>,
    val staggerRight: List<Int>
)

/**
 * Data for a flat (non-split) keyboard.
 */
data class FlatLayoutData(
    val rows: List<KeyRow>
)

/**
 * Sealed type so a layout can be either split or flat.
 */
sealed class LayoutData {
    data class Split(val data: SplitLayoutData) : LayoutData()
    data class Flat(val data: FlatLayoutData) : LayoutData()
}

/**
 * Core abstraction for a keyboard layout.
 * Every concrete layout (Sofle, Corne, QWERTY, etc.) must implement this.
 */
interface KeyboardLayout {
    val name: String
    val isSplit: Boolean
    val layers: Map<String, LayoutData>
}
