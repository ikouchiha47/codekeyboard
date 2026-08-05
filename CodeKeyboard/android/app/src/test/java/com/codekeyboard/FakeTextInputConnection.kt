package com.codekeyboard

class FakeTextInputConnection : TextInputConnection {
    val committed = StringBuilder()
    var composing: String = ""
    var finishComposingCalled = false

    override fun appendComposing(text: String) { composing = text }

    override fun commitText(text: String) {
        committed.append(text)
        composing = ""
    }

    override fun backspace() {
        if (committed.isNotEmpty()) committed.deleteCharAt(committed.length - 1)
    }

    override fun clearComposing() {
        composing = ""
        finishComposingCalled = true
    }

    override fun getTextBeforeCursor(maxChars: Int): String =
        committed.toString().takeLast(maxChars)

    override fun getSelectedText(): String = ""

    fun reset() {
        committed.clear()
        composing = ""
        finishComposingCalled = false
    }
}
