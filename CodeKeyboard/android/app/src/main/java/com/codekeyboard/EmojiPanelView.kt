package com.codekeyboard

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONArray
import java.io.InputStream

class EmojiPanelView(context: Context) : LinearLayout(context) {

    var onEmojiSelected: ((String) -> Unit)? = null
    var onBackToKeyboard: (() -> Unit)? = null
    var onDeletePressed: (() -> Unit)? = null

    // Cap our height to the screen's keyboard slot so we never bleed into system nav.
    // The IME window is sized to the keyboard height; MATCH_PARENT on a ScrollView
    // reports unbounded height and the framework lets the window grow full-screen.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxH = resources.displayMetrics.heightPixels / 3
        val h = MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST)
        super.onMeasure(widthMeasureSpec, h)
    }

    private data class EmojiEntry(val base: String, val variants: List<String>)
    private data class Category(val name: String, val icon: String, val emoji: List<EmojiEntry>)

    private val COLS = 8
    private val CELL_DP = 46
    private val TAB_DP = 46
    private val FONT_SP = 26f
    private val TAB_FONT_SP = 22f

    private val BG_DARK = Color.parseColor("#1a1a1a")
    private val BG_BAR = Color.parseColor("#111111")
    private val TEXT_GRAY = Color.parseColor("#888888")
    private val ACCENT_GREEN = Color.parseColor("#4CAF50")
    private val DIVIDER = Color.parseColor("#2a2a2a")

    private val scrollView: ScrollView
    private val tabButtons = mutableListOf<TextView>()
    private val sectionOffsets = mutableListOf<Int>() // y-px of each section header
    private var activeTab = 0

    init {
        orientation = VERTICAL
        setBackgroundColor(BG_DARK)
        val dp = resources.displayMetrics.density

        val categories = loadEmoji(context)

        // ── Tab bar ───────────────────────────────────────────────────────────
        val tabBar = buildTabBar(context, categories, dp)
        addView(tabBar)

        // divider
        addView(View(context).apply {
            setBackgroundColor(DIVIDER)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
        })

        // ── Emoji scroll area ─────────────────────────────────────────────────
        val container = LinearLayout(context).apply {
            orientation = VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        buildEmojiSections(context, categories, container, dp)

        scrollView = ScrollView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f)
            addView(container)
            // Update active tab as user scrolls manually
            setOnScrollChangeListener { _, _, scrollY, _, _ ->
                updateActiveTabFromScroll(scrollY, dp)
            }
        }
        addView(scrollView)

        // divider
        addView(View(context).apply {
            setBackgroundColor(DIVIDER)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 1)
        })

        // ── Bottom bar ────────────────────────────────────────────────────────
        addView(buildBottomBar(context, dp))
    }

    // ── Tab bar ───────────────────────────────────────────────────────────────

    private fun buildTabBar(context: Context, categories: List<Category>, dp: Float): View {
        val tabPx = (TAB_DP * dp).toInt()

        val hsv = HorizontalScrollView(context).apply {
            setBackgroundColor(BG_BAR)
            isHorizontalScrollBarEnabled = false
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, tabPx)
        }

        val row = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        categories.forEachIndexed { i, cat ->
            val tab = TextView(context).apply {
                text = cat.icon
                textSize = TAB_FONT_SP
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(tabPx, tabPx)
                isClickable = true
                isFocusable = true
                setOnClickListener { selectTab(i, dp) }
            }
            tabButtons += tab
            row.addView(tab)
        }

        hsv.addView(row)
        setTabActive(0)
        return hsv
    }

    private fun setTabActive(idx: Int) {
        tabButtons.forEachIndexed { i, tv ->
            if (i == idx) {
                tv.setBackgroundColor(Color.parseColor("#2a2a2a"))
                // underline via bottom border — use a view layered approach: just tint
                tv.setTextColor(Color.WHITE)
            } else {
                tv.setBackgroundColor(Color.TRANSPARENT)
                tv.setTextColor(Color.parseColor("#888888"))
            }
        }
        activeTab = idx
    }

    private fun selectTab(idx: Int, dp: Float) {
        setTabActive(idx)
        if (idx < sectionOffsets.size) {
            scrollView.smoothScrollTo(0, sectionOffsets[idx])
        }
    }

    private fun updateActiveTabFromScroll(scrollY: Int, dp: Float) {
        if (sectionOffsets.isEmpty()) return
        var best = 0
        for (i in sectionOffsets.indices) {
            if (scrollY >= sectionOffsets[i] - (8 * dp).toInt()) best = i
        }
        if (best != activeTab) setTabActive(best)
    }

    // ── Emoji sections ────────────────────────────────────────────────────────

    private fun buildEmojiSections(
        context: Context,
        categories: List<Category>,
        container: LinearLayout,
        dp: Float,
    ) {
        val cellPx = (CELL_DP * dp).toInt()
        val headerH = (28 * dp).toInt()

        // We'll record offsets after layout using a global Y accumulator
        var accY = 0

        for (cat in categories) {
            sectionOffsets += accY

            val header = TextView(context).apply {
                text = cat.name.uppercase()
                setTextColor(TEXT_GRAY)
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.08f
                setPadding((12 * dp).toInt(), (8 * dp).toInt(), (12 * dp).toInt(), (4 * dp).toInt())
                layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, headerH + (12 * dp).toInt())
                gravity = Gravity.BOTTOM
            }
            container.addView(header)
            accY += headerH + (12 * dp).toInt()

            var row: LinearLayout? = null
            cat.emoji.forEachIndexed { idx, entry ->
                if (idx % COLS == 0) {
                    row = LinearLayout(context).apply {
                        orientation = HORIZONTAL
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, cellPx)
                    }
                    container.addView(row)
                    accY += cellPx
                }
                row!!.addView(makeEmojiCell(context, entry, cellPx, dp))
            }
            val remaining = cat.emoji.size % COLS
            if (remaining != 0) {
                repeat(COLS - remaining) {
                    row!!.addView(View(context).apply {
                        layoutParams = LayoutParams(cellPx, cellPx)
                    })
                }
            }
        }
    }

    // ── Emoji cell ────────────────────────────────────────────────────────────

    private fun makeEmojiCell(context: Context, entry: EmojiEntry, cellPx: Int, dp: Float): View {
        val tv = TextView(context).apply {
            text = entry.base
            textSize = FONT_SP
            gravity = Gravity.CENTER
            layoutParams = LayoutParams(cellPx, cellPx)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                setBackgroundColor(Color.TRANSPARENT)
                onEmojiSelected?.invoke(entry.base)
            }
            if (entry.variants.isNotEmpty()) {
                setOnLongClickListener { showVariantPopup(this, entry.variants, dp); true }
            }
        }
        return tv
    }

    private fun showVariantPopup(anchor: View, variants: List<String>, dp: Float) {
        val cellPx = (CELL_DP * dp).toInt()
        val row = LinearLayout(anchor.context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(Color.parseColor("#2a2a2a"))
            setPadding((4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt(), (4 * dp).toInt())
        }
        var popup: PopupWindow? = null
        variants.forEach { v ->
            val tv = TextView(anchor.context).apply {
                text = v
                textSize = FONT_SP
                gravity = Gravity.CENTER
                layoutParams = LayoutParams(cellPx, cellPx)
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
            (cellPx * 1.2).toInt(),
            true,
        )
        popup.elevation = 8f * dp
        popup.showAsDropDown(anchor, 0, -(cellPx * 2))
    }

    // ── Bottom bar ────────────────────────────────────────────────────────────

    private fun buildBottomBar(context: Context, dp: Float): View {
        val barH = (52 * dp).toInt()

        val bar = LinearLayout(context).apply {
            orientation = HORIZONTAL
            setBackgroundColor(BG_BAR)
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, barH)
            setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0)
        }

        // ABC button
        val abcBtn = TextView(context).apply {
            text = "ABC"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = pillBackground(Color.parseColor("#333333"))
            layoutParams = LayoutParams((72 * dp).toInt(), (36 * dp).toInt())
            setOnClickListener { onBackToKeyboard?.invoke() }
        }
        bar.addView(abcBtn)

        // Spacer
        bar.addView(View(context).apply {
            layoutParams = LayoutParams(0, 1, 1f)
        })

        // Backspace button
        val bkspBtn = TextView(context).apply {
            text = "⌫" // ⌫
            textSize = 20f
            setTextColor(TEXT_GRAY)
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            layoutParams = LayoutParams((48 * dp).toInt(), barH)
            setOnClickListener { onDeletePressed?.invoke() }
        }
        bar.addView(bkspBtn)

        return bar
    }

    private fun pillBackground(color: Int): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = 100f
        }
    }

    // ── Data loading ──────────────────────────────────────────────────────────

    companion object {
        private val CATEGORY_ICONS = mapOf(
            "Smileys & Emotion"  to "😀",
            "People & Body"      to "👋",
            "Animals & Nature"   to "🐶",
            "Food & Drink"       to "🍔",
            "Travel & Places"    to "✈️",
            "Activities"         to "⚽",
            "Objects"            to "💡",
            "Symbols"            to "❤️",
            "Flags"              to "🏳️",
            "Component"          to "🎨",
        )
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
            val icon = CATEGORY_ICONS[name] ?: entries.firstOrNull()?.base ?: "?"
            cats += Category(name, icon, entries)
        }
        return cats
    }
}
