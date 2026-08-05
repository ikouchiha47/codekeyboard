package com.codekeyboard

import android.view.inputmethod.InputConnection

interface TextInputConnection {
    fun appendComposing(text: String)
    fun commitText(text: String)
    fun backspace()
    fun clearComposing()
    fun getTextBeforeCursor(maxChars: Int): String
    fun getSelectedText(): String
}

class AndroidTextInputConnection(
    private val ic: InputConnection
) : TextInputConnection {

    override fun appendComposing(text: String) {
        ic.setComposingText(text, 1)
    }

    override fun commitText(text: String) {
        ic.commitText(text, 1)
        // commitText implicitly clears the composing region — do NOT call
        // finishComposingText after, it causes double-clear on some apps.
    }

    override fun backspace() {
        ic.deleteSurroundingText(1, 0)
    }

    override fun clearComposing() {
        ic.finishComposingText()
    }

    override fun getTextBeforeCursor(maxChars: Int): String =
        ic.getTextBeforeCursor(maxChars, 0)?.toString() ?: ""

    override fun getSelectedText(): String =
        ic.getSelectedText(0)?.toString() ?: ""
}
