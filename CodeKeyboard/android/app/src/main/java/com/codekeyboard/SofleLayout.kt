package com.codekeyboard

/**
 * Sofle Choc v2 layout (split).
 * Matches the reference HTML exactly.
 */
object SofleLayout : KeyboardLayout {
    override val name = "Sofle Choc v2"
    override val isSplit = true

    private val STAGGER_LEFT = listOf(0, 14, 28, 42, 56, 56)
    private val STAGGER_RIGHT = listOf(56, 42, 28, 14, 0, 0, 0)

    private val BASE = SplitLayoutData(
        left = listOf(
            listOf(
                KeyDef("Esc", "escape", stagger = STAGGER_LEFT[0]),
                KeyDef("1", shift = "!", stagger = STAGGER_LEFT[1]),
                KeyDef("2", shift = "@", stagger = STAGGER_LEFT[2]),
                KeyDef("3", shift = "#", stagger = STAGGER_LEFT[3]),
                KeyDef("4", shift = "$", stagger = STAGGER_LEFT[4]),
                KeyDef("5", shift = "%", stagger = STAGGER_LEFT[5])
            ),
            listOf(
                KeyDef("Tab", "tab", stagger = STAGGER_LEFT[0]),
                KeyDef("q", stagger = STAGGER_LEFT[1]),
                KeyDef("w", stagger = STAGGER_LEFT[2]),
                KeyDef("e", stagger = STAGGER_LEFT[3]),
                KeyDef("r", stagger = STAGGER_LEFT[4]),
                KeyDef("t", stagger = STAGGER_LEFT[5])
            ),
            listOf(
                KeyDef("Caps", "caps", stagger = STAGGER_LEFT[0]),
                KeyDef("a", stagger = STAGGER_LEFT[1]),
                KeyDef("s", stagger = STAGGER_LEFT[2]),
                KeyDef("d", stagger = STAGGER_LEFT[3]),
                KeyDef("f", stagger = STAGGER_LEFT[4]),
                KeyDef("g", stagger = STAGGER_LEFT[5])
            ),
            listOf(
                KeyDef("Shift", "shift", stagger = STAGGER_LEFT[0]),
                KeyDef("z", stagger = STAGGER_LEFT[1]),
                KeyDef("x", stagger = STAGGER_LEFT[2]),
                KeyDef("c", stagger = STAGGER_LEFT[3]),
                KeyDef("v", stagger = STAGGER_LEFT[4]),
                KeyDef("b", stagger = STAGGER_LEFT[5])
            ),
            listOf(
                KeyDef("Ctrl", "ctrl", stagger = STAGGER_LEFT[0]),
                KeyDef("Alt", "alt", stagger = STAGGER_LEFT[1]),
                KeyDef("", stagger = STAGGER_LEFT[2]),
                KeyDef("Cmd", "meta", stagger = STAGGER_LEFT[3]),
                KeyDef("Spc", "space", stagger = STAGGER_LEFT[4]),
                KeyDef("Spc", "space", stagger = STAGGER_LEFT[5])
            )
        ),
        right = listOf(
            listOf(
                KeyDef("6", shift = "^", stagger = STAGGER_RIGHT[0]),
                KeyDef("7", shift = "&", stagger = STAGGER_RIGHT[1]),
                KeyDef("8", shift = "*", stagger = STAGGER_RIGHT[2]),
                KeyDef("9", shift = "(", stagger = STAGGER_RIGHT[3]),
                KeyDef("0", shift = ")", stagger = STAGGER_RIGHT[4]),
                KeyDef("-", shift = "_", stagger = STAGGER_RIGHT[5]),
                KeyDef("=", shift = "+", stagger = STAGGER_RIGHT[6])
            ),
            listOf(
                KeyDef("y", stagger = STAGGER_RIGHT[0]),
                KeyDef("u", stagger = STAGGER_RIGHT[1]),
                KeyDef("i", stagger = STAGGER_RIGHT[2]),
                KeyDef("o", stagger = STAGGER_RIGHT[3]),
                KeyDef("p", stagger = STAGGER_RIGHT[4]),
                KeyDef("[", shift = "{", stagger = STAGGER_RIGHT[5]),
                KeyDef("]", shift = "}", stagger = STAGGER_RIGHT[6])
            ),
            listOf(
                KeyDef("h", stagger = STAGGER_RIGHT[0]),
                KeyDef("j", stagger = STAGGER_RIGHT[1]),
                KeyDef("k", stagger = STAGGER_RIGHT[2]),
                KeyDef("l", stagger = STAGGER_RIGHT[3]),
                KeyDef(";", shift = ":", stagger = STAGGER_RIGHT[4]),
                KeyDef("'", shift = "\"", stagger = STAGGER_RIGHT[5]),
                KeyDef("Enter", "enter", stagger = STAGGER_RIGHT[6])
            ),
            listOf(
                KeyDef("n", stagger = STAGGER_RIGHT[0]),
                KeyDef("m", stagger = STAGGER_RIGHT[1]),
                KeyDef(",", shift = "<", stagger = STAGGER_RIGHT[2]),
                KeyDef(".", shift = ">", stagger = STAGGER_RIGHT[3]),
                KeyDef("/", shift = "?", stagger = STAGGER_RIGHT[4]),
                KeyDef("Shift", "shift", stagger = STAGGER_RIGHT[5]),
                KeyDef("Bksp", "backspace", stagger = STAGGER_RIGHT[6])
            ),
            listOf(
                KeyDef("Spc", "space", stagger = STAGGER_RIGHT[0]),
                KeyDef("Spc", "space", stagger = STAGGER_RIGHT[1]),
                KeyDef("Fn", "func", stagger = STAGGER_RIGHT[2]),
                KeyDef("Alt", "alt", stagger = STAGGER_RIGHT[3]),
                KeyDef("Ctrl", "ctrl", stagger = STAGGER_RIGHT[4]),
                KeyDef("←", "arrow-left", stagger = STAGGER_RIGHT[5]),
                KeyDef("→", "arrow-right", stagger = STAGGER_RIGHT[6])
            )
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )

    // TODO: define LOWER, RAISE, ADJUST, FUNC the same way
    private val LOWER = BASE
    private val RAISE = BASE
    private val ADJUST = BASE
    private val FUNC = BASE

    override val layers: Map<String, LayoutData> = mapOf(
        "base" to LayoutData.Split(BASE),
        "lower" to LayoutData.Split(LOWER),
        "raise" to LayoutData.Split(RAISE),
        "adj" to LayoutData.Split(ADJUST),
        "func" to LayoutData.Split(FUNC)
    )
}
