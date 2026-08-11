package com.codekeyboard

/**
 * Geometry for the Ferris Sweep variant with top row retained.
 *
 * Structure:
 *   Top row   8 keys, full-width, no stagger (identical to Sofle)
 *   Main      3 rows x 5 cols per side, column-staggered
 *   Thumb     2 large keys per side, centred under each half
 *
 * Stagger (fraction of keyHeight):
 *   Left  cols 0-4: [0.00, 0.25, 0.50, 0.50, 0.75]  — flatter pinky than Sofle
 *   Right cols 0-4: [0.75, 0.50, 0.50, 0.25, 0.00]
 */
class FerrisSweepLayoutComputer(
    internal val density: Float,
    private  val provider: BaseLayerProvider<FerrisSweepLayerData> = FerrisSweepBaseLayerProvider(),
    private  val keyMap:   KeyMap = QwertyKeyMap,
) : KeyboardLayoutComputer {

    override val name   = "Ferris Sweep"
    override val layers get() = provider.supportedLayers

    private val layerData: Map<String, FerrisSweepLayerData> by lazy { provider.layers(keyMap) }

    // ── Dimensions ────────────────────────────────────────────────────────────
    internal val padding     = 6f  * density
    internal val keyGap      = 5f  * density
    internal val rowGap      = 5f  * density
    internal val keyHeight   = 48f * density
    internal val thumbHeight = 52f * density   // slightly taller thumb keys
    internal val thumbGap    = 8f  * density   // gap between top row and thumb row bottom
    internal val topRowKeys  = 8

    internal fun halfGap(screenW: Int): Float = screenW * 0.02f

    override fun maxSafeSnapPx(screenW: Int): Float {
        val halfGapPx    = halfGap(screenW) / 2f
        val marginPx     = 1f * density
        val geometricMax = halfGapPx - marginPx
        val capPx        = 8f * density
        return minOf(geometricMax, capPx)
    }

    internal val staggerLeft  = listOf(0f, 0.25f, 0.50f, 0.50f, 0.75f)
    internal val staggerRight = listOf(0.75f, 0.50f, 0.50f, 0.25f, 0f)

    private val maxStagger = 0.75f
    private val numRows    = 3

    private fun halfWidth(screenW: Int) = (screenW - 2 * padding - halfGap(screenW)) / 2f
    private fun topKeyWidth(screenW: Int) =
        (screenW - 2 * padding - (topRowKeys - 1) * keyGap) / topRowKeys

    override fun heightPx(screenWidthPx: Int): Int =
        (2 * padding
            + keyHeight + rowGap                         // top row
            + numRows * (keyHeight + rowGap)             // main rows
            + maxStagger * keyHeight                     // stagger overhang
            + rowGap                                     // gap before thumb
            + thumbHeight + padding                      // thumb row
        ).toInt()

    override fun compute(screenWidthPx: Int, layer: String): List<PositionedKey> {
        val data = layerData[layer] ?: layerData["base"]!!
        val out  = mutableListOf<PositionedKey>()

        computeTopRow(data.topRow, screenWidthPx, out)

        val hw       = halfWidth(screenWidthPx)
        val mainTopY = padding + keyHeight + rowGap

        computeHalf(data.left,  staggerLeft,                          padding,              hw, mainTopY, out)
        computeHalf(data.right, staggerRight, padding + hw + halfGap(screenWidthPx), hw, mainTopY, out)

        // Thumb row sits below the tallest staggered column
        val thumbY = mainTopY + numRows * (keyHeight + rowGap) + maxStagger * keyHeight + rowGap
        computeThumb(data.thumbL, padding,              hw, thumbY, out)
        computeThumb(data.thumbR, padding + hw + halfGap(screenWidthPx), hw, thumbY, out)

        return out
    }

    override fun exportLayout(): String = buildString {
        append("""{"layout":"ferris-sweep","stagger":{"left":[""")
        append(staggerLeft.joinToString(","))
        append("""],"right":[""")
        append(staggerRight.joinToString(","))
        append("""]},"keyMap":"${keyMap.id}","layers":{""")
        layerData.entries.joinTo(this, ",") { (name, _) -> "\"$name\":{}" }
        append("}}")
    }

    private fun computeTopRow(keys: List<KeyDef>, screenW: Int, out: MutableList<PositionedKey>) {
        val kw = topKeyWidth(screenW)
        val y  = padding
        keys.forEachIndexed { i, key ->
            if (key.label.isEmpty()) return@forEachIndexed
            val x = padding + i * (kw + keyGap)
            out.add(PositionedKey(key, KeyRect(x, y, x + kw, y + keyHeight)))
        }
    }

    private fun computeHalf(
        rows: List<List<KeyDef>>,
        colStagger: List<Float>,
        startX: Float,
        hw: Float,
        startY: Float,
        out: MutableList<PositionedKey>,
    ) {
        for ((rowIdx, row) in rows.withIndex()) {
            if (row.isEmpty()) continue
            val unitW = (hw - (row.size - 1) * keyGap) / row.size
            val baseY = startY + rowIdx * (keyHeight + rowGap)
            var x = startX
            for ((colIdx, key) in row.withIndex()) {
                if (key.label.isNotEmpty()) {
                    val y = baseY + colStagger.getOrElse(colIdx) { 0f } * keyHeight
                    out.add(PositionedKey(key, KeyRect(x, y, x + unitW, y + keyHeight)))
                }
                x += unitW + keyGap
            }
        }
    }

    private fun computeThumb(
        keys: List<KeyDef>,
        startX: Float,
        hw: Float,
        y: Float,
        out: MutableList<PositionedKey>,
    ) {
        if (keys.isEmpty()) return
        val n     = keys.size
        val unitW = (hw - (n - 1) * keyGap) / n
        // Centre the thumb cluster under the half
        val totalW = n * unitW + (n - 1) * keyGap
        var x = startX + (hw - totalW) / 2f
        for (key in keys) {
            if (key.label.isNotEmpty()) {
                out.add(PositionedKey(key, KeyRect(x, y, x + unitW, y + thumbHeight)))
            }
            x += unitW + keyGap
        }
    }
}
