package com.codekeyboard

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

class SuggestionBarView(context: Context) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
    }

    private val slots = mutableListOf<TextView>()
    var onSlotTapped: ((String) -> Unit)? = null

    private var theme: KeyboardTheme = KeyboardThemes.load()
    private var lastItems: List<String> = emptyList()

    init {
        isHorizontalScrollBarEnabled = false
        overScrollMode = OVER_SCROLL_NEVER
        setBackgroundColor(theme.bg)
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun reloadTheme() {
        theme = KeyboardThemes.load()
        setBackgroundColor(theme.bg)
        if (lastItems.isNotEmpty()) rebuildSlots(lastItems, resources.displayMetrics.density)
    }

    // slot 0 = typed word (blue, committed as-is)
    // slots 1..N = suggestions (grey)
    fun update(word: String, suggestions: List<String>) {
        if (word.isEmpty()) {
            // Bigram next-word suggestions — no composing word, show suggestions only
            if (suggestions.isEmpty()) { clear(); return }
            val dp = context.resources.displayMetrics.density
            rebuildSlots(suggestions, dp)
            return
        }
        val dp = context.resources.displayMetrics.density
        val items = mutableListOf(word) + suggestions.filter { it != word }
        rebuildSlots(items, dp)
    }

    fun clear() {
        row.removeAllViews()
        slots.clear()
        lastItems = emptyList()
    }

    private fun rebuildSlots(items: List<String>, dp: Float) {
        lastItems = items
        row.removeAllViews()
        slots.clear()
        items.forEachIndexed { i, text ->
            if (i > 0) row.addView(makeDivider(dp))
            val tv = TextView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT
                )
                gravity = Gravity.CENTER
                setTextColor(if (i == 0) theme.accent else theme.panelTextMuted)
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                isSingleLine = true
                setPadding(
                    (12 * dp).toInt(), (6 * dp).toInt(),
                    (12 * dp).toInt(), (6 * dp).toInt()
                )
                this.text = text
                setOnClickListener { onSlotTapped?.invoke(text) }
            }
            // slot 0 (typed word) gets subtle pill highlight
            if (i == 0 && items.size > 1) {
                tv.background = makeRounded(4 * dp, theme.keyActive)
            }
            row.addView(tv)
            slots.add(tv)
        }
        scrollTo(0, 0)
    }

    private fun makeRounded(radiusPx: Float, color: Int): GradientDrawable =
        GradientDrawable().apply { setColor(color); cornerRadius = radiusPx }

    private fun makeDivider(dp: Float): View = View(context).apply {
        layoutParams = LinearLayout.LayoutParams(
            (1 * dp).toInt().coerceAtLeast(1),
            (28 * dp).toInt()
        ).apply { gravity = Gravity.CENTER_VERTICAL }
        setBackgroundColor(theme.divider)
    }
}
