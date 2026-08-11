package com.codekeyboard

/**
 * Maps QWERTY alpha key labels to their equivalents in another key map.
 * Non-alpha keys (numbers, symbols, modifiers, thumb cluster) pass through unchanged.
 * Implementations are pure objects — no Android dependencies.
 */
interface KeyMap {
    val id:   String
    val name: String
    fun map(qwertyLabel: String): String
}

object KeyMapRegistry {
    val ALL: Map<String, KeyMap> = mapOf(
        QwertyKeyMap.id            to QwertyKeyMap,
        ColemakKeyMap.id           to ColemakKeyMap,
        DvorakKeyMap.id            to DvorakKeyMap,
        ProgrammerDvorakKeyMap.id  to ProgrammerDvorakKeyMap,
        ProgrammerColemakKeyMap.id to ProgrammerColemakKeyMap,
    )

    val DEFAULT: KeyMap = QwertyKeyMap

    fun get(id: String): KeyMap = ALL[id] ?: DEFAULT
}
