package com.codekeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.os.Bundle
import com.facebook.react.ReactApplication

class CodeKeyboardIME : InputMethodService() {

  private var reactSurface: com.facebook.react.interfaces.fabric.ReactSurface? = null

  override fun onCreateInputView(): View {
    val reactHost = (applicationContext as ReactApplication).reactHost

    val props = Bundle().apply {
      putString("mode", "ime")
    }

    val surface = reactHost!!.createSurface(this, "CodeKeyboard", props)
    surface.start()
    reactSurface = surface

    return surface.view!!
  }

  override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
    super.onStartInput(editorInfo, restarting)
    CodeKeyboardModuleHolder.module?.inputConnection = currentInputConnection
  }

  override fun onDestroy() {
    reactSurface?.stop()
    super.onDestroy()
  }
}
