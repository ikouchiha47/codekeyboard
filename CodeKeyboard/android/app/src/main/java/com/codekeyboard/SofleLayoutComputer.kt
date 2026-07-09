package com.codekeyboard

import android.graphics.RectF

/**
 * Computes pixel-exact key positions for the Sofle Choc v2 split keyboard.
 *
 * Geometry rules (matching keyboard-sofle-layers.html):
 *   - Two halves separated by [halfGapPx]
 *   - Each half gets equal width: (screenWidth - 2*padding - halfGap) / 2
 *   - Column stagger: each key's Y is offset by colStagger[colIdx] * keyHeight
 *       left  columns 0-5: [0.00, 0.25, 0.50, 0.75, 1.00, 1.00]
 *       right columns 0-6: [1.00, 0.75, 0.50, 0.25, 0.00, 0.00, 0.00]
 *   - Total height = numRows*(keyH+rowGap) + maxStagger*keyH + 2*padding
 *
 * This class is the only place in the codebase that knows anything about
 * Sofle geometry. The renderer (NativeKeyboardView) never sees this class.
 */
class SofleLayoutComputer(density: Float) : KeyboardLayoutComputer {

    override val name = "Sofle Choc v2"
    override val layers = SofleKeyData.LAYERS.keys.toList()

    // ── Dimensions (all in px) ────────────────────────────────────────────────

    private val padding   = (6  * density)
    private val keyGap    = (4  * density)
    private val rowGap    = (4  * density)
    private val halfGap   = (20 * density)
    private val keyHeight = (52 * density)
    private val cornerR   = (8  * density)   // unused here, available for renderer hint

    // ── Column stagger (fraction of keyHeight, from the HTML STAG constant) ──

    private val staggerLeft  = listOf(0f, 0.25f, 0.50f, 0.75f, 1.00f, 1.00f)
    private val staggerRight = listOf(1.00f, 0.75f, 0.50f, 0.25f, 0f, 0f, 0f)

    private val maxStagger = 1.00f   // max value in either stagger array
    private val numRows    = 4       // Sofle has 4 rows per half

    // ── Public API ────────────────────────────────────────────────────────────

    override fun heightPx(screenWidthPx: Int): Int {
        return (numRows * (keyHeight + rowGap) + maxStagger * keyHeight + 2 * padding).toInt()
    }

    override fun compute(screenWidthPx: Int, layer: String): List<PositionedKey> {
        val (leftRows, rightRows) = SofleKeyData.LAYERS[layer]
            ?: SofleKeyData.LAYERS["base"]!!

        val halfWidth = (screenWidthPx - 2 * padding - halfGap) / 2f
        val result    = mutableListOf<PositionedKey>()

        // Left half starts at x = padding
        computeHalf(leftRows, staggerLeft, padding, halfWidth, result)

        // Right half starts at x = padding + halfWidth + halfGap
        computeHalf(rightRows, staggerRight, padding + halfWidth + halfGap, halfWidth, result)

        return result
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Place every key in [rows] into [out].
     *
     * @param rows      key definitions, rows[rowIdx][colIdx]
     * @param colStagger vertical offsets per column (fraction of keyHeight)
     * @param startX    left edge of this half in screen px
     * @param halfWidth available width for this half in px
     */
    private fun computeHalf(
        rows: List<List<KeyDef>>,
        colStagger: List<Float>,
        startX: Float,
        halfWidth: Float,
        out: MutableList<PositionedKey>
    ) {
        for ((rowIdx, row) in rows.withIndex()) {
            if (row.isEmpty()) continue

            // Total relative width units in this row (respects variable-width keys)
            val totalUnits = row.sumOf { it.width.toDouble() }.toFloat()
            val totalGaps  = (row.size - 1) * keyGap
            val unitWidth  = (halfWidth - totalGaps) / totalUnits

            // Base Y for this row (before per-column stagger)
            val baseY = padding + rowIdx * (keyHeight + rowGap)

            var x = startX
            for ((colIdx, key) in row.withIndex()) {
                val keyW = key.width * unitWidth
                val staggerOffsetY = (colStagger.getOrElse(colIdx) { 0f }) * keyHeight
                val y = baseY + staggerOffsetY

                // Only add non-empty keys (empty keys advance x but produce no PositionedKey)
                if (key.label.isNotEmpty()) {
                    val rect = RectF(x, y, x + keyW, y + keyHeight)
                    out.add(PositionedKey(key, rect))
                }

                x += keyW + keyGap
            }
        }
    }
}
