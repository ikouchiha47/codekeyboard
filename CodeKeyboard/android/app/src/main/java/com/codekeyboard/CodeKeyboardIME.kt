package com.codekeyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent
import android.widget.LinearLayout

class CodeKeyboardIME : InputMethodService() {

    private lateinit var keyboardView: NativeKeyboardView
    private val kbState = KeyboardState()

    // Modifier name → KeyEvent meta flag — extend this map to add new modifiers.
    private val MODIFIER_META_FLAGS = mapOf(
        "ctrl" to KeyEvent.META_CTRL_ON,
        "alt"  to KeyEvent.META_ALT_ON,
        "meta" to KeyEvent.META_META_ON,
    )

    private val CYCLE_AND_TOGGLE = KeyboardState.CYCLE_MODIFIERS + KeyboardState.TOGGLE_MODIFIERS
    private val STATE_HOLD_ACTIONS = KeyboardState.HOLD_STATE_MODIFIERS + KeyboardState.LAYER_HOLDS

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        KeyboardSettings.init(this)
    }

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
            // Must be opaque — transparent wrapper lets app content bleed through
            // the nav-bar padding area below the keys.
            setBackgroundColor(Color.parseColor("#111111"))
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
        val action = key.action ?: ""

        when (action) {
            // ── Layer keys ────────────────────────────────────────────────────
            in KeyboardState.LAYER_HOLDS -> {
                kbState.cycleLayer(action)
                keyboardView.notifyStateChanged(kbState)
            }

            // ── Modifier state keys ──────────────────────────────────────────
            in CYCLE_AND_TOGGLE -> {
                kbState.cycleModifier(action)
                keyboardView.notifyStateChanged(kbState)
            }

            // ── Backspace / Delete ────────────────────────────────────────────────
            "backspace" -> {
                val sel = ic?.getSelectedText(0)
                if (!sel.isNullOrEmpty()) ic?.commitText("", 1)
                else if (ic?.deleteSurroundingText(1, 0) != true) sendDownUp(ic, KeyEvent.KEYCODE_DEL)
            }
            "delete" -> {
                val sel = ic?.getSelectedText(0)
                if (!sel.isNullOrEmpty()) ic?.commitText("", 1)
                else if (ic?.deleteSurroundingText(0, 1) != true) sendDownUp(ic, KeyEvent.KEYCODE_FORWARD_DEL)
            }

            // ── Other action keys ─────────────────────────────────────────────
            "enter" -> {
                val editorInfo = currentInputEditorInfo
                val action = editorInfo?.let { it.imeOptions and EditorInfo.IME_MASK_ACTION } ?: EditorInfo.IME_ACTION_UNSPECIFIED
                val noEnterAction = editorInfo?.let { it.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION } ?: 0
                if (noEnterAction != 0 || action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE) {
                    sendDownUp(ic, KeyEvent.KEYCODE_ENTER)
                } else {
                    if (ic?.performEditorAction(action) != true) sendDownUp(ic, KeyEvent.KEYCODE_ENTER)
                }
            }
            "tab"         -> sendDownUp(ic, KeyEvent.KEYCODE_TAB)
            "space"       -> ic?.commitText(" ", 1)
            "escape"      -> sendDownUp(ic, KeyEvent.KEYCODE_ESCAPE)
            "arrow-left"  -> sendDownUp(ic, KeyEvent.KEYCODE_DPAD_LEFT)
            "arrow-right" -> sendDownUp(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
            "arrow-up"    -> sendDownUp(ic, KeyEvent.KEYCODE_DPAD_UP)
            "arrow-down"  -> sendDownUp(ic, KeyEvent.KEYCODE_DPAD_DOWN)
            "meta"        -> sendDownUp(ic, KeyEvent.KEYCODE_META_LEFT)

            // ── Edit actions ──────────────────────────────────────────────────
            "cut"        -> ic?.performContextMenuAction(android.R.id.cut)
            "copy"       -> ic?.performContextMenuAction(android.R.id.copy)
            "paste"      -> ic?.performContextMenuAction(android.R.id.paste)
            "select-all" -> ic?.performContextMenuAction(android.R.id.selectAll)
            "undo"       -> ic?.performContextMenuAction(android.R.id.undo)
            "redo"       -> ic?.performContextMenuAction(android.R.id.redo)
            "save"       -> sendCtrl(ic, KeyEvent.KEYCODE_S)
            "find"       -> sendCtrl(ic, KeyEvent.KEYCODE_F)
            "replace"    -> sendCtrl(ic, KeyEvent.KEYCODE_H)
            "format"     -> sendCtrlShift(ic, KeyEvent.KEYCODE_F)
            "comment"    -> sendCtrl(ic, KeyEvent.KEYCODE_SLASH)
            "duplicate"  -> { sendCtrl(ic, KeyEvent.KEYCODE_C); sendCtrl(ic, KeyEvent.KEYCODE_V) }

            // ── Navigation ────────────────────────────────────────────────────
            "home"      -> sendDownUp(ic, KeyEvent.KEYCODE_MOVE_HOME)
            "end"       -> sendDownUp(ic, KeyEvent.KEYCODE_MOVE_END)
            "page-up"   -> sendDownUp(ic, KeyEvent.KEYCODE_PAGE_UP)
            "page-down" -> sendDownUp(ic, KeyEvent.KEYCODE_PAGE_DOWN)
            "insert"    -> sendDownUp(ic, KeyEvent.KEYCODE_INSERT)

            // ── F-keys ────────────────────────────────────────────────────────
            "f1"  -> sendDownUp(ic, KeyEvent.KEYCODE_F1)
            "f2"  -> sendDownUp(ic, KeyEvent.KEYCODE_F2)
            "f3"  -> sendDownUp(ic, KeyEvent.KEYCODE_F3)
            "f4"  -> sendDownUp(ic, KeyEvent.KEYCODE_F4)
            "f5"  -> sendDownUp(ic, KeyEvent.KEYCODE_F5)
            "f6"  -> sendDownUp(ic, KeyEvent.KEYCODE_F6)
            "f7"  -> sendDownUp(ic, KeyEvent.KEYCODE_F7)
            "f8"  -> sendDownUp(ic, KeyEvent.KEYCODE_F8)
            "f9"  -> sendDownUp(ic, KeyEvent.KEYCODE_F9)
            "f10" -> sendDownUp(ic, KeyEvent.KEYCODE_F10)
            "f11" -> sendDownUp(ic, KeyEvent.KEYCODE_F11)
            "f12" -> sendDownUp(ic, KeyEvent.KEYCODE_F12)

            // ── Media / system ────────────────────────────────────────────────
            "volume-mute"     -> sendDownUp(ic, KeyEvent.KEYCODE_VOLUME_MUTE)
            "volume-down"     -> sendDownUp(ic, KeyEvent.KEYCODE_VOLUME_DOWN)
            "volume-up"       -> sendDownUp(ic, KeyEvent.KEYCODE_VOLUME_UP)
            "media-play"      -> sendDownUp(ic, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "media-next"      -> sendDownUp(ic, KeyEvent.KEYCODE_MEDIA_NEXT)
            "media-previous"  -> sendDownUp(ic, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "brightness-down" -> sendDownUp(ic, KeyEvent.KEYCODE_BRIGHTNESS_DOWN)
            "brightness-up"   -> sendDownUp(ic, KeyEvent.KEYCODE_BRIGHTNESS_UP)
            "bt", "wifi"      -> { /* system-level — no IME key event available */ }

            // ── Character keys ────────────────────────────────────────────────
            else -> {
                val text = kbState.resolveLabel(key) ?: key.label
                if (text.isNotEmpty()) {
                    val metaState = kbState.computeMetaState(MODIFIER_META_FLAGS)
                    if (text.length == 1 && metaState != 0) {
                        val keyCode = charToKeyCode(text[0])
                        if (keyCode != null) {
                            ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
                            ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, metaState))
                            kbState.onCharCommitted()
                            keyboardView.notifyStateChanged(kbState)
                            return
                        }
                    }
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
        if (action in STATE_HOLD_ACTIONS) {
            kbState.applyHold(action)
            kbState.heldKeyLabel = key.label
            keyboardView.notifyStateChanged(kbState)
        } else if (action.isNotEmpty()) {
            handleKey(KeyDef("", action = action))
        }
    }

    private fun handleRelease(key: KeyDef) {
        val action = key.holdAction ?: return
        if (action in STATE_HOLD_ACTIONS) {
            kbState.releaseHold(action)
            kbState.heldKeyLabel = null
            keyboardView.notifyStateChanged(kbState)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendDownUp(ic: android.view.inputmethod.InputConnection?, keyCode: Int) {
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   keyCode))
    }

    private fun sendCtrl(ic: android.view.inputmethod.InputConnection?, keyCode: Int) {
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON))
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, KeyEvent.META_CTRL_ON))
    }

    private fun sendCtrlShift(ic: android.view.inputmethod.InputConnection?, keyCode: Int) {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, meta))
    }

    private fun charToKeyCode(c: Char): Int? = when (c.uppercaseChar()) {
        'A' -> KeyEvent.KEYCODE_A
        'B' -> KeyEvent.KEYCODE_B
        'C' -> KeyEvent.KEYCODE_C
        'D' -> KeyEvent.KEYCODE_D
        'E' -> KeyEvent.KEYCODE_E
        'F' -> KeyEvent.KEYCODE_F
        'G' -> KeyEvent.KEYCODE_G
        'H' -> KeyEvent.KEYCODE_H
        'I' -> KeyEvent.KEYCODE_I
        'J' -> KeyEvent.KEYCODE_J
        'K' -> KeyEvent.KEYCODE_K
        'L' -> KeyEvent.KEYCODE_L
        'M' -> KeyEvent.KEYCODE_M
        'N' -> KeyEvent.KEYCODE_N
        'O' -> KeyEvent.KEYCODE_O
        'P' -> KeyEvent.KEYCODE_P
        'Q' -> KeyEvent.KEYCODE_Q
        'R' -> KeyEvent.KEYCODE_R
        'S' -> KeyEvent.KEYCODE_S
        'T' -> KeyEvent.KEYCODE_T
        'U' -> KeyEvent.KEYCODE_U
        'V' -> KeyEvent.KEYCODE_V
        'W' -> KeyEvent.KEYCODE_W
        'X' -> KeyEvent.KEYCODE_X
        'Y' -> KeyEvent.KEYCODE_Y
        'Z' -> KeyEvent.KEYCODE_Z
        '0' -> KeyEvent.KEYCODE_0
        '1' -> KeyEvent.KEYCODE_1
        '2' -> KeyEvent.KEYCODE_2
        '3' -> KeyEvent.KEYCODE_3
        '4' -> KeyEvent.KEYCODE_4
        '5' -> KeyEvent.KEYCODE_5
        '6' -> KeyEvent.KEYCODE_6
        '7' -> KeyEvent.KEYCODE_7
        '8' -> KeyEvent.KEYCODE_8
        '9' -> KeyEvent.KEYCODE_9
        '\n' -> KeyEvent.KEYCODE_ENTER
        ' ' -> KeyEvent.KEYCODE_SPACE
        '\t' -> KeyEvent.KEYCODE_TAB
        else -> null
    }
}
