package com.codekeyboard

/**
 * Pure key definitions for every Sofle Choc v2 layer.
 *
 * Mirrors keyboard-sofle-layers.html MAPS exactly.
 * No geometry, no pixel values, no rendering concerns.
 *
 * Each layer is:
 *   Pair<List<List<KeyDef>>, List<List<KeyDef>>>  →  (leftRows, rightRows)
 *
 * Left half:  4 rows × 6 columns
 * Right half: 4 rows × 7 columns
 */
object SofleKeyData {

    // Convenience builder
    private fun k(label: String, action: String? = null, shift: String? = null) =
        KeyDef(label, action, shift)

    private fun empty() = KeyDef("")

    // ── BASE ──────────────────────────────────────────────────────────────────

    val BASE_LEFT: List<List<KeyDef>> = listOf(
        // row 0
        listOf(k("Tab","tab"),   k("q"), k("w"), k("e"), k("r"), k("t")),
        // row 1
        listOf(k("Caps","caps"), k("a"), k("s"), k("d"), k("f"), k("g")),
        // row 2
        listOf(k("Shift","shift"), k("z"), k("x"), k("c"), k("v"), k("b")),
        // row 3  thumb cluster
        listOf(k("Ctrl","ctrl"), k("Alt","alt"), k("Spc","space"), k("LWR","lower"), k("Cmd","meta"), empty())
    )

    val BASE_RIGHT: List<List<KeyDef>> = listOf(
        listOf(k("y"), k("u"), k("i"), k("o"), k("p"), k("[",shift="{"), k("]",shift="}")),
        listOf(k("h"), k("j"), k("k"), k("l"), k(";",shift=":"), k("'",shift="\""), k("Enter","enter")),
        listOf(k("n"), k("m"), k(",",shift="<"), k(".",shift=">"), k("/",shift="?"), k("Shift","shift"), k("Bksp","backspace")),
        // row 3  thumb cluster
        listOf(k("RSE","raise"), k("Spc","space"), k("Spc","space"), k("ADJ","adj"), k("FUNC","func"), k("←","arrow-left"), k("→","arrow-right"))
    )

    // ── LOWER ─────────────────────────────────────────────────────────────────

    val LOWER_LEFT: List<List<KeyDef>> = listOf(
        listOf(k("Esc","escape"), k("1",shift="!"), k("2",shift="@"), k("3",shift="#"), k("4",shift="$"), k("5",shift="%")),
        listOf(k("`"),  k("-"), k("="), k("["), k("]"), k("\\")),
        listOf(k("~",shift="`"), k("_"), k("+"), k("{"), k("}"), k("|")),
        listOf(k("Ctrl","ctrl"), k("Alt","alt"), k("Spc","space"), k("LWR","lower"), empty(), empty())
    )

    val LOWER_RIGHT: List<List<KeyDef>> = listOf(
        listOf(k("6",shift="^"), k("7",shift="&"), k("8",shift="*"), k("9",shift="("), k("0",shift=")"), k("-"), k("=")),
        listOf(k("^"), k("&"), k("*"), k("("), k(")"), k("_"), k("+")),
        listOf(k("!"), k("@"), k("#"), k("$"), k("%"), k("Bksp","backspace"), k("Del","delete")),
        listOf(k("RSE","raise"), k("Spc","space"), empty(), k("ADJ","adj"), k("FUNC","func"), k("←","arrow-left"), k("→","arrow-right"))
    )

    // ── RAISE ─────────────────────────────────────────────────────────────────

    val RAISE_LEFT: List<List<KeyDef>> = listOf(
        listOf(k("F1"), k("F2"), k("F3"), k("F4"), k("F5"), k("F6")),
        listOf(k("F7"), k("F8"), k("F9"), k("F10"), k("F11"), k("F12")),
        listOf(k("PrtSc"), k("ScrLk"), k("Pause"), k("Ins"), k("Home"), k("PgUp")),
        listOf(k("Ctrl","ctrl"), k("Alt","alt"), k("Spc","space"), k("LWR","lower"), empty(), empty())
    )

    val RAISE_RIGHT: List<List<KeyDef>> = listOf(
        listOf(k("←","arrow-left"), k("↓","arrow-down"), k("↑","arrow-up"), k("→","arrow-right"), empty(), empty(), empty()),
        listOf(k("Home"), k("End"), k("PgUp"), k("PgDn"), empty(), empty(), empty()),
        listOf(k("Cut"), k("Copy"), k("Paste"), empty(), empty(), k("Del","delete"), k("Bksp","backspace")),
        listOf(k("RSE","raise"), k("Spc","space"), empty(), k("ADJ","adj"), k("FUNC","func"), k("←","arrow-left"), k("→","arrow-right"))
    )

    // ── ADJUST ────────────────────────────────────────────────────────────────

    val ADJ_LEFT: List<List<KeyDef>> = listOf(
        listOf(k("Esc","escape"), k("Br-"), k("Br+"), k("Mute"), k("Vol-"), k("Vol+")),
        listOf(k("Prev"), k("Play"), k("Next"), empty(), empty(), empty()),
        listOf(empty(), empty(), empty(), empty(), empty(), empty()),
        listOf(k("Ctrl","ctrl"), k("Alt","alt"), k("Spc","space"), k("LWR","lower"), empty(), empty())
    )

    val ADJ_RIGHT: List<List<KeyDef>> = listOf(
        listOf(empty(), empty(), empty(), empty(), empty(), empty(), empty()),
        listOf(k("BT"), k("WiFi"), empty(), empty(), empty(), empty(), empty()),
        listOf(empty(), empty(), empty(), empty(), empty(), k("Bksp","backspace"), k("Del","delete")),
        listOf(k("RSE","raise"), k("Spc","space"), empty(), k("ADJ","adj"), k("FUNC","func"), k("←","arrow-left"), k("→","arrow-right"))
    )

    // ── FUNC ──────────────────────────────────────────────────────────────────

    val FUNC_LEFT: List<List<KeyDef>> = listOf(
        listOf(k("Undo"), k("Redo"), k("Cut"), k("Copy"), k("Paste"), k("SelAll")),
        listOf(k("Save"), k("Find"), k("Repl"), k("Cmnt","comment"), k("Dup","duplicate"), k("Fmt")),
        listOf(k("Tab","tab"), k("Spc","space"), k("Ent","enter"), k("Esc","escape"), empty(), empty()),
        listOf(k("Ctrl","ctrl"), k("Alt","alt"), k("Spc","space"), k("LWR","lower"), empty(), empty())
    )

    val FUNC_RIGHT: List<List<KeyDef>> = listOf(
        listOf(empty(), empty(), empty(), empty(), empty(), empty(), empty()),
        listOf(empty(), empty(), empty(), empty(), empty(), empty(), empty()),
        listOf(empty(), empty(), empty(), empty(), empty(), k("Bksp","backspace"), k("Del","delete")),
        listOf(k("RSE","raise"), k("Spc","space"), empty(), k("ADJ","adj"), k("FUNC","func"), k("←","arrow-left"), k("→","arrow-right"))
    )

    // ── Registry ──────────────────────────────────────────────────────────────

    /** All layers keyed by name, (leftRows, rightRows) */
    val LAYERS: Map<String, Pair<List<List<KeyDef>>, List<List<KeyDef>>>> = mapOf(
        "base"  to (BASE_LEFT  to BASE_RIGHT),
        "lower" to (LOWER_LEFT to LOWER_RIGHT),
        "raise" to (RAISE_LEFT to RAISE_RIGHT),
        "adj"   to (ADJ_LEFT   to ADJ_RIGHT),
        "func"  to (FUNC_LEFT  to FUNC_RIGHT)
    )
}
