package com.codekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

enum class EnterIntent { LINE_BREAK, EDITOR_ACTION, KEY_ENTER }

/**
 * Pure Enter-key policy.
 *
 * Plain Enter follows the field's declared IME action (Send/Search/Done) when
 * there is one; otherwise it sends a raw Enter key, which a multiline editor
 * turns into a newline. An explicit Shift+Enter asks for a line break, except
 * in a single-line field that declares an action — there is nowhere to put a
 * newline, so the action wins.
 */
fun resolveEnterIntent(inputType: Int, imeOptions: Int, explicitShift: Boolean): EnterIntent {
    val action = imeOptions and EditorInfo.IME_MASK_ACTION
    val noEnterAction = imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION != 0
    val multiline = inputType and InputType.TYPE_TEXT_FLAG_MULTI_LINE != 0
    val hasAction = !noEnterAction &&
        action != EditorInfo.IME_ACTION_UNSPECIFIED &&
        action != EditorInfo.IME_ACTION_NONE
    return when {
        explicitShift && (multiline || !hasAction) -> EnterIntent.LINE_BREAK
        hasAction -> EnterIntent.EDITOR_ACTION
        else -> EnterIntent.KEY_ENTER
    }
}
