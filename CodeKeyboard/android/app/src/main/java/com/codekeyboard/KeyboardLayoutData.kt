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
    // Stagger in dp (matching HTML: 0, 0.25, 0.5, 0.75, 1.0, 1.0 × rowHeight)
    private val STAGGER_LEFT = listOf(0, 14, 28, 42, 56, 56)
    private val STAGGER_RIGHT = listOf(56, 42, 28, 14, 0, 0, 0)

    val BASE = SplitLayoutData(
        left = listOf(
            // Row 0
            listOf(
                KeyDef("Esc", "escape", stagger = STAGGER_LEFT[0]),
                KeyDef("1", shift = "!", stagger = STAGGER_LEFT[1]),
                KeyDef("2", shift = "@", stagger = STAGGER_LEFT[2]),
                KeyDef("3", shift = "#", stagger = STAGGER_LEFT[3]),
                KeyDef("4", shift = "$", stagger = STAGGER_LEFT[4]),
                KeyDef("5", shift = "%", stagger = STAGGER_LEFT[5])
            ),
            // Row 1
            listOf(
                KeyDef("Tab", "tab", stagger = STAGGER_LEFT[0]),
                KeyDef("q", stagger = STAGGER_LEFT[1]),
                KeyDef("w", stagger = STAGGER_LEFT[2]),
                KeyDef("e", stagger = STAGGER_LEFT[3]),
                KeyDef("r", stagger = STAGGER_LEFT[4]),
                KeyDef("t", stagger = STAGGER_LEFT[5])
            ),
            // Row 2
            listOf(
                KeyDef("Caps", "caps", stagger = STAGGER_LEFT[0]),
                KeyDef("a", stagger = STAGGER_LEFT[1]),
                KeyDef("s", stagger = STAGGER_LEFT[2]),
                KeyDef("d", stagger = STAGGER_LEFT[3]),
                KeyDef("f", stagger = STAGGER_LEFT[4]),
                KeyDef("g", stagger = STAGGER_LEFT[5])
            ),
            // Row 3
            listOf(
                KeyDef("Shift", "shift", stagger = STAGGER_LEFT[0]),
                KeyDef("z", stagger = STAGGER_LEFT[1]),
                KeyDef("x", stagger = STAGGER_LEFT[2]),
                KeyDef("c", stagger = STAGGER_LEFT[3]),
                KeyDef("v", stagger = STAGGER_LEFT[4]),
                KeyDef("b", stagger = STAGGER_LEFT[5])
            ),
            // Row 4 – thumb cluster
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
            // Row 0
            listOf(
                KeyDef("6", shift = "^", stagger = STAGGER_RIGHT[0]),
                KeyDef("7", shift = "&", stagger = STAGGER_RIGHT[1]),
                KeyDef("8", shift = "*", stagger = STAGGER_RIGHT[2]),
                KeyDef("9", shift = "(", stagger = STAGGER_RIGHT[3]),
                KeyDef("0", shift = ")", stagger = STAGGER_RIGHT[4]),
                KeyDef("-", shift = "_", stagger = STAGGER_RIGHT[5]),
                KeyDef("=", shift = "+", stagger = STAGGER_RIGHT[6])
            ),
            // Row 1
            listOf(
                KeyDef("y", stagger = STAGGER_RIGHT[0]),
                KeyDef("u", stagger = STAGGER_RIGHT[1]),
                KeyDef("i", stagger = STAGGER_RIGHT[2]),
                KeyDef("o", stagger = STAGGER_RIGHT[3]),
                KeyDef("p", stagger = STAGGER_RIGHT[4]),
                KeyDef("[", shift = "{", stagger = STAGGER_RIGHT[5]),
                KeyDef("]", shift = "}", stagger = STAGGER_RIGHT[6])
            ),
            // Row 2
            listOf(
                KeyDef("h", stagger = STAGGER_RIGHT[0]),
                KeyDef("j", stagger = STAGGER_RIGHT[1]),
                KeyDef("k", stagger = STAGGER_RIGHT[2]),
                KeyDef("l", stagger = STAGGER_RIGHT[3]),
                KeyDef(";", shift = ":", stagger = STAGGER_RIGHT[4]),
                KeyDef("'", shift = "\"", stagger = STAGGER_RIGHT[5]),
                KeyDef("Enter", "enter", stagger = STAGGER_RIGHT[6])
            ),
            // Row 3
            listOf(
                KeyDef("n", stagger = STAGGER_RIGHT[0]),
                KeyDef("m", stagger = STAGGER_RIGHT[1]),
                KeyDef(",", shift = "<", stagger = STAGGER_RIGHT[2]),
                KeyDef(".", shift = ">", stagger = STAGGER_RIGHT[3]),
                KeyDef("/", shift = "?", stagger = STAGGER_RIGHT[4]),
                KeyDef("Shift", "shift", stagger = STAGGER_RIGHT[5]),
                KeyDef("Bksp", "backspace", stagger = STAGGER_RIGHT[6])
            ),
            // Row 4 – thumb + arrows
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

    val LOWER = SplitLayoutData(
        left = listOf(
            listOf(KeyDef("Esc", "escape"), KeyDef("1"), KeyDef("2"), KeyDef("3"), KeyDef("4"), KeyDef("5")),
            listOf(KeyDef("Tab"), KeyDef("!"), KeyDef("@"), KeyDef("#"), KeyDef("$"), KeyDef("%")),
            listOf(KeyDef("Shift"), KeyDef("~"), KeyDef("`"), KeyDef("^"), KeyDef("&"), KeyDef("*")),
            listOf(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("", stagger = STAGGER_LEFT[2]), KeyDef("Cmd"), KeyDef("Spc"), KeyDef("Spc"))
        ),
        right = listOf(
            listOf(KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            listOf(KeyDef("("), KeyDef(")"), KeyDef("_"), KeyDef("+"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            listOf(KeyDef("{"), KeyDef("}"), KeyDef("|"), KeyDef("\\"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            listOf(KeyDef("Spc"), KeyDef("Spc"), KeyDef("Fn"), KeyDef("Alt"), KeyDef("Ctrl"), KeyDef("←"), KeyDef("→"))
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )

    val RAISE = SplitLayoutData(
        left = listOf(
            listOf(KeyDef("Esc"), KeyDef("F1"), KeyDef("F2"), KeyDef("F3"), KeyDef("F4"), KeyDef("F5")),
            listOf(KeyDef("Tab"), KeyDef("F6"), KeyDef("F7"), KeyDef("F8"), KeyDef("F9"), KeyDef("F10")),
            listOf(KeyDef("Shift"), KeyDef("F11"), KeyDef("F12"), KeyDef("Ins"), KeyDef("Del"), KeyDef("PgUp")),
            listOf(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("", stagger = STAGGER_LEFT[2]), KeyDef("Cmd"), KeyDef("Spc"), KeyDef("Spc"))
        ),
        right = listOf(
            listOf(KeyDef("F6"), KeyDef("F7"), KeyDef("F8"), KeyDef("F9"), KeyDef("F10"), KeyDef("-"), KeyDef("=")),
            listOf(KeyDef("Home"), KeyDef("↑"), KeyDef("End"), KeyDef("PgDn"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            listOf(KeyDef("←"), KeyDef("↓"), KeyDef("→"), KeyDef("PgUp"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            listOf(KeyDef("Spc"), KeyDef("Spc"), KeyDef("Fn"), KeyDef("Alt"), KeyDef("Ctrl"), KeyDef("←"), KeyDef("→"))
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )

    val ADJUST = SplitLayoutData(
        left = listOf(
            listOf(KeyDef("Esc"), KeyDef("Vol-"), KeyDef("Vol+"), KeyDef("Mute"), KeyDef("Play"), KeyDef("Prev")),
            listOf(KeyDef("Tab"), KeyDef("RGB"), KeyDef("Bri-"), KeyDef("Bri+"), KeyDef("Mode"), KeyDef("Next")),
            listOf(KeyDef("Shift"), KeyDef("BT1"), KeyDef("BT2"), KeyDef("BT3"), KeyDef("BT4"), KeyDef("BT5")),
            listOf(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("", stagger = STAGGER_LEFT[2]), KeyDef("Cmd"), KeyDef("Spc"), KeyDef("Spc"))
        ),
        right = listOf(
            listOf(KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            listOf(KeyDef("Home"), KeyDef("↑"), KeyDef("End"), KeyDef("PgDn"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            listOf(KeyDef("←"), KeyDef("↓"), KeyDef("→"), KeyDef("PgUp"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            listOf(KeyDef("Spc"), KeyDef("Spc"), KeyDef("Fn"), KeyDef("Alt"), KeyDef("Ctrl"), KeyDef("←"), KeyDef("→"))
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )

    val FUNC = SplitLayoutData(
        left = listOf(
            listOf(KeyDef("Esc"), KeyDef("1"), KeyDef("2"), KeyDef("3"), KeyDef("4"), KeyDef("5")),
            listOf(KeyDef("Tab"), KeyDef("F1"), KeyDef("F2"), KeyDef("F3"), KeyDef("F4"), KeyDef("F5")),
            listOf(KeyDef("Shift"), KeyDef("F6"), KeyDef("F7"), KeyDef("F8"), KeyDef("F9"), KeyDef("F10")),
            listOf(KeyDef("Ctrl"), KeyDef("Alt"), KeyDef("", stagger = STAGGER_LEFT[2]), KeyDef("Cmd"), KeyDef("Spc"), KeyDef("Spc"))
        ),
        right = listOf(
            listOf(KeyDef("6"), KeyDef("7"), KeyDef("8"), KeyDef("9"), KeyDef("0"), KeyDef("-"), KeyDef("=")),
            listOf(KeyDef("F11"), KeyDef("F12"), KeyDef("↑"), KeyDef("PgUp"), KeyDef("["), KeyDef("]"), KeyDef("Enter")),
            listOf(KeyDef("←"), KeyDef("↓"), KeyDef("→"), KeyDef("PgDn"), KeyDef(":"), KeyDef("\""), KeyDef("Bksp")),
            listOf(KeyDef("Spc"), KeyDef("Spc"), KeyDef("Fn"), KeyDef("Alt"), KeyDef("Ctrl"), KeyDef("←"), KeyDef("→"))
        ),
        staggerLeft = STAGGER_LEFT,
        staggerRight = STAGGER_RIGHT
    )
}
