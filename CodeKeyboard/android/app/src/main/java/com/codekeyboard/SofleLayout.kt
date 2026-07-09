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

    private fun row(vararg keys: KeyDef, align: Alignment = Alignment.SPLIT, stagger: List<Int> = emptyList()) =
        Row(keys.toList(), align, stagger)

    private val BASE = GridLayoutData(
        rows = listOf(
            // Row 0
            row(
                KeyDef("Esc", "escape", stagger = STAGGER_LEFT[0]),
                KeyDef("1", shift = "!", stagger = STAGGER_LEFT[1]),
                KeyDef("2", shift = "@", stagger = STAGGER_LEFT[2]),
                KeyDef("3", shift = "#", stagger = STAGGER_LEFT[3]),
                KeyDef("4", shift = "$", stagger = STAGGER_LEFT[4]),
                KeyDef("5", shift = "%", stagger = STAGGER_LEFT[5]),
                KeyDef("6", shift = "^", stagger = STAGGER_RIGHT[0]),
                KeyDef("7", shift = "&", stagger = STAGGER_RIGHT[1]),
                KeyDef("8", shift = "*", stagger = STAGGER_RIGHT[2]),
                KeyDef("9", shift = "(", stagger = STAGGER_RIGHT[3]),
                KeyDef("0", shift = ")", stagger = STAGGER_RIGHT[4]),
                KeyDef("-", shift = "_", stagger = STAGGER_RIGHT[5]),
                KeyDef("=", shift = "+", stagger = STAGGER_RIGHT[6])
            ),
            // Row 1
            row(
                KeyDef("Tab", "tab", stagger = STAGGER_LEFT[0]),
                KeyDef("q", stagger = STAGGER_LEFT[1]),
                KeyDef("w", stagger = STAGGER_LEFT[2]),
                KeyDef("e", stagger = STAGGER_LEFT[3]),
                KeyDef("r", stagger = STAGGER_LEFT[4]),
                KeyDef("t", stagger = STAGGER_LEFT[5]),
                KeyDef("y", stagger = STAGGER_RIGHT[0]),
                KeyDef("u", stagger = STAGGER_RIGHT[1]),
                KeyDef("i", stagger = STAGGER_RIGHT[2]),
                KeyDef("o", stagger = STAGGER_RIGHT[3]),
                KeyDef("p", stagger = STAGGER_RIGHT[4]),
                KeyDef("[", shift = "{", stagger = STAGGER_RIGHT[5]),
                KeyDef("]", shift = "}", stagger = STAGGER_RIGHT[6])
            ),
            // Row 2
            row(
                KeyDef("Caps", "caps", stagger = STAGGER_LEFT[0]),
                KeyDef("a", stagger = STAGGER_LEFT[1]),
                KeyDef("s", stagger = STAGGER_LEFT[2]),
                KeyDef("d", stagger = STAGGER_LEFT[3]),
                KeyDef("f", stagger = STAGGER_LEFT[4]),
                KeyDef("g", stagger = STAGGER_LEFT[5]),
                KeyDef("h", stagger = STAGGER_RIGHT[0]),
                KeyDef("j", stagger = STAGGER_RIGHT[1]),
                KeyDef("k", stagger = STAGGER_RIGHT[2]),
                KeyDef("l", stagger = STAGGER_RIGHT[3]),
                KeyDef(";", shift = ":", stagger = STAGGER_RIGHT[4]),
                KeyDef("'", shift = "\"", stagger = STAGGER_RIGHT[5]),
                KeyDef("Enter", "enter", stagger = STAGGER_RIGHT[6])
            ),
            // Row 3
            row(
                KeyDef("Shift", "shift", stagger = STAGGER_LEFT[0]),
                KeyDef("z", stagger = STAGGER_LEFT[1]),
                KeyDef("x", stagger = STAGGER_LEFT[2]),
                KeyDef("c", stagger = STAGGER_LEFT[3]),
                KeyDef("v", stagger = STAGGER_LEFT[4]),
                KeyDef("b", stagger = STAGGER_LEFT[5]),
                KeyDef("n", stagger = STAGGER_RIGHT[0]),
                KeyDef("m", stagger = STAGGER_RIGHT[1]),
                KeyDef(",", shift = "<", stagger = STAGGER_RIGHT[2]),
                KeyDef(".", shift = ">", stagger = STAGGER_RIGHT[3]),
                KeyDef("/", shift = "?", stagger = STAGGER_RIGHT[4]),
                KeyDef("Shift", "shift", stagger = STAGGER_RIGHT[5]),
                KeyDef("Bksp", "backspace", stagger = STAGGER_RIGHT[6])
            ),
            // Row 4 – thumb cluster (split alignment)
            row(
                KeyDef("Ctrl", "ctrl", stagger = STAGGER_LEFT[0]),
                KeyDef("Alt", "alt", stagger = STAGGER_LEFT[1]),
                KeyDef("", stagger = STAGGER_LEFT[2]),
                KeyDef("Cmd", "meta", stagger = STAGGER_LEFT[3]),
                KeyDef("Spc", "space", stagger = STAGGER_LEFT[4]),
                KeyDef("Spc", "space", stagger = STAGGER_LEFT[5]),
                KeyDef("Spc", "space", stagger = STAGGER_RIGHT[0]),
                KeyDef("Spc", "space", stagger = STAGGER_RIGHT[1]),
                KeyDef("Fn", "func", stagger = STAGGER_RIGHT[2]),
                KeyDef("Alt", "alt", stagger = STAGGER_RIGHT[3]),
                KeyDef("Ctrl", "ctrl", stagger = STAGGER_RIGHT[4]),
                KeyDef("←", "arrow-left", stagger = STAGGER_RIGHT[5]),
                KeyDef("→", "arrow-right", stagger = STAGGER_RIGHT[6])
            )
        )
    )

    private val LOWER = GridLayoutData(
        rows = listOf(
            row(KeyDef("Esc"), KeyDef("1"), KeyDef("2"), KeyDef("3"), KeyDef("4"), KeyDef("5"), KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            row(KeyDef("Tab"), KeyDef("!"), KeyDef("@"), KeyDef("#"), KeyDef("$"), KeyDef("%"), KeyDef("^"), KeyDef("&"), KeyDef("*"), KeyDef("("), KeyDef(")"), KeyDef("["), KeyDef("]")),
            row(KeyDef("Shift"), KeyDef("~"), KeyDef("`"), KeyDef("|"), KeyDef("\\"), KeyDef("{"), KeyDef("}"), KeyDef("<"), KeyDef(">"), KeyDef(":"), KeyDef(";"), KeyDef("\""), KeyDef("'")),
            row(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("", stagger = STAGGER_LEFT[2]), KeyDef("Cmd"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Fn"), KeyDef("Alt"), KeyDef("Ctrl"), KeyDef("←"), KeyDef("→"))
        )
    )

    private val RAISE = GridLayoutData(
        rows = listOf(
            row(KeyDef("Esc"), KeyDef("F1"), KeyDef("F2"), KeyDef("F3"), KeyDef("F4"), KeyDef("F5"), KeyDef("F6"), KeyDef("F7"), KeyDef("F8"), KeyDef("F9"), KeyDef("F10"), KeyDef("F11"), KeyDef("F12")),
            row(KeyDef("Tab"), KeyDef("Home"), KeyDef("↑"), KeyDef("End"), KeyDef("PgUp"), KeyDef("Ins"), KeyDef("Del"), KeyDef("←"), KeyDef("↓"), KeyDef("→"), KeyDef("PgDn"), KeyDef("["), KeyDef("]")),
            row(KeyDef("Shift"), KeyDef("Vol-"), KeyDef("Vol+"), KeyDef("Mute"), KeyDef("Play"), KeyDef("Prev"), KeyDef("Next"), KeyDef("RGB"), KeyDef("Bri-"), KeyDef("Bri+"), KeyDef("Mode"), KeyDef(":"), KeyDef("\"")),
            row(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("", stagger = STAGGER_LEFT[2]), KeyDef("Cmd"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Fn"), KeyDef("Alt"), KeyDef("Ctrl"), KeyDef("←"), KeyDef("→"))
        )
    )

    private val ADJUST = GridLayoutData(
        rows = listOf(
            row(KeyDef("Esc"), KeyDef("BT1"), KeyDef("BT2"), KeyDef("BT3"), KeyDef("BT4"), KeyDef("BT5"), KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            row(KeyDef("Tab"), KeyDef("RGB"), KeyDef("Bri-"), KeyDef("Bri+"), KeyDef("Mode"), KeyDef("Next"), KeyDef("Home"), KeyDef("↑"), KeyDef("End"), KeyDef("PgUp"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            row(KeyDef("Shift"), KeyDef("Vol-"), KeyDef("Vol+"), KeyDef("Mute"), KeyDef("Play"), KeyDef("Prev"), KeyDef("←"), KeyDef("↓"), KeyDef("→"), KeyDef("PgDn"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            row(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("", stagger = STAGGER_LEFT[2]), KeyDef("Cmd"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Fn"), KeyDef("Alt"), KeyDef("Ctrl"), KeyDef("←"), KeyDef("→"))
        )
    )

    private val FUNC = GridLayoutData(
        rows = listOf(
            row(KeyDef("Esc"), KeyDef("1"), KeyDef("2"), KeyDef("3"), KeyDef("4"), KeyDef("5"), KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            row(KeyDef("Tab"), KeyDef("F1"), KeyDef("F2"), KeyDef("F3"), KeyDef("F4"), KeyDef("F5"), KeyDef("F6"), KeyDef("F7"), KeyDef("F8"), KeyDef("F9"), KeyDef("F10"), KeyDef("F11"), KeyDef("F12")),
            row(KeyDef("Shift"), KeyDef("F13"), KeyDef("F14"), KeyDef("F15"), KeyDef("F16"), KeyDef("F17"), KeyDef("F18"), KeyDef("F19"), KeyDef("F20"), KeyDef("F21"), KeyDef("F22"), KeyDef(":"), KeyDef("\"")),
            row(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("", stagger = STAGGER_LEFT[2]), KeyDef("Cmd"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("Fn"), KeyDef("Alt"), KeyDef("Ctrl"), KeyDef("←"), KeyDef("→"))
        )
    )

    override val layers: Map<String, GridLayoutData> = mapOf(
        "base" to BASE,
        "lower" to LOWER,
        "raise" to RAISE,
        "adj" to ADJUST,
        "func" to FUNC
    )
}
