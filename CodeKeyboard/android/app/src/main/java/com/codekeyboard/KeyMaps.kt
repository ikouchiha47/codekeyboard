package com.codekeyboard

// ── Helpers ───────────────────────────────────────────────────────────────────

private fun tableMap(table: Map<String, String>): (String) -> String = { q ->
    val lower = q.lowercase()
    val mapped = table[lower]
    if (mapped == null) q
    else if (q[0].isUpperCase()) mapped.uppercase() else mapped
}

// ── QWERTY (identity) ─────────────────────────────────────────────────────────

object QwertyKeyMap : KeyMap {
    override val id   = "qwerty"
    override val name = "QWERTY"
    override fun map(q: String) = q
}

// ── Colemak ───────────────────────────────────────────────────────────────────
// Source (verified): https://colemak.com — official site, 17 keys change from QWERTY.
// Top:  Q W F P G J L U Y ;   (e→f r→p t→g y→j u→l i→u o→y p→;)
// Home: A R S T D H N E I O   (s→r d→s f→t g→d j→n k→e l→i ;→o)
// Bot:  Z X C V B K M , . /   (n→k)

object ColemakKeyMap : KeyMap {
    override val id   = "colemak"
    override val name = "Colemak"
    private val fn = tableMap(mapOf(
        "e" to "f", "r" to "p", "t" to "g",
        "y" to "j", "u" to "l", "i" to "u", "o" to "y", "p" to ";",
        "s" to "r", "d" to "s", "f" to "t", "g" to "d",
        "j" to "n", "k" to "e", "l" to "i", ";" to "o",
        "n" to "k",
    ))
    override fun map(q: String) = fn(q)
}

// ── Colemak-DH (Mod-DH) ──────────────────────────────────────────────────────
// Source (verified): https://colemakmods.github.io/mod-dh/ , https://github.com/ColemakMods/mod-dh
// Variant that moves D and H to stronger positions on the bottom row; G reverts
// to its QWERTY position.
// Top:  Q W F P B J L U Y ;
// Home: A R S T G M N E I O
// Bot:  Z X C D V K H , . /

object ColemakDHKeyMap : KeyMap {
    override val id   = "colemak-dh"
    override val name = "Colemak-DH"
    private val fn = tableMap(mapOf(
        "e" to "f", "r" to "p", "t" to "b",
        "y" to "j", "u" to "l", "i" to "u", "o" to "y", "p" to ";",
        "s" to "r", "d" to "s", "f" to "t",
        "h" to "m", "j" to "n", "k" to "e", "l" to "i", ";" to "o",
        "v" to "d", "b" to "v", "n" to "k", "m" to "h",
    ))
    override fun map(q: String) = fn(q)
}

// ── Dvorak ────────────────────────────────────────────────────────────────────
// Source (verified): https://en.wikipedia.org/wiki/Dvorak_keyboard_layout — ANSI-standardized (INCITS 207).
// Top:  ' , . P Y F G C R L
// Home: A O E U I D H T N S
// Bot:  ; Q J K X B M W V Z

object DvorakKeyMap : KeyMap {
    override val id   = "dvorak"
    override val name = "Dvorak"
    private val fn = tableMap(mapOf(
        "q" to "'", "w" to ",", "e" to ".", "r" to "p", "t" to "y",
        "y" to "f", "u" to "g", "i" to "c", "o" to "r", "p" to "l",
        "s" to "o", "d" to "e", "f" to "u", "g" to "i",
        "h" to "d", "j" to "h", "k" to "t", "l" to "n", ";" to "s",
        "z" to ";", "x" to "q", "c" to "j", "v" to "k", "b" to "x",
        "n" to "b", "m" to "w", "," to "v", "." to "z",
    ))
    override fun map(q: String) = fn(q)
}

// ── Programmer Dvorak ─────────────────────────────────────────────────────────
// Source (verified): https://www.kaufmann.no/roland/dvorak/ — Roland Kaufmann's official site.
// Same alpha positions as standard Dvorak. The difference is on the number/symbol
// row (odds left, evens right, symbols unshifted) — handled in lower/raise layers.
// On the alpha block: ' and ; swap relative to standard Dvorak.
// Top:  ; , . P Y F G C R L
// Home: A O E U I D H T N S
// Bot:  ' Q J K X B M W V Z

object ProgrammerDvorakKeyMap : KeyMap {
    override val id   = "programmer-dvorak"
    override val name = "Prog. Dvorak"
    private val fn = tableMap(mapOf(
        "q" to ";", "w" to ",", "e" to ".", "r" to "p", "t" to "y",
        "y" to "f", "u" to "g", "i" to "c", "o" to "r", "p" to "l",
        "s" to "o", "d" to "e", "f" to "u", "g" to "i",
        "h" to "d", "j" to "h", "k" to "t", "l" to "n", ";" to "s",
        "z" to "'", "x" to "q", "c" to "j", "v" to "k", "b" to "x",
        "n" to "b", "m" to "w", "," to "v", "." to "z",
    ))
    override fun map(q: String) = fn(q)
}

// ── Programmer Colemak ────────────────────────────────────────────────────────
// Alpha positions identical to standard Colemak. Programmer variant differences
// are on the number/symbol row only — handled in lower/raise layer data.

object ProgrammerColemakKeyMap : KeyMap {
    override val id   = "programmer-colemak"
    override val name = "Prog. Colemak"
    private val fn = ColemakKeyMap::map
    override fun map(q: String) = fn(q)
}
