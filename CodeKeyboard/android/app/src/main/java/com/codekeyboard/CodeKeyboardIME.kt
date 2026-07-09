package com.codekeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.KeyEvent

class CodeKeyboardIME : InputMethodService(), KeyPressListener {

  private lateinit var keyboardView: NativeKeyboardView
  private var currentLayer = "base"

  override fun onCreateInputView(): View {
    keyboardView = NativeKeyboardView(this)
    keyboardView.setListener(this)
    return keyboardView
  }

  override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
    super.onStartInput(editorInfo, restarting)
    CodeKeyboardModuleHolder.module?.inputConnection = currentInputConnection
  }

  override fun onKeyPress(key: KeyDef) {
    val ic = currentInputConnection ?: return

    when (key.action) {
      "lower" -> { currentLayer = "lower"; keyboardView.setLayout(SofleLayout.LOWER); return }
      "raise" -> { currentLayer = "raise"; keyboardView.setLayout(SofleLayout.RAISE); return }
      "adj" -> { currentLayer = "adj"; keyboardView.setLayout(SofleLayout.ADJUST); return }
      "func" -> { currentLayer = "func"; keyboardView.setLayout(SofleLayout.FUNC); return }
      "base" -> { currentLayer = "base"; keyboardView.setLayout(SofleLayout.BASE); return }
      "backspace" -> {
        val before = ic.getTextBeforeCursor(1, 0)
        if (before != null && before.isNotEmpty()) {
          ic.deleteSurroundingText(before.length, 0)
        }
      }
      "delete" -> ic.deleteSurroundingText(0, 1)
      "enter" -> ic.commitText("\n", 1)
      "tab" -> ic.commitText("    ", 1)
      "space" -> ic.commitText(" ", 1)
      "escape" -> ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
      "arrow-left" -> ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT))
      "arrow-right" -> ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT))
      "arrow-up" -> ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
      "arrow-down" -> ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN))
      else -> {
        val text = key.label
        if (text.isNotEmpty()) {
          ic.commitText(text, 1)
        }
      }
    }
  }
}
