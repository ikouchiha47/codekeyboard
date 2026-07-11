package com.codekeyboard

/**
 * Computes pixel-exact key positions for the V5 layout.
 *
 * Structure:
 *   ┌──────────────────────────────────────────────┐
 *   │  Top row  (8 keys, full-width, no stagger)   │
 *   ├─────────────────────┬────────────────────────┤
 *   │   Left half         │   Right half           │
 *   │   5 cols × 4 rows   │   5 cols × 4 rows      │
 *   │   stagger L5        │   stagger R5 (mirror)  │
 *   └─────────────────────┴────────────────────────┘
 *
 * Stagger (fraction of keyHeight):
 *   Left  cols 0-4: [0.00, 0.25, 0.50, 0.75, 1.00]
 *   Right cols 0-4: [1.00, 0.75, 0.50, 0.25, 0.00]
 *
 * No Android imports — fully testable with pure JUnit.
 */
class SofleLayoutComputer(density: Float) : KeyboardLayoutComputer {

    override val name   = "Sofle V5"
    override val layers = SofleKeyData.LAYERS.keys.toList()

    // ── Dimensions (px) ───────────────────────────────────────────────────────
    internal val padding    = 6f  * density
    internal val keyGap     = 5f  * density
    internal val rowGap     = 5f  * density
    internal val keyHeight  = 48f * density
    internal val topRowKeys = 8              // Tab + Esc + 6 layer slots

    /** Gap between the left and right halves as a fraction of screen width. */
    internal fun halfGap(screenW: Int): Float = screenW * 0.05f

    // ── Stagger ───────────────────────────────────────────────────────────────
    internal val staggerLeft  = listOf(0f, 0.25f, 0.50f, 0.75f, 1.00f)
    internal val staggerRight = listOf(1.00f, 0.75f, 0.50f, 0.25f, 0f)

    private val maxStagger = 1.00f
    private val numRows    = 4

    // ── Derived ───────────────────────────────────────────────────────────────
    private fun halfWidth(screenW: Int) = (screenW - 2 * padding - halfGap(screenW)) / 2f
    private fun topKeyWidth(screenW: Int) =
        (screenW - 2 * padding - (topRowKeys - 1) * keyGap) / topRowKeys

    // ── Public API ────────────────────────────────────────────────────────────

    override fun heightPx(screenWidthPx: Int): Int =
        (2 * padding
            + keyHeight + rowGap                          // top row + gap below it
            + numRows * (keyHeight + rowGap)              // main rows
            + maxStagger * keyHeight                      // extra for stagger
        ).toInt()

    override fun compute(screenWidthPx: Int, layer: String): List<PositionedKey> {
        val data = SofleKeyData.LAYERS[layer] ?: SofleKeyData.LAYERS["base"]!!
        val out  = mutableListOf<PositionedKey>()

        computeTopRow(data.topRow, screenWidthPx, out)

        val hw       = halfWidth(screenWidthPx)
        val mainTopY = padding + keyHeight + rowGap   // below the top row

        computeHalf(data.left,  staggerLeft,                        padding,             hw, mainTopY, out)
        computeHalf(data.right, staggerRight, padding + hw + halfGap(screenWidthPx), hw, mainTopY, out)

        return out
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun computeTopRow(
        keys: List<KeyDef>,
        screenW: Int,
        out: MutableList<PositionedKey>
    ) {
        val kw = topKeyWidth(screenW)
        val y  = padding
        keys.forEachIndexed { i, key ->
            if (key.label.isEmpty()) return@forEachIndexed
            val x = padding + i * (kw + keyGap)
            out.add(PositionedKey(key, KeyRect(x, y, x + kw, y + keyHeight)))
        }
    }

    override fun exportLayout(): String = SofleLayoutComputer.exportLayoutJson()

    companion object {
        fun exportLayoutJson(): String = buildString {
            append("""{"stagger":{"left":[""")
            append(staggerLeft.joinToString(",") { it.toString() })
            append("""],"right":[""")
            append(staggerRight.joinToString(",") { it.toString() })
            append("""]},"layers":{""")
            SofleKeyData.LAYERS.entries.joinTo(this, ",") { (name, data) ->
                append(name.toJson())
                append(":")
                append(data.toJson())
            }
            append("}}")
        }

        private val staggerLeft  = listOf(0f, 0.25f, 0.50f, 0.75f, 1.00f)
        private val staggerRight = listOf(1.00f, 0.75f, 0.50f, 0.25f, 0f)

        private fun SofleLayerData.toJson(): String = buildString {
            append("""{"topRow":[""")
            topRow.joinTo(this, ",") { it.toJson() }
            append("""],"left":[""")
            left.joinTo(this, ",") { row -> "[" + row.joinToString(",") { it.toJson() } + "]" }
            append("""],"right":[""")
            right.joinTo(this, ",") { row -> "[" + row.joinToString(",") { it.toJson() } + "]" }
            append("]}")
        }

        private fun KeyDef.toJson(): String = buildString {
            append("{")
            append("\"label\":".plus(label.toJson()))
            if (action != null) append(",\"action\":".plus(action.toJson()))
            if (shift != null) append(",\"shift\":".plus(shift.toJson()))
            if (width != 1f) append(",\"width\":".plus(width.toString()))
            append("}")
        }

        private fun String.toJson(): String = buildString {
            append('"')
            for (c in this@toJson) {
                when (c) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\n' -> append("\\n")
                    '\t' -> append("\\t")
                    '\b' -> append("\\b")
                    '\r' -> append("\\r")
                    '\u000C' -> append("\\f")
                    else -> append(c)
                }
            }
            append('"')
        }
    }

    private fun computeHalf(
        rows: List<List<KeyDef>>,
        colStagger: List<Float>,
        startX: Float,
        hw: Float,
        startY: Float,
        out: MutableList<PositionedKey>
    ) {
        for ((rowIdx, row) in rows.withIndex()) {
            if (row.isEmpty()) continue
            val unitW  = (hw - (row.size - 1) * keyGap) / row.size
            val baseY  = startY + rowIdx * (keyHeight + rowGap)

            var x = startX
            for ((colIdx, key) in row.withIndex()) {
                val kw = unitW   // all keys width=1 in this layout
                if (key.label.isNotEmpty()) {
                    val y = baseY + colStagger.getOrElse(colIdx) { 0f } * keyHeight
                    out.add(PositionedKey(key, KeyRect(x, y, x + kw, y + keyHeight)))
                }
                x += kw + keyGap
            }
        }
    }
}
