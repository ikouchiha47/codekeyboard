package com.codekeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class SuggestionBarView(context: Context) : LinearLayout(context) {

    private val slots: Array<TextView>
    var onSlotTapped: ((String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.parseColor("#1e1e1e"))
        val dp = context.resources.displayMetrics.density

        slots = Array(3) { i ->
            val tv = TextView(context).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                gravity = Gravity.CENTER
                setTextColor(
                    if (i == 0) Color.parseColor("#4a9eff")
                    else Color.parseColor("#999999")
                )
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                setPadding(
                    (8 * dp).toInt(), (6 * dp).toInt(),
                    (8 * dp).toInt(), (6 * dp).toInt()
                )
                setOnClickListener { dispatchTap(i) }
            }
            addView(tv)
            if (i < 2) addView(makeDivider(dp))
            tv
        }
    }

    fun update(word: String, suggestions: List<String>) {
        val dp = context.resources.displayMetrics.density
        slots[0].text = if (word.isNotEmpty()) suggestions.getOrNull(0) ?: word else ""
        slots[1].text = suggestions.getOrNull(1) ?: ""
        slots[2].text = suggestions.getOrNull(2) ?: ""
        slots[0].background = if (suggestions.isNotEmpty() && word.isNotEmpty())
            makeRounded(4 * dp, Color.parseColor("#1a3a5c"))
        else null
    }

    fun clear() {
        slots[0].text = ""
        slots[1].text = ""
        slots[2].text = ""
        slots[0].background = null
    }

    private fun dispatchTap(index: Int) {
        val word = slots[index].text.toString()
        if (word.isNotEmpty()) onSlotTapped?.invoke(word)
    }

    private fun makeRounded(radiusPx: Float, color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    private fun makeDivider(dp: Float): View = View(context).apply {
        layoutParams = LayoutParams(
            (1 * dp).toInt().coerceAtLeast(1),
            (28 * dp).toInt()
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        setBackgroundColor(Color.parseColor("#333333"))
    }
}
