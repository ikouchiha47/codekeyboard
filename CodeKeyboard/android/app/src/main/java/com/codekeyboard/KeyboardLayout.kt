package com.codekeyboard

import android.graphics.RectF

/**
 * A single key's logical definition. No geometry here.
 *
 * @param label   Text displayed on the key face
 * @param action  Optional action identifier (e.g. "shift", "enter", "lower")
 * @param shift   Optional label shown when shift/caps is active
 * @param width   Relative width unit (1f = one standard key width)
 */
data class KeyDef(
    val label: String,
    val action: String? = null,
    val shift: String? = null,
    val width: Float = 1f
)

/**
 * A key with a computed screen-space rectangle.
 * This is the only thing the renderer needs to know about a key.
 */
data class PositionedKey(
    val key: KeyDef,
    val rect: RectF
)

/**
 * Contract every keyboard layout must fulfil.
 *
 * Implementations own:
 *   - the key definitions for every layer
 *   - the geometry computation (column stagger, split gap, thumb cluster, etc.)
 *
 * The renderer knows nothing beyond List<PositionedKey>.
 * Adding a new layout (Corne, QWERTY, custom) = implement this interface only.
 */
interface KeyboardLayoutComputer {
    /** Human-readable name shown in settings */
    val name: String

    /** All layer identifiers this layout supports */
    val layers: List<String>

    /**
     * Compute pixel-exact positions for every key in [layer] given
     * the available [screenWidthPx]. Returns an empty list if [layer]
     * is unknown.
     *
     * The returned rects are used for both drawing and touch hit-testing,
     * so they must be the single source of truth.
     */
    fun compute(screenWidthPx: Int, layer: String): List<PositionedKey>

    /**
     * Total view height in pixels needed to display this layout.
     * Must be consistent with whatever [compute] returns.
     */
    fun heightPx(screenWidthPx: Int): Int
}
