package com.codekeyboard

/**
 * Keyboard-layout adjacency for proximity-aware typo correction (ADR-013).
 *
 * The core insight: on a physical keyboard, most mistypes are fat-finger taps
 * that hit a *physically adjacent* key, not random spelling errors. So a
 * substitution of a neighbouring key should cost less than a substitution of a
 * distant key. The adjacency map is layout-specific — QWERTY, Colemak and
 * Dvorak all place characters on different physical keys.
 *
 * This interface is the single seam the correction path depends on, so the
 * active keymap can be injected at runtime (the IME lets the user switch
 * layouts).
 */
interface KeyAdjacency {
    /**
     * Cost of substituting `typed` where `intended` was the target character.
     * 0.0 for identical, 0.5 for a physically adjacent key, 1.0 otherwise.
     */
    fun substitutionCost(typed: Char, intended: Char): Float
}

/** No adjacency model — every substitution costs the full 1.0 (plain edit distance). */
object NoAdjacency : KeyAdjacency {
    override fun substitutionCost(typed: Char, intended: Char): Float =
        if (typed == intended) 0f else 1.0f
}

/**
 * QWERTY physical-key adjacency.
 *
 * Two characters are neighbours if their keys touch horizontally, vertically,
 * or diagonally on the standard QWERTY layout. The map below is the letter
 * portion; punctuation/number keys are omitted because the correction path
 * operates on word characters only.
 */
class QwertyAdjacency : KeyAdjacency {

    private val neighbours: Map<Char, Set<Char>> = mapOf(
        'q' to setOf('w', 'a', 's'),
        'w' to setOf('q', 'e', 'a', 's', 'd'),
        'e' to setOf('w', 'r', 's', 'd', 'f'),
        'r' to setOf('e', 't', 'd', 'f', 'g'),
        't' to setOf('r', 'y', 'f', 'g', 'h'),
        'y' to setOf('t', 'u', 'g', 'h', 'j'),
        'u' to setOf('y', 'i', 'h', 'j', 'k'),
        'i' to setOf('u', 'o', 'j', 'k', 'l'),
        'o' to setOf('i', 'p', 'k', 'l'),
        'p' to setOf('o', 'l'),
        'a' to setOf('q', 'w', 's', 'z'),
        's' to setOf('a', 'w', 'e', 'd', 'x', 'z'),
        'd' to setOf('s', 'e', 'r', 'f', 'c', 'x'),
        'f' to setOf('d', 'r', 't', 'g', 'v', 'c'),
        'g' to setOf('f', 't', 'y', 'h', 'b', 'v'),
        'h' to setOf('g', 'y', 'u', 'j', 'n', 'b'),
        'j' to setOf('h', 'u', 'i', 'k', 'm', 'n'),
        'k' to setOf('j', 'i', 'o', 'l', 'm'),
        'l' to setOf('k', 'o', 'p'),
        'z' to setOf('a', 's', 'x'),
        'x' to setOf('z', 's', 'd', 'c'),
        'c' to setOf('x', 'd', 'f', 'v'),
        'v' to setOf('c', 'f', 'g', 'b'),
        'b' to setOf('v', 'g', 'h', 'n'),
        'n' to setOf('b', 'h', 'j', 'm'),
        'm' to setOf('n', 'j', 'k'),
    )

    override fun substitutionCost(typed: Char, intended: Char): Float {
        if (typed == intended) return 0f
        return if (neighbours[intended]?.contains(typed) == true) 0.5f else 1.0f
    }
}

// TODO: Colemak / Dvorak adjacency maps — the physical key positions differ.
// Delegate to NoAdjacency until the exact layouts are verified, so the
// correction path works (with QWERTY-agnostic costs) across all keymaps.
class ColemakAdjacency : KeyAdjacency by NoAdjacency
class DvorakAdjacency : KeyAdjacency by NoAdjacency