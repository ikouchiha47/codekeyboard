package com.codekeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import android.os.Bundle
import com.facebook.react.ReactApplication
import com.facebook.react.ReactRootView

class CodeKeyboardIME : InputMethodService() {

  private lateinit var reactRootView: ReactRootView

  override fun onCreateInputView(): View {
    val reactHost = (applicationContext as ReactApplication).reactHost
    reactRootView = ReactRootView(this)

    val props = Bundle().apply {
      putString("mode", "ime")
    }

    reactRootView.startReactApplication(reactHost, "CodeKeyboard", props)

    return reactRootView
  }

  override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
    super.onStartInput(editorInfo, restarting)
    CodeKeyboardModuleHolder.module?.inputConnection = currentInputConnection
  }

  override fun onDestroy() {
    reactRootView.unmountReactApplication()
    super.onDestroy()
  }
}
