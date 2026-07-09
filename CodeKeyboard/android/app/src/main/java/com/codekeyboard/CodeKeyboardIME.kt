package com.codekeyboard

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.graphics.Color
import android.view.Gravity
import android.util.TypedValue
import com.facebook.react.ReactApplication

class CodeKeyboardIME : InputMethodService() {

  private var reactSurface: com.facebook.react.interfaces.fabric.ReactSurface? = null

  override fun onCreateInputView(): View {
    val density = resources.displayMetrics.density
    val keyboardHeight = (280 * density).toInt()

    val container = FrameLayout(this).apply {
      layoutParams = ViewGroup.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        keyboardHeight
      )
      setBackgroundColor(Color.parseColor("#111111"))
    }

    val loadingView = createLoadingView()
    container.addView(loadingView)

    try {
      val reactHost = (applicationContext as ReactApplication).reactHost

      val props = Bundle().apply {
        putString("mode", "ime")
      }

      val surface = reactHost.createSurface(this, "CodeKeyboard", props)
      surface.start()
      reactSurface = surface

      val surfaceView = surface.view
      if (surfaceView != null) {
        surfaceView.layoutParams = FrameLayout.LayoutParams(
          FrameLayout.LayoutParams.MATCH_PARENT,
          FrameLayout.LayoutParams.MATCH_PARENT
        )
        surfaceView.visibility = View.INVISIBLE
        container.addView(surfaceView)

        surfaceView.post {
          surfaceView.visibility = View.VISIBLE
          container.removeView(loadingView)
        }
      }
    } catch (e: Exception) {
      container.removeAllViews()
      container.addView(createErrorView("Error: ${e.message}"))
    }

    return container
  }

  private fun createLoadingView(): View {
    val density = resources.displayMetrics.density
    return LinearLayout(this).apply {
      orientation = LinearLayout.VERTICAL
      gravity = Gravity.CENTER
      layoutParams = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.MATCH_PARENT,
        FrameLayout.LayoutParams.MATCH_PARENT
      )
      setBackgroundColor(Color.parseColor("#111111"))

      val row = LinearLayout(this@CodeKeyboardIME).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
      }
      for (i in 0 until 10) {
        val key = TextView(this@CodeKeyboardIME).apply {
          val params = LinearLayout.LayoutParams(
            (36 * density).toInt(),
            (44 * density).toInt()
          ).apply { setMargins((2 * density).toInt(), 0, (2 * density).toInt(), 0) }
          layoutParams = params
          setBackgroundColor(Color.parseColor("#2c2c2c"))
          setTextColor(Color.parseColor("#e0e0e0"))
          textSize = 14f
          gravity = Gravity.CENTER
          text = "·"
        }
        row.addView(key)
      }
      addView(row)
    }
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
