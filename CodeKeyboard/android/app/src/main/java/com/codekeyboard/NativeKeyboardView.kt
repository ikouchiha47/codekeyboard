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
 * Dumb canvas renderer. Knows nothing about Sofle, Corne, QWERTY, or any
 * specific layout. It receives a pre-computed list of PositionedKey objects
 * and renders them. Touch hit-testing uses the same rects.
 *
 * All layout geometry and state logic live elsewhere:
 *   - SofleLayoutComputer  → computes PositionedKey list
 *   - KeyboardState        → owns modifier / layer state
 *   - CodeKeyboardIME      → orchestrates everything
 */
class NativeKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // ── Callback ──────────────────────────────────────────────────────────────

    /** Called when the user lifts their finger off a key. */
    var onKeyTapped: ((KeyDef) -> Unit)? = null

    // ── Data ──────────────────────────────────────────────────────────────────

    private var keys: List<PositionedKey> = emptyList()
    private var state: KeyboardState = KeyboardState()
    private var viewHeightPx: Int = 0

    /** Called by the IME to provide a computer and state. View recomputes on every size change. */
    var computer: KeyboardLayoutComputer? = null
    var kbState: KeyboardState = KeyboardState()

    /**
     * Push a new set of pre-computed keys and matching state snapshot.
     * Triggers a redraw.
     */
    fun setKeys(keys: List<PositionedKey>, state: KeyboardState, heightPx: Int) {
        this.keys        = keys
        this.state       = state
        this.viewHeightPx = heightPx
        requestLayout()
        invalidate()
    }

    /** Called when the IME state changes — recomputes if we already have a valid width. */
    fun notifyStateChanged(newState: KeyboardState) {
        this.kbState = newState
        if (width > 0) recompute(width)
        else invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) recompute(w)
    }

    private fun recompute(w: Int) {
        val c = computer ?: return
        val computed = c.compute(w, kbState.layer)
        val h        = c.heightPx(w)
        setKeys(computed, kbState, h)
    }

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (viewHeightPx > 0) viewHeightPx else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    private val density    = resources.displayMetrics.density
    private val cornerR    = 8f * density

    private val bgPaint    = Paint().apply { color = Color.parseColor("#111111"); style = Paint.Style.FILL }
    private val keyPaint   = Paint().apply { color = Color.parseColor("#2c2c2c"); style = Paint.Style.FILL; isAntiAlias = true }
    private val modPaint   = Paint().apply { color = Color.parseColor("#252525"); style = Paint.Style.FILL; isAntiAlias = true }
    private val activePaint= Paint().apply { color = Color.parseColor("#1a3a5c"); style = Paint.Style.FILL; isAntiAlias = true }
    private val lockedPaint= Paint().apply { color = Color.parseColor("#264f78"); style = Paint.Style.FILL; isAntiAlias = true }
    private val thumbPaint = Paint().apply { color = Color.parseColor("#1a2a3a"); style = Paint.Style.FILL; isAntiAlias = true }
    private val layerPaint = Paint().apply { color = Color.parseColor("#162616"); style = Paint.Style.FILL; isAntiAlias = true }
    private val funcPaint  = Paint().apply { color = Color.parseColor("#2a1a1a"); style = Paint.Style.FILL; isAntiAlias = true }

    private val labelPaint = Paint().apply {
        color = Color.parseColor("#e0e0e0"); textAlign = Paint.Align.CENTER; isAntiAlias = true
    }
    private val subPaint   = Paint().apply {
        color = Color.parseColor("#777777"); textAlign = Paint.Align.RIGHT; isAntiAlias = true
    }
    private val accentPaint= Paint().apply {
        color = Color.parseColor("#4a9eff"); textAlign = Paint.Align.CENTER; isAntiAlias = true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        for (pk in keys) {
            drawKey(canvas, pk)
        }
    }

    private fun drawKey(canvas: Canvas, pk: PositionedKey) {
        val key  = pk.key
        val rect = pk.rect

        // ── Background paint selection ────────────────────────────────────────
        val action = key.action
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
            action == "func"   -> funcPaint
            action in LAYER_ACTIONS -> layerPaint
            action in THUMB_ACTIONS -> thumbPaint
            action in MOD_ACTIONS  -> modPaint
            else               -> keyPaint
        }
        canvas.drawRoundRect(rect, cornerR, cornerR, bg)

        // ── Label ─────────────────────────────────────────────────────────────
        val keyH   = rect.height()
        val keyW   = rect.width()

        // Scale text to fit: smaller for narrow keys or long labels
        val baseSize = keyH * 0.30f
        val label    = state.resolveLabel(key) ?: key.label
        val textSize = when {
            label.length > 4 -> baseSize * 0.65f
            label.length > 2 -> baseSize * 0.80f
            else             -> baseSize
        }
        labelPaint.textSize = textSize.coerceIn(density * 8, density * 14)

        val textPaint = when {
            action in LAYER_ACTIONS -> accentPaint.also { it.textSize = labelPaint.textSize }
            action == "func"        -> accentPaint.also { it.textSize = labelPaint.textSize; it.color = Color.parseColor("#FFB74D") }
            action in THUMB_ACTIONS -> accentPaint.also { it.textSize = labelPaint.textSize; it.color = Color.parseColor("#7ab8ff") }
            else                    -> labelPaint
        }
        // Restore colors after reuse
        accentPaint.color = Color.parseColor("#4a9eff")

        val textY = rect.centerY() + textPaint.textSize * 0.35f
        canvas.drawText(label, rect.centerX(), textY, textPaint)

        // ── Shift sub-label (top-right corner) ────────────────────────────────
        if (key.shift != null && !state.isShiftActive && !state.isCapsActive) {
            subPaint.textSize = (keyH * 0.18f).coerceIn(density * 6, density * 9)
            canvas.drawText(key.shift, rect.right - density * 3f, rect.top + subPaint.textSize + density * 1f, subPaint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val x   = event.getX(idx)
                val y   = event.getY(idx)
                hitTest(x, y)?.let { onKeyTapped?.invoke(it) }
                return true
            }
        }
        return true  // consume all events so the IME doesn't steal focus
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
