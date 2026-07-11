package com.codekeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.widget.LinearLayout

class CodeKeyboardIME : InputMethodService() {

    private lateinit var keyboardView: NativeKeyboardView
    private val kbState = KeyboardState()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density

        keyboardView = NativeKeyboardView(this)
        keyboardView.computer    = SofleLayoutComputer(density)
        keyboardView.kbState     = kbState
        keyboardView.onKeyTapped = { key -> handleKey(key) }
        keyboardView.onKeyHeld   = { key -> handleHold(key) }
        keyboardView.onKeyReleased = { key -> handleRelease(key) }

        // Wrap the keyboard in a container that adds bottom padding for the
        // navigation bar so the bottom row of keys is never hidden.
        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        wrapper.addView(keyboardView)

        // Read navigation bar height from system resource (always reliable
        // for IME windows — IMEs don't dispatch WindowInsets like regular apps).
        val navBarHeight = getNavBarHeight()
        if (navBarHeight > 0) {
            wrapper.setPadding(0, 0, 0, navBarHeight)
        }

        // Initial key compute — will be corrected by onSizeChanged once the
        // view has real dimensions.
        val w = resources.displayMetrics.widthPixels
        val c = keyboardView.computer!!
        keyboardView.setKeys(c.compute(w, kbState.effectiveLayer), kbState, c.heightPx(w))

        return wrapper
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

            // ── Backspace ─────────────────────────────────────────────────────
            // deleteSurroundingText(1,0) works across all editor types.
            // Fall back to KEYCODE_DEL for editors that reject it (e.g. WebViews).
            "backspace" -> {
                if (ic?.deleteSurroundingText(1, 0) != true) {
                    ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_DEL))
                }
            }

            // ── Other action keys ─────────────────────────────────────────────
            "delete"      -> {
                if (ic?.deleteSurroundingText(0, 1) != true) {
                    ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_FORWARD_DEL))
                    ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_FORWARD_DEL))
                }
            }
            "enter"       -> ic?.commitText("\n", 1)
            "tab"         -> {
                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_TAB))
                ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   KeyEvent.KEYCODE_TAB))
            }
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

    // ── Navigation bar height ─────────────────────────────────────────────────

    private fun getNavBarHeight(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resId > 0) {
            return resources.getDimensionPixelSize(resId)
        }
        return (48f * resources.displayMetrics.density + 0.5f).toInt()
    }

    // ── Hold-tap handlers ──────────────────────────────────────────────────────

    private fun handleHold(key: KeyDef) {
        val action = key.holdAction ?: return
        when (action) {
            "ctrl", "shift", "alt", "meta",
            "lower", "raise", "adj", "func" -> {
                kbState.applyHold(action)
                kbState.heldKeyLabel = key.label
                keyboardView.notifyStateChanged(kbState)
            }
            else -> {
                if (action.isNotEmpty()) {
                    handleKey(KeyDef("", action = action))
                }
            }
        }
    }

    private fun handleRelease(key: KeyDef) {
        val action = key.holdAction ?: return
        when (action) {
            "ctrl", "shift", "alt", "meta",
            "lower", "raise", "adj", "func" -> {
                kbState.releaseHold(action)
                kbState.heldKeyLabel = null
                keyboardView.notifyStateChanged(kbState)
            }
            else -> { /* no-op for non-state actions */ }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendCtrl(ic: android.view.inputmethod.InputConnection?, keyCode: Int) {
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON))
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, KeyEvent.META_CTRL_ON))
    }
}
