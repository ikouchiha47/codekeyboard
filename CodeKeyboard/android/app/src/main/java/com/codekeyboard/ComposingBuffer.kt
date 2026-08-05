package com.codekeyboard

class ComposingBuffer {
    private val buf = StringBuilder()

    val text: String get() = buf.toString()
    val isEmpty: Boolean get() = buf.isEmpty()

    fun append(char: String): String {
        buf.append(char)
        return buf.toString()
    }

    fun backspace(): Boolean {
        if (buf.isEmpty()) return false
        buf.deleteCharAt(buf.length - 1)
        return true
    }

    fun flush(): String {
        val t = buf.toString()
        buf.clear()
        return t
    }

    fun clear() {
        buf.clear()
    }
}
