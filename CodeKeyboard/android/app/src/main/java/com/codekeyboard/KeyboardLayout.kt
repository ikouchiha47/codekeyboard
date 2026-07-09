package com.codekeyboard

/**
 * Base definition of a single key.
 */
data class KeyDef(
    val label: String,
    val action: String? = null,
    val shift: String? = null,
    val width: Float = 1f,
    val stagger: Int = 0
)

/**
 * Row alignment within the keyboard area.
 */
enum class Alignment {
    LEFT, RIGHT, CENTER, SPLIT
}

/**
 * One row of keys + metadata.
 */
data class Row(
    val keys: List<KeyDef>,
    val alignment: Alignment = Alignment.SPLIT,
    val stagger: List<Int> = emptyList()
)

/**
 * Unified grid layout used by all keyboard layouts (split or flat).
 */
data class GridLayoutData(
    val rows: List<Row>
)

/**
 * Core abstraction for a keyboard layout.
 * Every concrete layout (Sofle, Corne, QWERTY, etc.) must implement this.
 */
interface KeyboardLayout {
    val name: String
    val isSplit: Boolean
    val layers: Map<String, GridLayoutData>
}
