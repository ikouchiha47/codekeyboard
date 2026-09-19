package com.codekeyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class EnterResolverTest {

    private val multiline = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
    private val singleLine = InputType.TYPE_CLASS_TEXT

    @Test fun `multiline with no action and no shift sends raw enter`() {
        assertEquals(
            EnterIntent.KEY_ENTER,
            resolveEnterIntent(multiline, EditorInfo.IME_ACTION_UNSPECIFIED, explicitShift = false),
        )
    }

    @Test fun `multiline with no action and explicit shift inserts line break`() {
        assertEquals(
            EnterIntent.LINE_BREAK,
            resolveEnterIntent(multiline, EditorInfo.IME_ACTION_UNSPECIFIED, explicitShift = true),
        )
    }

    @Test fun `single line with send action and no shift performs editor action`() {
        assertEquals(
            EnterIntent.EDITOR_ACTION,
            resolveEnterIntent(singleLine, EditorInfo.IME_ACTION_SEND, explicitShift = false),
        )
    }

    @Test fun `single line with send action and explicit shift still performs editor action`() {
        // No newline is possible in a single-line field, so the action wins.
        assertEquals(
            EnterIntent.EDITOR_ACTION,
            resolveEnterIntent(singleLine, EditorInfo.IME_ACTION_SEND, explicitShift = true),
        )
    }

    @Test fun `multiline with send action and explicit shift inserts line break`() {
        assertEquals(
            EnterIntent.LINE_BREAK,
            resolveEnterIntent(multiline, EditorInfo.IME_ACTION_SEND, explicitShift = true),
        )
    }

    @Test fun `no enter action flag forces raw enter`() {
        val options = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_ENTER_ACTION
        assertEquals(
            EnterIntent.KEY_ENTER,
            resolveEnterIntent(singleLine, options, explicitShift = false),
        )
    }

    @Test fun `IME_ACTION_NONE sends raw enter`() {
        assertEquals(
            EnterIntent.KEY_ENTER,
            resolveEnterIntent(singleLine, EditorInfo.IME_ACTION_NONE, explicitShift = false),
        )
    }
}
