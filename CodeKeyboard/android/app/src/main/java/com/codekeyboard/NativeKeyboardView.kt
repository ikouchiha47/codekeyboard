package com.codekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View

/**
 * Dumb canvas renderer. Receives List<PositionedKey> and draws them.
 * All geometry lives in KeyboardLayoutComputer. All state lives in KeyboardState.
 * Hit-testing uses KeyRect (no Android dependency). RectF is only created at draw time.
 */
class NativeKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Callback ──────────────────────────────────────────────────────────────

    var onKeyTapped: ((KeyDef) -> Unit)? = null

    // ── Data ──────────────────────────────────────────────────────────────────

    private var keys: List<PositionedKey> = emptyList()
    private var state: KeyboardState = KeyboardState()
    private var viewHeightPx: Int = 0

    var computer: KeyboardLayoutComputer? = null
    var kbState: KeyboardState = KeyboardState()

    fun setKeys(keys: List<PositionedKey>, state: KeyboardState, heightPx: Int) {
        this.keys         = keys
        this.state        = state
        this.viewHeightPx = heightPx
        requestLayout()
        invalidate()
    }

    fun notifyStateChanged(newState: KeyboardState) {
        this.kbState = newState
        if (width > 0) recompute(width) else { state = newState; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) recompute(w)
    }

    private fun recompute(w: Int) {
        val c = computer ?: return
        setKeys(c.compute(w, kbState.layer), kbState, c.heightPx(w))
    }

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (viewHeightPx > 0) viewHeightPx else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    private val density     = resources.displayMetrics.density
    private val cornerR     = 8f * density
    private val drawRect    = RectF()   // reused per key to avoid allocation

    private val bgPaint     = Paint().apply { color = Color.parseColor("#111111"); style = Paint.Style.FILL }
    private val keyPaint    = Paint().apply { color = Color.parseColor("#2c2c2c"); style = Paint.Style.FILL; isAntiAlias = true }
    private val modPaint    = Paint().apply { color = Color.parseColor("#252525"); style = Paint.Style.FILL; isAntiAlias = true }
    private val activePaint = Paint().apply { color = Color.parseColor("#1a3a5c"); style = Paint.Style.FILL; isAntiAlias = true }
    private val lockedPaint = Paint().apply { color = Color.parseColor("#264f78"); style = Paint.Style.FILL; isAntiAlias = true }
    private val thumbPaint  = Paint().apply { color = Color.parseColor("#1a2a3a"); style = Paint.Style.FILL; isAntiAlias = true }
    private val layerPaint  = Paint().apply { color = Color.parseColor("#162616"); style = Paint.Style.FILL; isAntiAlias = true }
    private val funcPaint   = Paint().apply { color = Color.parseColor("#2a1a1a"); style = Paint.Style.FILL; isAntiAlias = true }
    private val labelPaint  = Paint().apply { color = Color.parseColor("#e0e0e0"); textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val subPaint    = Paint().apply { color = Color.parseColor("#777777"); textAlign = Paint.Align.RIGHT;  isAntiAlias = true }
    private val accentPaint = Paint().apply { color = Color.parseColor("#4a9eff"); textAlign = Paint.Align.CENTER; isAntiAlias = true }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        for (pk in keys) drawKey(canvas, pk)
    }

    private fun drawKey(canvas: Canvas, pk: PositionedKey) {
        val key    = pk.key
        val kr     = pk.rect
        val action = key.action

        drawRect.set(kr.left, kr.top, kr.right, kr.bottom)

        val bg = when {
            action == "shift" && state.isShiftActive ->
                if (state.shift == LatchState.LOCKED) lockedPaint else activePaint
            action == "caps"  && state.isCapsActive  -> lockedPaint
            action == "ctrl"  && state.isCtrlActive  ->
                if (state.ctrl  == LatchState.LOCKED) lockedPaint else activePaint
            action == "alt"   && state.isAltActive   ->
                if (state.alt   == LatchState.LOCKED) lockedPaint else activePaint
            action in LAYER_ACTIONS && state.layer == action ->
                if (state.layerState == LatchState.LOCKED) lockedPaint else activePaint
            action == "func"        -> funcPaint
            action in LAYER_ACTIONS -> layerPaint
            action in THUMB_ACTIONS -> thumbPaint
            action in MOD_ACTIONS   -> modPaint
            else                    -> keyPaint
        }
        canvas.drawRoundRect(drawRect, cornerR, cornerR, bg)

        // ── Label ─────────────────────────────────────────────────────────────
        val label    = state.resolveLabel(key) ?: key.label
        val baseSize = kr.height * 0.30f
        labelPaint.textSize = when {
            label.length > 4 -> baseSize * 0.65f
            label.length > 2 -> baseSize * 0.80f
            else             -> baseSize
        }.coerceIn(density * 8f, density * 14f)

        val textPaint: Paint = when {
            action in LAYER_ACTIONS -> accentPaint.also {
                it.textSize = labelPaint.textSize
                it.color    = Color.parseColor("#4a9eff")
            }
            action == "func" -> accentPaint.also {
                it.textSize = labelPaint.textSize
                it.color    = Color.parseColor("#FFB74D")
            }
            action in THUMB_ACTIONS -> accentPaint.also {
                it.textSize = labelPaint.textSize
                it.color    = Color.parseColor("#7ab8ff")
            }
            else -> labelPaint
        }

        canvas.drawText(label, kr.centerX, kr.centerY + textPaint.textSize * 0.35f, textPaint)

        // ── Shift sub-label ───────────────────────────────────────────────────
        if (key.shift != null && !state.isShiftActive && !state.isCapsActive) {
            subPaint.textSize = (kr.height * 0.18f).coerceIn(density * 6f, density * 9f)
            canvas.drawText(key.shift, kr.right - density * 3f, kr.top + subPaint.textSize + density, subPaint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                hitTest(event.getX(idx), event.getY(idx))?.let { onKeyTapped?.invoke(it) }
            }
        }
        return true
    }

    private fun hitTest(x: Float, y: Float): KeyDef? {
        for (pk in keys) {
            if (pk.rect.contains(x, y)) return pk.key
        }
        return null
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private val LAYER_ACTIONS = setOf("lower", "raise", "adj", "func")
        private val THUMB_ACTIONS = setOf("space", "meta")
        private val MOD_ACTIONS   = setOf("shift", "caps", "ctrl", "alt", "enter",
                                          "backspace", "delete", "tab", "escape",
                                          "arrow-left", "arrow-right", "arrow-up", "arrow-down")
    }
}
