package com.codekeyboard

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
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

    // ── Callbacks ─────────────────────────────────────────────────────────────

    var onKeyTapped:   ((KeyDef) -> Unit)? = null
    var onKeyHeld:     ((KeyDef) -> Unit)? = null
    var onKeyReleased: ((KeyDef) -> Unit)? = null

    // ── Data ──────────────────────────────────────────────────────────────────

    private var keys: List<PositionedKey> = emptyList()
    private var state: KeyboardState = KeyboardState()
    private var viewHeightPx: Int = 0
    private var snapThresholdPx: Float = 0f

    // Auto-repeat (backspace/delete)
    private val repeatHandler = Handler(Looper.getMainLooper())
    private var repeatKeyDef: KeyDef? = null
    private var repeatPointerId = -1

    private val repeatRunnable = object : Runnable {
        override fun run() {
            val key = repeatKeyDef ?: return
            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onKeyTapped?.invoke(key)
            repeatHandler.postDelayed(this, REPEAT_INTERVAL_MS)
        }
    }

    // Hold-tap (home row mods, thumb layer-holds) — timer path
    private val holdTapHandler = Handler(Looper.getMainLooper())
    private var holdTapKeyDef: KeyDef? = null
    private var holdTapPointerId = -1
    private var holdTapFired: Boolean = false

    private val holdTapRunnable = Runnable {
        val key = holdTapKeyDef ?: return@Runnable
        holdTapFired = true
        onKeyHeld?.invoke(key)
    }

    // Dedicated-modifier hold — activate-on-down path, but with a MIN_TAP_MS delay
    // to filter out phantom capacitive touches (<20ms) that the hardware generates
    // on adjacent keys while a real key is held.
    // heldFired=true once the MIN_TAP_MS runnable fires and onKeyHeld is invoked.
    private data class ModHoldEntry(
        val key: KeyDef,
        var otherKeyPressed: Boolean,
        val startTime: Long,
        var heldFired: Boolean = false,
    )
    private val modHoldByPointer = HashMap<Int, ModHoldEntry>()

    var computer: KeyboardLayoutComputer? = null
    var kbState: KeyboardState = KeyboardState()

    fun setKeys(keys: List<PositionedKey>, state: KeyboardState, heightPx: Int) {
        this.keys            = keys
        this.state           = state
        this.viewHeightPx    = heightPx
        this.snapThresholdPx = computer?.maxSafeSnapPx(if (width > 0) width else heightPx) ?: 0f
        requestLayout()
        invalidate()
    }

    fun notifyStateChanged(newState: KeyboardState) {
        this.kbState = newState
        android.util.Log.e("CKB_HOLD", "notifyStateChanged: effectiveLayer=${newState.effectiveLayer} layerHeld=${newState.layerHeld}")
        if (width > 0) recompute(width) else { state = newState; invalidate() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w > 0) recompute(w)
    }

    private fun recompute(w: Int) {
        val c = computer ?: return
        setKeys(c.compute(w, kbState.effectiveLayer), kbState, c.heightPx(w))
    }

    // ── Measure ───────────────────────────────────────────────────────────────

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = if (viewHeightPx > 0) viewHeightPx else MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
    }

    // ── Draw ──────────────────────────────────────────────────────────────────

    private val density       = resources.displayMetrics.density
    private val cornerR       = 8f * density
    private val hitExpandPx   = density * HIT_EXPAND_DP
    private val drawRect      = RectF()   // reused per key to avoid allocation

    var debugHitRects: Boolean = false

    private val hitRectPaint = Paint().apply {
        color = Color.parseColor("#ff0000")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
        isAntiAlias = true
    }

    private var theme = KeyboardThemes.load()

    private val bgPaint        = Paint().apply { style = Paint.Style.FILL }
    private val keyPaint       = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val modPaint       = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val activePaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val lockedPaint    = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val thumbPaint     = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val layerPaint     = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val funcPaint      = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val labelPaint     = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val subPaint       = Paint().apply { textAlign = Paint.Align.RIGHT;  isAntiAlias = true }
    private val accentPaint    = Paint().apply { textAlign = Paint.Align.CENTER; isAntiAlias = true }
    private val accentBarPaint = Paint().apply { style = Paint.Style.FILL }

    init { applyTheme() }

    fun reloadTheme() {
        theme = KeyboardThemes.load()
        applyTheme()
        invalidate()
    }

    private fun applyTheme() {
        bgPaint.color     = theme.bg
        keyPaint.color    = theme.key
        modPaint.color    = theme.keyMod
        activePaint.color = theme.keyActive
        lockedPaint.color = theme.keyLocked
        thumbPaint.color  = theme.keyThumb
        layerPaint.color  = theme.keyLayer
        funcPaint.color   = theme.keyLayer
        labelPaint.color  = theme.label
        subPaint.color    = theme.labelSub
        accentPaint.color = theme.accent
    }

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

        // ── Bottom accent bar (only when key is active) ───────────────────────
        val isActive = when {
            action == "shift" && state.isShiftActive -> true
            action == "caps"  && state.isCapsActive  -> true
            action == "ctrl"  && state.isCtrlActive  -> true
            action == "alt"   && state.isAltActive   -> true
            action in LAYER_ACTIONS && state.layer == action -> true
            key.holdAction != null && state.heldKeyLabel == key.label -> true
            else -> false
        }
        if (isActive) {
            val barColor = theme.accent
            accentBarPaint.color = barColor
            val barH = 2.5f * density
            val barInset = drawRect.width() * 0.15f
            canvas.drawRoundRect(
                drawRect.left + barInset,
                drawRect.bottom + 2f * density,
                drawRect.right - barInset,
                drawRect.bottom + 2f * density + barH,
                barH / 2f, barH / 2f,
                accentBarPaint
            )
        }

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
                it.color    = theme.labelLayer
            }
            action == "func" -> accentPaint.also {
                it.textSize = labelPaint.textSize
                it.color    = theme.labelLayer
            }
            action in THUMB_ACTIONS -> accentPaint.also {
                it.textSize = labelPaint.textSize
                it.color    = theme.labelMod
            }
            else -> labelPaint
        }

        canvas.drawText(label, kr.centerX, kr.centerY + textPaint.textSize * 0.35f, textPaint)

        // ── Shift sub-label ───────────────────────────────────────────────────
        if (key.shift != null && !state.isShiftActive && !state.isCapsActive) {
            subPaint.textSize = (kr.height * 0.18f).coerceIn(density * 6f, density * 9f)
            canvas.drawText(key.shift, kr.right - density * 3f, kr.top + subPaint.textSize + density, subPaint)
        }

        // ── Debug hit rect ────────────────────────────────────────────────────
        if (debugHitRects) {
            canvas.drawRect(kr.left - hitExpandPx, kr.top - hitExpandPx,
                            kr.right + hitExpandPx, kr.bottom + hitExpandPx, hitRectPaint)
        }
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                val idx = event.actionIndex
                val pid = event.getPointerId(idx)
                hitTest(event.getX(idx), event.getY(idx))?.let { key ->

                    if (key.holdAction != null && key.action in DEDICATED_MOD_ACTIONS) {
                        // Dedicated modifier: delay activation by MIN_TAP_MS to filter phantom
                        // capacitive touches. Haptic fires only after the delay confirms real intent.
                        val entry = ModHoldEntry(key, false, System.currentTimeMillis())
                        modHoldByPointer[pid] = entry
                        android.util.Log.e("CKB_HOLD", "DOWN dedicated pid=$pid action=${key.action} holdAction=${key.holdAction}")
                        postDelayed({
                            // Entry removed by UP or CANCEL before delay elapsed → phantom, ignore.
                            if (modHoldByPointer[pid] !== entry) return@postDelayed
                            entry.heldFired = true
                            performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                            onKeyHeld?.invoke(key)
                        }, MIN_TAP_MS)
                    } else if (key.holdAction != null) {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        // Home-row mod / thumb space: timer path.
                        if (holdTapKeyDef != null) {
                            holdTapHandler.removeCallbacksAndMessages(null)
                            if (!holdTapFired) onKeyTapped?.invoke(holdTapKeyDef!!)
                            else               onKeyReleased?.invoke(holdTapKeyDef!!)
                        }
                        holdTapKeyDef = key
                        holdTapPointerId = pid
                        holdTapFired = false
                        holdTapHandler.postDelayed(holdTapRunnable, TAPPING_TERM_MS)
                        // Only mark confirmed holds as having seen another key press.
                        modHoldByPointer.values.filter { it.heldFired }.forEach { it.otherKeyPressed = true }
                    } else {
                        performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        // Regular key: fire immediately.
                        // Only mark confirmed holds as having seen another key press.
                        modHoldByPointer.values.filter { it.heldFired }.forEach { it.otherKeyPressed = true }
                        onKeyTapped?.invoke(key)
                        if (key.action in REPEATABLE_ACTIONS) {
                            repeatKeyDef = key
                            repeatPointerId = pid
                            repeatHandler.postDelayed(repeatRunnable, REPEAT_INITIAL_DELAY_MS)
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val pid = event.getPointerId(event.actionIndex)

                modHoldByPointer.remove(pid)?.let { entry ->
                    val elapsedMs = System.currentTimeMillis() - entry.startTime
                    android.util.Log.e("CKB_HOLD", "UP dedicated pid=$pid action=${entry.key.action} elapsed=${elapsedMs}ms heldFired=${entry.heldFired} otherPressed=${entry.otherKeyPressed}")
                    if (!entry.heldFired) {
                        // Phantom touch — onKeyHeld never fired, delayed runnable will see
                        // entry is gone and bail out. Nothing to release in state machine.
                        return@let
                    }
                    // Layer hold keys (lower/raise/adj/func): purely hold-and-release.
                    // Never cycle the latch on quick release — that causes every short hold
                    // to toggle the layer latch, creating a rapid-cycle loop.
                    // Modifier hold keys (shift/ctrl/alt): may cycle latch on quick tap.
                    val isLayerHold = entry.key.action in KeyboardState.LAYER_HOLDS
                    val wasQuickTap = !isLayerHold && !entry.otherKeyPressed && elapsedMs < TAPPING_TERM_MS
                    onKeyReleased?.invoke(entry.key)
                    if (wasQuickTap) onKeyTapped?.invoke(entry.key)
                }

                if (pid == holdTapPointerId) {
                    val key = holdTapKeyDef
                    val fired = holdTapFired
                    holdTapHandler.removeCallbacksAndMessages(null)
                    holdTapKeyDef = null
                    holdTapPointerId = -1
                    holdTapFired = false
                    if (key != null) {
                        if (!fired) onKeyTapped?.invoke(key)
                        else        onKeyReleased?.invoke(key)
                    }
                }

                if (pid == repeatPointerId) {
                    cancelRepeat()
                }
            }
            MotionEvent.ACTION_MOVE -> {
                // Dedicated modifier hold is NOT cancelled on slide-off —
                // only ACTION_UP releases it. Finger drift while holding LWR
                // and tapping other keys is expected and must not drop the hold.

                if (holdTapKeyDef != null) {
                    var onHoldKey = false
                    for (i in 0 until event.pointerCount) {
                        if (event.getPointerId(i) == holdTapPointerId) {
                            onHoldKey = hitTest(event.getX(i), event.getY(i)) === holdTapKeyDef
                            break
                        }
                    }
                    if (!onHoldKey) {
                        val key = holdTapKeyDef
                        val fired = holdTapFired
                        holdTapHandler.removeCallbacksAndMessages(null)
                        holdTapKeyDef = null
                        holdTapPointerId = -1
                        holdTapFired = false
                        if (key != null) {
                            if (!fired) {
                                onKeyTapped?.invoke(key)
                            } else {
                                onKeyReleased?.invoke(key)
                            }
                        }
                    }
                }

                if (repeatKeyDef != null) {
                    var onKey = false
                    for (i in 0 until event.pointerCount) {
                        if (event.getPointerId(i) == repeatPointerId) {
                            onKey = hitTest(event.getX(i), event.getY(i)) === repeatKeyDef
                            break
                        }
                    }
                    if (!onKey) cancelRepeat()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelRepeat()
                modHoldByPointer.values.filter { it.heldFired }.forEach { onKeyReleased?.invoke(it.key) }
                modHoldByPointer.clear()
                if (holdTapKeyDef != null) {
                    val key = holdTapKeyDef!!
                    holdTapHandler.removeCallbacksAndMessages(null)
                    if (!holdTapFired) onKeyTapped?.invoke(key) else onKeyReleased?.invoke(key)
                    holdTapKeyDef = null; holdTapPointerId = -1; holdTapFired = false
                }
            }
        }
        return true
    }

    private fun cancelRepeat() {
        repeatHandler.removeCallbacks(repeatRunnable)
        repeatKeyDef = null
        repeatPointerId = -1
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        cancelRepeat()
        modHoldByPointer.values.filter { it.heldFired }.forEach { onKeyReleased?.invoke(it.key) }
        modHoldByPointer.clear()
        holdTapHandler.removeCallbacksAndMessages(null)
        holdTapKeyDef = null; holdTapPointerId = -1; holdTapFired = false
    }

    private fun hitTest(x: Float, y: Float): KeyDef? {
        var best: KeyDef? = null
        var bestDist = Float.MAX_VALUE
        for (pk in keys) {
            val kr = pk.rect
            if (x >= kr.left - hitExpandPx && x <= kr.right + hitExpandPx &&
                y >= kr.top - hitExpandPx && y <= kr.bottom + hitExpandPx) return pk.key
            val dx = maxOf(kr.left - x, 0f, x - kr.right)
            val dy = maxOf(kr.top - y, 0f, y - kr.bottom)
            val dist = dx * dx + dy * dy
            if (dist < bestDist) {
                bestDist = dist
                best = pk.key
            }
        }
        return if (bestDist <= snapThresholdPx * snapThresholdPx) best else null
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private val LAYER_ACTIONS = setOf("lower", "raise", "adj", "func")
        // Keys where hold activates immediately (no timer) — dedicated modifier keys only.
        private val DEDICATED_MOD_ACTIONS = setOf("shift", "ctrl", "alt", "lower", "raise", "func", "adj")
        private val THUMB_ACTIONS = setOf("space", "meta")
        private val MOD_ACTIONS   = setOf("shift", "caps", "ctrl", "alt", "enter",
                                          "backspace", "delete", "tab", "escape",
                                          "arrow-left", "arrow-right", "arrow-up", "arrow-down")
        private val REPEATABLE_ACTIONS = setOf("backspace", "delete", "space")
        private const val REPEAT_INITIAL_DELAY_MS = 400L
        private const val REPEAT_INTERVAL_MS = 50L
        private const val TAPPING_TERM_MS = 150L
        private const val MIN_TAP_MS     = 40L   // phantom touches are <20ms; human taps are >60ms
        // Snap radius is now computed dynamically via SofleLayoutComputer.maxSafeSnapPx
        private const val HIT_EXPAND_DP = 2.5f
    }
}
