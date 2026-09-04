package com.codekeyboard

/**
 * Owns the Ferris Sweep key definitions for all layers and applies a KeyMap
 * to the alpha block of the base layer.
 *
 * Physical form: top row (8 keys) + 3x5 main columns + 2-key thumb cluster per side.
 * The top row and all non-base layers are keymap-invariant.
 */
class FerrisSweepBaseLayerProvider : BaseLayerProvider<FerrisSweepLayerData> {

    override val supportedLayers = listOf("base", "lower", "raise", "adj", "func")

    override fun layers(keyMap: KeyMap): Map<String, FerrisSweepLayerData> = mapOf(
        "base"  to base(keyMap),
        "lower" to LOWER,
        "raise" to RAISE,
        "adj"   to ADJUST,
        "func"  to FUNC,
    )

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun k(label: String, action: String? = null, shift: String? = null, hold: String? = null, ignoreLockedShift: Boolean = false) =
        KeyDef(label, action, shift, holdAction = hold, ignoreLockedShift = ignoreLockedShift)

    private fun empty() = KeyDef("")

    // ── Shared top row ──────────────────────────────────────────────────────

    // Top row is the modifier row: Tab/Esc/Ctrl/Alt/Shift grouped together.
    // ? replaces the old duplicate Bksp (Bksp already lives at main-grid bottom-right).
    private val TOP_ROW = listOf(
        k("Tab","tab"), k("Esc","escape"),
        k("`"), k("Shift","shift", hold="shift"),
        k("Ctrl","ctrl", hold="ctrl"), k("Alt","alt", hold="alt"),
        k("😊","emoji"), k("?"),
    )

    // ── BASE (alpha remapped via KeyMap) ──────────────────────────────────────

    private fun base(km: KeyMap): FerrisSweepLayerData {
        fun a(q: String) = km.map(q)
        return FerrisSweepLayerData(
            topRow = TOP_ROW,
            left = listOf(
                listOf(k(a("q")), k(a("w")), k(a("e")), k(a("r")), k(a("t"))),
                listOf(k(a("a"), hold="ctrl"), k(a("s"), hold="meta"),
                       k(a("d"), hold="alt"),  k(a("f"), hold="shift"), k(a("g"))),
                listOf(k(a("z")), k(a("x")), k(a("c")), k(a("v")), k(a("b"))),
            ),
            right = listOf(
                listOf(k(a("y")), k(a("u")), k(a("i")), k(a("o")), k(a("p"))),
                listOf(k(a("h"), hold="shift"), k(a("j"), hold="alt"),
                       k(a("k"), hold="meta"),  k(a("l"), hold="ctrl"), k(";", shift=":")),
                listOf(k(a("n")), k(a("m")), k(",", shift="<", ignoreLockedShift=true), k(".", shift=">", ignoreLockedShift=true), k("Bksp","backspace")),
            ),
            // Thumb cluster is fixed-function across every layer: LWR/RSE sit
            // innermost (nearest the centre gap), Space/Enter sit outermost.
            thumbL = listOf(k("Spc","space"), k("LWR","lower", hold="lower")),
            thumbR = listOf(k("RSE","raise", hold="raise"), k("Enter","enter")),
        )
    }

    // ── LOWER ─────────────────────────────────────────────────────────────────

    private val LOWER = FerrisSweepLayerData(
        topRow = listOf(
            // ( ) [ ] removed — they're already one row down in the main grid.
            // Lower's own row-1 isn't the alpha row, so the home-row hold-mods
            // (Ctrl/Meta/Alt/Shift tied to a/s/d/f, h/j/k/l) are unreachable
            // while Lower is held; these freed slots are the only place they can go.
            k("Tab","tab"), k("Esc","escape"),
            k("Shift","shift", hold="shift"), k("Ctrl","ctrl", hold="ctrl"),
            k("Alt","alt", hold="alt"), k("Meta","meta", hold="meta"),
            k("{"), k("}"),
        ),
        left = listOf(
            listOf(k("1", shift="!"), k("2", shift="@"), k("3", shift="#"),
                   k("4", shift="$"), k("5", shift="%")),
            // ^ moved here (was a redundant bare ` — Base already has ` at
            // zero cost, so this slot bought nothing until ^ took it).
            listOf(k("^"), k("-", shift="_"), k("=", shift="+"),
                   k("[", shift="{"), k("]", shift="}")),
            listOf(k("~"), k("\\", shift="|"), k("("), k(")"), k("'", shift="\"")),
        ),
        right = listOf(
            listOf(k("6"), k("7", shift="&"), k("8", shift="*"),
                   k("9", shift="("), k("0", shift=")")),
            // ? bare here as a quick-access alternate to its existing shift-of-/
            // route, same pattern as !@#$ getting bare-tap copies below.
            // The duplicate ' that used to sit here (also at left row-2) is gone.
            listOf(k("/", shift="?"), k(":", shift=";"), k("?"), k("<", shift=","), k(">", shift=".")),
            listOf(k("!"), k("@"), k("#"), k("$"), k("Bksp","backspace", shift="delete")),
        ),
        thumbL = listOf(k("Spc","space"), k("LWR","lower", hold="lower")),
        thumbR = listOf(k("RSE","raise", hold="raise"), k("Enter","enter")),
    )

    // ── RAISE ─────────────────────────────────────────────────────────────────

    private val RAISE = FerrisSweepLayerData(
        topRow = listOf(
            k("Tab","tab"), k("Esc","escape"),
            k("F1","f1"), k("F2","f2"), k("F3","f3"),
            k("F4","f4"), k("F5","f5"), k("F6","f6"),
        ),
        left = listOf(
            listOf(k("F7","f7"),  k("F8","f8"),   k("F9","f9"),
                   k("F10","f10"), k("F11","f11")),
            listOf(k("F12","f12"), k("Ins","insert"), k("Home","home"),
                   k("PgUp","page-up"), k("PgDn","page-down")),
            listOf(k("End","end"), k("Cut","cut"), k("Copy","copy"),
                   k("Paste","paste"), k("Undo","undo")),
        ),
        // Right half used to duplicate ~85% of the left half (inherited
        // unchanged from SofleKeyData.kt, where a 4th row diluted it — Ferris
        // has no 4th row, so it was pure waste here). Stripped to what's
        // actually unique: arrows, Redo (a real gap — existed on Func, not
        // here), and Bksp. This is a touchscreen, not a physical split board,
        // so mirroring clipboard/paging actions for off-hand reach doesn't
        // buy what it would on hardware.
        right = listOf(
            listOf(k("←","arrow-left"), k("↓","arrow-down"),
                   k("↑","arrow-up"),   k("→","arrow-right"), k("Redo","redo")),
            listOf(empty(), empty(), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), empty(), k("Bksp","backspace")),
        ),
        thumbL = listOf(k("Spc","space"), k("LWR","lower", hold="lower")),
        thumbR = listOf(k("RSE","raise", hold="raise"), k("Enter","enter")),
    )

    // ── ADJUST ────────────────────────────────────────────────────────────────

    private val ADJUST = FerrisSweepLayerData(
        topRow = listOf(
            k("Tab","tab"), k("Esc","escape"),
            k("Br-","brightness-down"), k("Br+","brightness-up"),
            k("Mute","volume-mute"), k("Vol-","volume-down"),
            k("Vol+","volume-up"), k("Play","media-play"),
        ),
        left = listOf(
            listOf(k("Prev","media-previous"), k("Play","media-play"),
                   k("Next","media-next"), empty(), empty()),
            listOf(empty(), empty(), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), empty(), empty()),
        ),
        right = listOf(
            listOf(empty(), empty(), empty(), empty(), empty()),
            listOf(k("BT"), k("WiFi"), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), k("Del","delete"), k("Bksp","backspace")),
        ),
        thumbL = listOf(k("Spc","space"), k("LWR","lower", hold="lower")),
        thumbR = listOf(k("RSE","raise", hold="raise"), k("Enter","enter")),
    )

    // ── FUNC ──────────────────────────────────────────────────────────────────

    private val FUNC = FerrisSweepLayerData(
        topRow = listOf(
            k("Tab","tab"), k("Esc","escape"),
            k("Undo","undo"), k("Redo","redo"), k("Cut","cut"),
            k("Copy","copy"), k("Paste","paste"), k("SelAll","select-all"),
        ),
        left = listOf(
            listOf(k("Save","save"), k("Find","find"), k("Repl","replace"),
                   k("Cmnt","comment"), k("Dup","duplicate")),
            listOf(k("Fmt","format"), empty(), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), empty(), empty()),
        ),
        right = listOf(
            listOf(empty(), empty(), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), empty(), empty()),
            listOf(empty(), empty(), empty(), k("Del","delete"), k("Bksp","backspace")),
        ),
        thumbL = listOf(k("Spc","space"), k("LWR","lower", hold="lower")),
        thumbR = listOf(k("RSE","raise", hold="raise"), k("Enter","enter")),
    )
}
