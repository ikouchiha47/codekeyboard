package com.codekeyboard

data class KeyDef(
    val label: String,
    val action: String? = null,
    val shift: String? = null,
    val width: Float = 1f,
    val stagger: Int = 0
)

typealias KeyRow = List<KeyDef>
typealias SplitHalf = List<KeyRow>

data class SplitLayoutData(
    val left: SplitHalf,
    val right: SplitHalf,
    val staggerLeft: List<Int>,
    val staggerRight: List<Int>
)

object SofleLayout {
    private val STAGGER_LEFT = listOf(0, 12, 24, 36, 48, 48)
    private val STAGGER_RIGHT = listOf(48, 36, 24, 12, 0, 0, 0)

    val BASE = SplitLayoutData(
        left = listOf(
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
                KeyDef("Spc", "space", stagger = STAGGER_LEFT[2]),
                KeyDef("LWR", "lower", stagger = STAGGER_LEFT[3]),
                KeyDef("Cmd", stagger = STAGGER_LEFT[4])
            )
        ),
        right = listOf(
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
                KeyDef("RSE", "raise", stagger = STAGGER_RIGHT[0]),
                KeyDef("Spc", "space", stagger = STAGGER_RIGHT[1]),
                KeyDef("Spc", "space", stagger = STAGGER_RIGHT[2]),
                KeyDef("ADJ", "adj", stagger = STAGGER_RIGHT[3]),
                KeyDef("Fn", "func", stagger = STAGGER_RIGHT[4]),
                KeyDef("←", "arrow-left", stagger = STAGGER_RIGHT[5]),
                KeyDef("→", "arrow-right", stagger = STAGGER_RIGHT[6])
            )
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )

    val LOWER = SplitLayoutData(
        left = listOf(
            listOf(KeyDef("Esc", "esc"), KeyDef("1"), KeyDef("2"), KeyDef("3"), KeyDef("4"), KeyDef("5")),
            listOf(KeyDef("Tab"), KeyDef("!"), KeyDef("@"), KeyDef("#"), KeyDef("$"), KeyDef("%")),
            listOf(KeyDef("Shift"), KeyDef("~"), KeyDef("`"), KeyDef("^"), KeyDef("&"), KeyDef("*")),
            listOf(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("Spc"), KeyDef("LWR"), KeyDef("Cmd"))
        ),
        right = listOf(
            listOf(KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            listOf(KeyDef("("), KeyDef(")"), KeyDef("_"), KeyDef("+"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            listOf(KeyDef("{"), KeyDef("}"), KeyDef("|"), KeyDef("\\"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            listOf(KeyDef("RSE"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("ADJ"), KeyDef("Fn"), KeyDef("←"), KeyDef("→"))
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )

    val RAISE = SplitLayoutData(
        left = listOf(
            listOf(KeyDef("Esc"), KeyDef("F1"), KeyDef("F2"), KeyDef("F3"), KeyDef("F4"), KeyDef("F5")),
            listOf(KeyDef("Tab"), KeyDef("F6"), KeyDef("F7"), KeyDef("F8"), KeyDef("F9"), KeyDef("F10")),
            listOf(KeyDef("Shift"), KeyDef("F11"), KeyDef("F12"), KeyDef("Ins"), KeyDef("Del"), KeyDef("PgUp")),
            listOf(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("Spc"), KeyDef("LWR"), KeyDef("Cmd"))
        ),
        right = listOf(
            listOf(KeyDef("F6"), KeyDef("F7"), KeyDef("F8"), KeyDef("F9"), KeyDef("F10"), KeyDef("-"), KeyDef("=")),
            listOf(KeyDef("Home"), KeyDef("↑"), KeyDef("End"), KeyDef("PgDn"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            listOf(KeyDef("←"), KeyDef("↓"), KeyDef("→"), KeyDef("PgUp"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            listOf(KeyDef("RSE"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("ADJ"), KeyDef("Fn"), KeyDef("←"), KeyDef("→"))
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )

    val ADJUST = SplitLayoutData(
        left = listOf(
            listOf(KeyDef("Esc"), KeyDef("Vol-"), KeyDef("Vol+"), KeyDef("Mute"), KeyDef("Play"), KeyDef("Prev")),
            listOf(KeyDef("Tab"), KeyDef("RGB"), KeyDef("Bri-"), KeyDef("Bri+"), KeyDef("Mode"), KeyDef("Next")),
            listOf(KeyDef("Shift"), KeyDef("BT1"), KeyDef("BT2"), KeyDef("BT3"), KeyDef("BT4"), KeyDef("BT5")),
            listOf(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("Spc"), KeyDef("LWR"), KeyDef("Cmd"))
        ),
        right = listOf(
            listOf(KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            listOf(KeyDef("Home"), KeyDef("↑"), KeyDef("End"), KeyDef("PgDn"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            listOf(KeyDef("←"), KeyDef("↓"), KeyDef("→"), KeyDef("PgUp"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            listOf(KeyDef("RSE"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("ADJ"), KeyDef("Fn"), KeyDef("←"), KeyDef("→"))
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )

    val FUNC = SplitLayoutData(
        left = listOf(
            listOf(KeyDef("Esc"), KeyDef("1"), KeyDef("2"), KeyDef("3"), KeyDef("4"), KeyDef("5")),
            listOf(KeyDef("Tab"), KeyDef("F1"), KeyDef("F2"), KeyDef("F3"), KeyDef("F4"), KeyDef("F5")),
            listOf(KeyDef("Shift"), KeyDef("F6"), KeyDef("F7"), KeyDef("F8"), KeyDef("F9"), KeyDef("F10")),
            listOf(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("Spc"), KeyDef("LWR"), KeyDef("Cmd"))
        ),
        right = listOf(
            listOf(KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            listOf(KeyDef("F11"), KeyDef("F12"), KeyDef("↑"), KeyDef("PgUp"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            listOf(KeyDef("←"), KeyDef("↓"), KeyDef("→"), KeyDef("PgDn"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            listOf(KeyDef("RSE"), KeyDef("Spc"), KeyDef("Spc"), KeyDef("ADJ"), KeyDef("Fn"), KeyDef("←"), KeyDef("→"))
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )
}
