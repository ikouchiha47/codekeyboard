package com.codekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max

interface KeyPressListener {
    fun onKeyPress(key: KeyDef)
}

class NativeKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var layout: SplitLayoutData = SofleLayout.BASE
    private var listener: KeyPressListener? = null

    private var shiftActive = false
    private var capsActive = false
    private var ctrlActive = false
    private var altActive = false

    private val bgPaint = Paint().apply { color = Color.parseColor("#111111"); style = Paint.Style.FILL }
    private val keyPaint = Paint().apply { color = Color.parseColor("#2c2c2c"); style = Paint.Style.FILL; isAntiAlias = true }
    private val modPaint = Paint().apply { color = Color.parseColor("#252525"); style = Paint.Style.FILL; isAntiAlias = true }
    private val activePaint = Paint().apply { color = Color.parseColor("#4a9eff"); style = Paint.Style.FILL; isAntiAlias = true }
    private val labelPaint = Paint().apply { color = Color.parseColor("#e0e0e0"); textSize = 32f; textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val subPaint = Paint().apply { color = Color.parseColor("#777777"); textSize = 20f; textAlign = Paint.Align.CENTER; isAntiAlias = true }

    private val density = resources.displayMetrics.density
    private val keyGap = 3 * density
    private val rowGap = 5 * density
    private val halfGap = 16 * density
    private val cornerRadius = 6 * density
    private val rowHeight = 48 * density

    private data class KeyRect(val rect: RectF, val key: KeyDef)
    private val keyRects = mutableListOf<KeyRect>()

    fun setLayout(layout: SplitLayoutData) {
        this.layout = layout
        requestLayout()
    }

    fun setListener(listener: KeyPressListener) {
        this.listener = listener
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rows = max(layout.left.size, layout.right.size)
        val height = (rows * rowHeight + (rows - 1) * rowGap + 2 * 6 * density).toInt()
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        keyRects.clear()

        val padding = 6 * density
        val availWidth = width - 2 * padding - halfGap
        val halfWidth = availWidth / 2

        drawHalf(canvas, layout.left, padding, padding, halfWidth, true)
        drawHalf(canvas, layout.right, padding + halfWidth + halfGap, padding, halfWidth, false)
    }

    private fun drawHalf(canvas: Canvas, rows: SplitHalf, startX: Float, startY: Float, halfWidth: Float, isLeft: Boolean) {
        val staggerList = if (isLeft) layout.staggerLeft else layout.staggerRight

        for ((rowIdx, row) in rows.withIndex()) {
            val totalWidthUnits = row.sumOf { it.width.toDouble() }.toFloat()
            val availForKey = halfWidth - (row.size - 1) * keyGap
            val unitWidth = availForKey / totalWidthUnits

            var x = startX
            val staggerPx = if (rowIdx < staggerList.size) staggerList[rowIdx] else 0
            val y = startY + rowIdx * (rowHeight + rowGap) + staggerPx * density * 0.25f

            for (key in row) {
                if (key.label.isEmpty()) {
                    x += key.width * unitWidth + keyGap
                    continue
                }

                val keyW = key.width * unitWidth
                val rect = RectF(x, y, x + keyW, y + rowHeight)

                val paint = when {
                    key.action == "shift" && shiftActive -> activePaint
                    key.action == "caps" && capsActive -> activePaint
                    key.action == "ctrl" && ctrlActive -> activePaint
                    key.action == "alt" && altActive -> activePaint
                    key.action in listOf("shift", "caps", "ctrl", "alt", "fn", "lower", "raise", "adj", "func") -> modPaint
                    else -> keyPaint
                }
                canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)

                val displayLabel = if ((shiftActive || capsActive) && key.shift != null) key.shift else key.label
                val labelY = y + rowHeight / 2 + labelPaint.textSize / 3
                canvas.drawText(displayLabel, rect.centerX(), labelY, labelPaint)

                if (key.shift != null && !shiftActive && !capsActive) {
                    canvas.drawText(key.shift, rect.right - 8 * density, y + 12 * density, subPaint)
                }

                keyRects.add(KeyRect(rect, key))
                x += keyW + keyGap
            }
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN || event.action == MotionEvent.ACTION_UP) {
            for (kr in keyRects) {
                if (kr.rect.contains(event.x, event.y)) {
                    if (event.action == MotionEvent.ACTION_UP) {
                        handleKey(kr.key)
                    }
                    return true
                }
            }
        }
        return super.onTouchEvent(event)
    }

    private fun handleKey(key: KeyDef) {
        when (key.action) {
            "shift" -> { shiftActive = !shiftActive; invalidate() }
            "caps" -> { capsActive = !capsActive; invalidate() }
            "ctrl" -> { ctrlActive = !ctrlActive; invalidate() }
            "alt" -> { altActive = !altActive; invalidate() }
            else -> {
                listener?.onKeyPress(key)
                if (shiftActive && key.action !in listOf("shift", "caps", "ctrl", "alt")) {
                    shiftActive = false
                    invalidate()
                }
            }
        }
    }
}
