package com.codekeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent

class CodeKeyboardIME : InputMethodService() {

    private lateinit var keyboardView: NativeKeyboardView
    private val kbState = KeyboardState()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density

        keyboardView = NativeKeyboardView(this)
        keyboardView.computer   = SofleLayoutComputer(density)
        keyboardView.kbState    = kbState
        keyboardView.onKeyTapped = { key -> handleKey(key) }

        // Initial render using screen width as estimate.
        // onSizeChanged will recompute once the real width is known.
        val w = resources.displayMetrics.widthPixels
        val c = keyboardView.computer!!
        keyboardView.setKeys(c.compute(w, kbState.layer), kbState, c.heightPx(w))

        return keyboardView
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        CodeKeyboardModuleHolder.module?.inputConnection = currentInputConnection
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    private fun handleKey(key: KeyDef) {
        val ic = currentInputConnection

        when (key.action) {
            // ── Layer keys ────────────────────────────────────────────────────
            "lower", "raise", "adj", "func" -> {
                kbState.cycleLayer(key.action)
                keyboardView.notifyStateChanged(kbState)
                return
            }

            // ── Modifier keys ─────────────────────────────────────────────────
            "shift" -> { kbState.cycleShift(); keyboardView.notifyStateChanged(kbState); return }
            "caps"  -> { kbState.cycleCaps();  keyboardView.notifyStateChanged(kbState); return }
            "ctrl"  -> { kbState.cycleCtrl();  keyboardView.notifyStateChanged(kbState); return }
            "alt"   -> { kbState.cycleAlt();   keyboardView.notifyStateChanged(kbState); return }

            // ── Action keys ───────────────────────────────────────────────────
            "backspace" -> {
                val before = ic?.getTextBeforeCursor(1, 0)
                if (before != null && before.isNotEmpty()) {
                    ic.deleteSurroundingText(before.length, 0)
                }
            }
            "delete"      -> ic?.deleteSurroundingText(0, 1)
            "enter"       -> ic?.commitText("\n", 1)
            "tab"         -> ic?.commitText("    ", 1)
            "space"       -> ic?.commitText(" ", 1)
            "escape"      -> ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
            "arrow-left"  -> ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
            "arrow-right" -> ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
            "arrow-up"    -> ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            "arrow-down"  -> ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
            "meta"        -> ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_META_LEFT))
            "comment"     -> sendCtrl(ic, KeyEvent.KEYCODE_SLASH)
            "duplicate"   -> { sendCtrl(ic, KeyEvent.KEYCODE_C); sendCtrl(ic, KeyEvent.KEYCODE_V) }

            // ── Character keys ────────────────────────────────────────────────
            else -> {
                val text = kbState.resolveLabel(key) ?: key.label
                if (text.isNotEmpty()) {
                    ic?.commitText(text, 1)
                    kbState.onCharCommitted()
                    keyboardView.notifyStateChanged(kbState)
                }
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendCtrl(ic: android.view.inputmethod.InputConnection?, keyCode: Int) {
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON))
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, KeyEvent.META_CTRL_ON))
    }
}
