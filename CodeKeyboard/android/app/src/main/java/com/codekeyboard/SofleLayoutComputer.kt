package com.codekeyboard

/**
 * Computes pixel-exact key positions for the Sofle Choc v2 split keyboard.
 *
 * Geometry rules (matching keyboard-sofle-layers.html):
 *   - Two halves separated by [halfGap]
 *   - Each half gets equal width: (screenWidth - 2*padding - halfGap) / 2
 *   - Column stagger: each key's Y is offset by colStagger[colIdx] * keyHeight
 *       left  columns 0-5: [0.00, 0.25, 0.50, 0.75, 1.00, 1.00]
 *       right columns 0-6: [1.00, 0.75, 0.50, 0.25, 0.00, 0.00, 0.00]
 *   - Total height = numRows*(keyH+rowGap) + maxStagger*keyH + 2*padding
 *
 * No Android imports — fully testable with pure JUnit.
 */
class SofleLayoutComputer(density: Float) : KeyboardLayoutComputer {

    override val name = "Sofle Choc v2"
    override val layers = SofleKeyData.LAYERS.keys.toList()

    // ── Dimensions (all in px) ────────────────────────────────────────────────
    // keyHeight matches the HTML reference (44 CSS px → 44*density physical px)

    internal val padding   = (6f  * density)
    internal val keyGap    = (5f  * density)
    internal val rowGap    = (5f  * density)
    internal val halfGap   = (24f * density)
    internal val keyHeight = (44f * density)

    // ── Column stagger (fraction of keyHeight, from the HTML STAG constant) ──

    internal val staggerLeft  = listOf(0f, 0.25f, 0.50f, 0.75f, 1.00f, 1.00f)
    internal val staggerRight = listOf(1.00f, 0.75f, 0.50f, 0.25f, 0f, 0f, 0f)

    private val maxStagger = 1.00f
    private val numRows    = 4

    // ── Public API ────────────────────────────────────────────────────────────

    override fun heightPx(screenWidthPx: Int): Int =
        (numRows * (keyHeight + rowGap) + maxStagger * keyHeight + 2 * padding).toInt()

    override fun compute(screenWidthPx: Int, layer: String): List<PositionedKey> {
        val (leftRows, rightRows) = SofleKeyData.LAYERS[layer]
            ?: SofleKeyData.LAYERS["base"]!!

        val halfWidth = (screenWidthPx - 2 * padding - halfGap) / 2f
        val result    = mutableListOf<PositionedKey>()

        computeHalf(leftRows,  staggerLeft,                              padding,                      halfWidth, result)
        computeHalf(rightRows, staggerRight, padding + halfWidth + halfGap, halfWidth, result)

        return result
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun computeHalf(
        rows: List<List<KeyDef>>,
        colStagger: List<Float>,
        startX: Float,
        halfWidth: Float,
        out: MutableList<PositionedKey>
    ) {
        for ((rowIdx, row) in rows.withIndex()) {
            if (row.isEmpty()) continue

            val totalUnits = row.sumOf { it.width.toDouble() }.toFloat()
            val totalGaps  = (row.size - 1) * keyGap
            val unitWidth  = (halfWidth - totalGaps) / totalUnits

            val baseY = padding + rowIdx * (keyHeight + rowGap)

            var x = startX
            for ((colIdx, key) in row.withIndex()) {
                val keyW           = key.width * unitWidth
                val staggerOffsetY = colStagger.getOrElse(colIdx) { 0f } * keyHeight
                val y              = baseY + staggerOffsetY

                if (key.label.isNotEmpty()) {
                    out.add(PositionedKey(key, KeyRect(x, y, x + keyW, y + keyHeight)))
                }

                x += keyW + keyGap
            }
        }
    }
}
