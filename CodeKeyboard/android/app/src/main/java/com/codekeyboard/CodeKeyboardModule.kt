package com.codekeyboard

import android.view.inputmethod.InputConnection
import android.text.SpannableStringBuilder
import android.view.KeyEvent
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class CodeKeyboardModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  var inputConnection: InputConnection? = null

  override fun getName(): String = "CodeKeyboardModule"

  @ReactMethod
  fun commitText(text: String) {
    inputConnection?.commitText(SpannableStringBuilder(text), 1)
  }

  @ReactMethod
  fun deleteBackward() {
    inputConnection?.deleteSurrounding(1, 0)
  }

  @ReactMethod
  fun deleteForward() {
    inputConnection?.deleteSurrounding(0, 1)
  }

  @ReactMethod
  fun performEnter() {
    inputConnection?.commitText(SpannableStringBuilder("\n"), 1)
  }

  @ReactMethod
  fun performTab() {
    inputConnection?.commitText(SpannableStringBuilder("    "), 1)
  }

  @ReactMethod
  fun sendDownUpKeyEvents(keyCode: Int) {
    inputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
    inputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
  }

  @ReactMethod
  fun performEditorAction(actionCode: Int) {
    inputConnection?.performEditorAction(actionCode)
  }
}
