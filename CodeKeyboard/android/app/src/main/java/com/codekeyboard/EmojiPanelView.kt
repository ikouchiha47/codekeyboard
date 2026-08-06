package com.codekeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.io.InputStream

class EmojiPanelView(context: Context) : ScrollView(context) {

    var onEmojiSelected: ((String) -> Unit)? = null

    private data class EmojiEntry(val base: String, val variants: List<String>)
    private data class Category(val name: String, val emoji: List<EmojiEntry>)

    private val COLS = 8
    private val CELL_DP = 44
    private val HEADER_DP = 32
    private val FONT_SP = 26f

    init {
        setBackgroundColor(Color.parseColor("#111111"))
        val dp = resources.displayMetrics.density
        val cellPx = (CELL_DP * dp).toInt()
        val headerPx = (HEADER_DP * dp).toInt()

        val categories = loadEmoji(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }

        for (cat in categories) {
            // Category header
            val header = TextView(context).apply {
                text = cat.name
                setTextColor(Color.parseColor("#888888"))
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, headerPx
                )
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.parseColor("#1a1a1a"))
            }
            container.addView(header)

            // Emoji grid using nested LinearLayouts (no RecyclerView dependency)
            var row: LinearLayout? = null
            cat.emoji.forEachIndexed { idx, entry ->
                if (idx % COLS == 0) {
                    row = LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                    }
                    container.addView(row)
                }
                val cell = makeEmojiCell(context, entry, cellPx, dp)
                row!!.addView(cell)
            }
            // Fill last row with empty cells to maintain grid alignment
            val remaining = cat.emoji.size % COLS
            if (remaining != 0) {
                repeat(COLS - remaining) {
                    row!!.addView(View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(cellPx, cellPx)
                    })
                }
            }
        }

        addView(container)
    }

    private fun makeEmojiCell(
        context: Context,
        entry: EmojiEntry,
        cellPx: Int,
        dp: Float,
    ): View {
        val tv = TextView(context).apply {
            text = entry.base
            textSize = FONT_SP
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(cellPx, cellPx)
            isClickable = true
            isFocusable = true
            setOnClickListener { onEmojiSelected?.invoke(entry.base) }
            if (entry.variants.isNotEmpty()) {
                setOnLongClickListener { showVariantPopup(this, entry.variants, dp); true }
            }
        }
        return tv
    }

    private fun showVariantPopup(anchor: View, variants: List<String>, dp: Float) {
        val cellPx = (CELL_DP * dp).toInt()
        val row = LinearLayout(anchor.context).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Color.parseColor("#222222"))
        }
        var popup: PopupWindow? = null
        variants.forEach { v ->
            val tv = TextView(anchor.context).apply {
                text = v
                textSize = FONT_SP
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(cellPx, cellPx)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    onEmojiSelected?.invoke(v)
                    popup?.dismiss()
                }
            }
            row.addView(tv)
        }
        popup = PopupWindow(
            row,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            cellPx,
            true,
        )
        popup.showAsDropDown(anchor)
    }

    private fun loadEmoji(context: Context): List<Category> {
        val stream: InputStream = context.assets.open("emoji.json")
        val json = stream.bufferedReader().readText()
        val arr = JSONArray(json)
        val cats = mutableListOf<Category>()
        for (i in 0 until arr.length()) {
            val catObj = arr.getJSONObject(i)
            val name = catObj.getString("category")
            val emojiArr = catObj.getJSONArray("emoji")
            val entries = mutableListOf<EmojiEntry>()
            for (j in 0 until emojiArr.length()) {
                val e = emojiArr.getJSONObject(j)
                val base = e.getString("base")
                val variantsArr = e.getJSONArray("variants")
                val variants = (0 until variantsArr.length()).map { variantsArr.getString(it) }
                entries += EmojiEntry(base, variants)
            }
            cats += Category(name, entries)
        }
        return cats
    }
}
