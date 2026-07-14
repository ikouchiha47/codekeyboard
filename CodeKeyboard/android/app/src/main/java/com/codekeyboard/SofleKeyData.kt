package com.codekeyboard

/**
 * Key definitions for every layer — V5 structure.
 *
 * Layout per layer:
 *   topRow  8 keys full-width  — Tab + Esc (fixed) + 6 layer slots
 *   left    4 rows × 5 cols   — stagger [0, .25, .5, .75, 1.0]
 *   right   4 rows × 5 cols   — stagger [1.0, .75, .5, .25, 0]
 *
 * Hold-tap annotations (home row mods, thumb layer-holds) are defined
 * on the BASE layer: a→ctrl, s→meta, d→alt, f→shift, h→shift, j→alt,
 * k→meta, l→ctrl (home row). Left Spc→lower, right Spc→raise (thumb).
 * Tapping-term = 150ms, managed by HoldTapTracker in NativeKeyboardView.
 *
 * No geometry, no pixel values, no rendering concerns.
 */
object SofleKeyData {

    private fun k(label: String, action: String? = null, shift: String? = null, hold: String? = null) =
        KeyDef(label, action, shift, holdAction = hold)

    private fun empty() = KeyDef("")

    // ── Shared top-row anchors (always the same) ──────────────────────────────
    private val ANCHOR_TAB = k("Tab", "tab")
    private val ANCHOR_ESC = k("Esc", "escape")

    // ── BASE ──────────────────────────────────────────────────────────────────
    private val BASE = SofleLayerData(
        topRow = listOf(
            ANCHOR_TAB, ANCHOR_ESC,
            k("`"), k("^"), k("Ctrl","ctrl"), k("Alt","alt"), k("Cmd","meta"), k("Bksp","backspace")
        ),
        left = listOf(
            listOf(k("q"),     k("w"),     k("e"),     k("r"),     k("t")),
            listOf(k("a", hold="ctrl"),  k("s", hold="meta"),
                   k("d", hold="alt"),   k("f", hold="shift"), k("g")),
            listOf(k("z"),     k("x"),     k("c"),     k("v"),     k("b")),
            listOf(k("Shift","shift"), k("Spc","space", hold="lower"), k("LWR","lower"),
                   k("Ctrl","ctrl"),  k("Alt","alt"))
        ),
        right = listOf(
            listOf(k("y"),     k("u"),     k("i"),     k("o"),     k("p")),
            listOf(k("h", hold="shift"),  k("j", hold="alt"),
                   k("k", hold="meta"),   k("l", hold="ctrl"), k(";", shift=":")),
            listOf(k("n"),     k("m"),     k(",", shift="<"), k(".", shift=">"), k("Bksp","backspace")),
            listOf(k("RSE","raise"), k("Enter","enter"), k("Spc","space", hold="raise"),
                   k("FUNC","func"),  k("ADJ","adj"))
        )
    )

    // ── LOWER ─────────────────────────────────────────────────────────────────
    private val LOWER = SofleLayerData(
        topRow = listOf(
            ANCHOR_TAB, ANCHOR_ESC,
            k("("), k(")"), k("["), k("]"), k("{"), k("}")
        ),
        left = listOf(
            listOf(k("1", shift="!"), k("2", shift="@"), k("3", shift="#"),
                   k("4", shift="$"), k("5", shift="%")),
            listOf(k("`"), k("-", shift="_"), k("=", shift="+"),
                   k("[", shift="{"), k("]", shift="}")),
            listOf(k("~"), k("\\", shift="|"), k("("), k(")"), k("'", shift="\"")),
            listOf(k("Shift","shift"), k("Spc","space"), k("LWR","lower"),
                   k("Esc","escape"),  k("Tab","tab"))
        ),
        right = listOf(
            listOf(k("6", shift="^"), k("7", shift="&"), k("8", shift="*"),
                   k("9", shift="("), k("0", shift=")")),
            listOf(k("/", shift="?"), k(";", shift=":"), k("'", shift="\""),
                   k("<"), k(">")),
            listOf(k("!"), k("@"), k("#"), k("$"), k("Del","delete")),
            listOf(k("RSE","raise"), k("Enter","enter"), k("Spc","space"),
                   k("FUNC","func"),  k("ADJ","adj"))
        )
    )

    // ── RAISE ─────────────────────────────────────────────────────────────────
    private val RAISE = SofleLayerData(
        topRow = listOf(
            ANCHOR_TAB, ANCHOR_ESC,
            k("F1","f1"), k("F2","f2"), k("F3","f3"),
            k("F4","f4"), k("F5","f5"), k("F6","f6")
        ),
        left = listOf(
            listOf(k("F7","f7"),  k("F8","f8"),   k("F9","f9"),
                   k("F10","f10"), k("F11","f11")),
            listOf(k("F12","f12"), k("Ins","insert"), k("Home","home"),
                   k("PgUp","page-up"), k("PgDn","page-down")),
            listOf(k("End","end"), k("Cut","cut"), k("Copy","copy"),
                   k("Paste","paste"), k("Undo","undo")),
            listOf(k("Shift","shift"), k("Spc","space"), k("LWR","lower"),
                   k("Ctrl","ctrl"),  k("Alt","alt"))
        ),
        right = listOf(
            listOf(k("←","arrow-left"), k("↓","arrow-down"),
                   k("↑","arrow-up"),   k("→","arrow-right"), k("PgDn","page-down")),
            listOf(k("Home","home"), k("End","end"),
                   k("PgUp","page-up"), k("PgDn","page-down"), empty()),
            listOf(k("Cut","cut"), k("Copy","copy"), k("Paste","paste"),
                   k("Undo","undo"), k("Bksp","backspace")),
            listOf(k("RSE","raise"), k("Enter","enter"), k("Spc","space"),
                   k("FUNC","func"),  k("ADJ","adj"))
        )
    )

    // ── ADJUST ────────────────────────────────────────────────────────────────
    private val ADJUST = SofleLayerData(
        topRow = listOf(
            ANCHOR_TAB, ANCHOR_ESC,
            k("Br-", "brightness-down"), k("Br+", "brightness-up"),
            k("Mute", "volume-mute"), k("Vol-", "volume-down"), k("Vol+", "volume-up"),
            k("Play", "media-play")
        ),
        left = listOf(
            listOf(k("Prev", "media-previous"), k("Play", "media-play"),
                   k("Next", "media-next"), empty(), empty()),
            listOf(empty(),    empty(),    empty(),    empty(),   empty()),
            listOf(empty(),    empty(),    empty(),    empty(),   empty()),
            listOf(k("Shift","shift"), k("Spc","space"), k("LWR","lower"),
                   empty(),            empty())
        ),
        right = listOf(
            listOf(empty(),    empty(),    empty(),    empty(),   empty()),
            listOf(k("BT"),    k("WiFi"),  empty(),    empty(),   empty()),
            listOf(empty(),    empty(),    empty(),    empty(),   k("Bksp","backspace")),
            listOf(k("RSE","raise"), k("Enter","enter"), k("Spc","space"),
                   k("FUNC","func"),  k("ADJ","adj"))
        )
    )

    // ── FUNC ──────────────────────────────────────────────────────────────────
    private val FUNC = SofleLayerData(
        topRow = listOf(
            ANCHOR_TAB, ANCHOR_ESC,
            k("Undo","undo"), k("Redo","redo"), k("Cut","cut"),
            k("Copy","copy"), k("Paste","paste"), k("SelAll","select-all")
        ),
        left = listOf(
            listOf(k("Save","save"), k("Find","find"), k("Repl","replace"),
                   k("Cmnt","comment"), k("Dup","duplicate")),
            listOf(k("Fmt","format"), empty(), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), empty(), empty()),
            listOf(k("Shift","shift"), k("Spc","space"), k("LWR","lower"),
                   empty(), empty())
        ),
        right = listOf(
            listOf(empty(), empty(), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), empty(), k("Bksp","backspace")),
            listOf(k("RSE","raise"), k("Enter","enter"), k("Spc","space"),
                   k("FUNC","func"),  k("ADJ","adj"))
        )
    )

    // ── Registry ──────────────────────────────────────────────────────────────
    val LAYERS: Map<String, SofleLayerData> = mapOf(
        "base"  to BASE,
        "lower" to LOWER,
        "raise" to RAISE,
        "adj"   to ADJUST,
        "func"  to FUNC
    )
}
