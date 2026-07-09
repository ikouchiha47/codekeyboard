package com.codekeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import com.facebook.react.ReactApplication

class CodeKeyboardIME : InputMethodService() {

  private var reactSurface: com.facebook.react.interfaces.fabric.ReactSurface? = null

  override fun onCreateInputView(): View {
    val container = FrameLayout(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
      )
      setBackgroundColor(Color.parseColor("#111111"))
    }

    try {
      val reactHost = (applicationContext as ReactApplication).reactHost

      val props = Bundle().apply {
        putString("mode", "ime")
      }

      val surface = reactHost!!.createSurface(this, "CodeKeyboard", props)
      surface.start()
      reactSurface = surface

      val surfaceView = surface.view
      if (surfaceView != null) {
        surfaceView.layoutParams = FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.WRAP_CONTENT
        )
        container.addView(surfaceView)
      } else {
        container.addView(createErrorView("Keyboard view not available"))
      }
    } catch (e: Exception) {
      container.addView(createErrorView("Error: ${e.message}"))
    }

    return container
  }

  private fun createErrorView(message: String): View {
    return TextView(this).apply {
      text = message
      setTextColor(Color.WHITE)
      textSize = 16f
      gravity = Gravity.CENTER
      setPadding(32, 32, 32, 32)
    }
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
