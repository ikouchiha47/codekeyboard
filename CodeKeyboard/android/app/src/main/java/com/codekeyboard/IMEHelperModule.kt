package com.codekeyboard

import android.content.Intent
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.content.Context
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod

class IMEHelperModule(reactContext: ReactApplicationContext) :
  ReactContextBaseJavaModule(reactContext) {

  override fun getName(): String = "IMEHelper"

  @ReactMethod
  fun openSettings() {
    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
      flags = Intent.FLAG_ACTIVITY_NEW_TASK
    }
    reactApplicationContext.startActivity(intent)
  }

  @ReactMethod
  fun showPicker() {
    val imm = reactApplicationContext.currentActivity
        ?.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
    imm?.showInputMethodPicker()
  }
}
