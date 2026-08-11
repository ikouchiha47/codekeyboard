package com.codekeyboard

object QwertyKeyMap : KeyMap {
    override val id   = "qwerty"
    override val name = "QWERTY"
    override fun map(q: String) = q
}

object ColemakKeyMap : KeyMap {
    override val id   = "colemak"
    override val name = "Colemak"

    private val TABLE = mapOf(
        "q" to "q", "w" to "w", "e" to "f", "r" to "p", "t" to "g",
        "y" to "j", "u" to "l", "i" to "u", "o" to "y", "p" to ";",
        "a" to "a", "s" to "r", "d" to "s", "f" to "t", "g" to "d",
        "h" to "h", "j" to "n", "k" to "e", "l" to "i", ";" to "o",
        "z" to "z", "x" to "x", "c" to "c", "v" to "v", "b" to "b",
        "n" to "k", "m" to "m",
    )

    override fun map(q: String): String {
        val lower = q.lowercase()
        val mapped = TABLE[lower] ?: return q
        return if (q[0].isUpperCase()) mapped.uppercase() else mapped
    }
}

object DvorakKeyMap : KeyMap {
    override val id   = "dvorak"
    override val name = "Dvorak"

    private val TABLE = mapOf(
        "q" to "'", "w" to ",", "e" to ".", "r" to "p", "t" to "y",
        "y" to "f", "u" to "g", "i" to "c", "o" to "r", "p" to "l",
        "a" to "a", "s" to "o", "d" to "e", "f" to "u", "g" to "i",
        "h" to "d", "j" to "h", "k" to "t", "l" to "n", ";" to "s",
        "z" to ";", "x" to "q", "c" to "j", "v" to "k", "b" to "x",
        "n" to "b", "m" to "m",
    )

    override fun map(q: String): String {
        val lower = q.lowercase()
        val mapped = TABLE[lower] ?: return q
        return if (q[0].isUpperCase()) mapped.uppercase() else mapped
    }
}

object ProgrammerDvorakKeyMap : KeyMap {
    override val id   = "programmer-dvorak"
    override val name = "Programmer Dvorak"

    // Alpha remapping is identical to standard Dvorak.
    // Symbol layer differences are handled in the layer data (lower/raise),
    // not here — this map only covers the base alpha block.
    private val TABLE = DvorakKeyMap.let { d ->
        mapOf(
            "q" to "'", "w" to ",", "e" to ".", "r" to "p", "t" to "y",
            "y" to "f", "u" to "g", "i" to "c", "o" to "r", "p" to "l",
            "a" to "a", "s" to "o", "d" to "e", "f" to "u", "g" to "i",
            "h" to "d", "j" to "h", "k" to "t", "l" to "n", ";" to "s",
            "z" to ";", "x" to "q", "c" to "j", "v" to "k", "b" to "x",
            "n" to "b", "m" to "m",
        )
    }

    override fun map(q: String): String {
        val lower = q.lowercase()
        val mapped = TABLE[lower] ?: return q
        return if (q[0].isUpperCase()) mapped.uppercase() else mapped
    }
}

object ProgrammerColemakKeyMap : KeyMap {
    override val id   = "programmer-colemak"
    override val name = "Programmer Colemak"

    // Alpha remapping is identical to standard Colemak.
    // Programmer-specific symbol differences belong in the lower/raise layer data.
    private val TABLE = mapOf(
        "q" to "q", "w" to "w", "e" to "f", "r" to "p", "t" to "g",
        "y" to "j", "u" to "l", "i" to "u", "o" to "y", "p" to ";",
        "a" to "a", "s" to "r", "d" to "s", "f" to "t", "g" to "d",
        "h" to "h", "j" to "n", "k" to "e", "l" to "i", ";" to "o",
        "z" to "z", "x" to "x", "c" to "c", "v" to "v", "b" to "b",
        "n" to "k", "m" to "m",
    )

    override fun map(q: String): String {
        val lower = q.lowercase()
        val mapped = TABLE[lower] ?: return q
        return if (q[0].isUpperCase()) mapped.uppercase() else mapped
    }
}
