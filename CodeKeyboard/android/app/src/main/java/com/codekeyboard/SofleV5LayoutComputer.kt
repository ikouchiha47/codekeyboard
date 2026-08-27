package com.codekeyboard

/**
 * Provider-aware Sofle V5 geometry. Pixel-identical to SofleLayoutComputer
 * for the QWERTY case; accepts any KeyMap via SofleBaseLayerProvider.
 *
 * SofleLayoutComputer.kt is not modified — it remains the reference implementation.
 */
class SofleV5LayoutComputer(
    internal val density: Float,
    private  val provider: BaseLayerProvider<SofleLayerData> = SofleBaseLayerProvider(),
    private  val keyMap:   KeyMap = QwertyKeyMap,
) : KeyboardLayoutComputer {

    override val name   = "Sofle V5"
    override val layers get() = provider.supportedLayers

    private val layerData: Map<String, SofleLayerData> by lazy { provider.layers(keyMap) }

    // ── Dimensions (px) — identical to SofleLayoutComputer ───────────────────
    internal val padding    = 6f  * density
    internal val keyGap     = 5f  * density
    internal val rowGap     = 5f  * density
    internal val keyHeight  = 48f * density
    internal val topRowKeys = 8

    internal fun halfGap(screenW: Int): Float = screenW * 0.02f

    override fun maxSafeSnapPx(screenWidthPx: Int): Float {
        val halfGapPx    = halfGap(screenWidthPx) / 2f
        val marginPx     = 1f * density
        val geometricMax = halfGapPx - marginPx
        val capPx        = 8f * density
        return minOf(geometricMax, capPx)
    }

    internal val staggerLeft  = listOf(0f, 0.25f, 0.50f, 0.75f, 1.00f)
    internal val staggerRight = listOf(1.00f, 0.75f, 0.50f, 0.25f, 0f)

    private val maxStagger = 1.00f
    private val numRows    = 4

    private fun halfWidth(screenW: Int) = (screenW - 2 * padding - halfGap(screenW)) / 2f
    private fun topKeyWidth(screenW: Int) =
        (screenW - 2 * padding - (topRowKeys - 1) * keyGap) / topRowKeys

    override fun heightPx(screenWidthPx: Int): Int =
        (2 * padding
            + keyHeight + rowGap
            + numRows * (keyHeight + rowGap)
            + maxStagger * keyHeight
        ).toInt()

    override fun compute(screenWidthPx: Int, layer: String): List<PositionedKey> {
        val data = layerData[layer] ?: layerData["base"]!!
        val out  = mutableListOf<PositionedKey>()

        computeTopRow(data.topRow, screenWidthPx, out)

        val hw       = halfWidth(screenWidthPx)
        val mainTopY = padding + keyHeight + rowGap

        computeHalf(data.left,  staggerLeft,                          padding,              hw, mainTopY, out)
        computeHalf(data.right, staggerRight, padding + hw + halfGap(screenWidthPx), hw, mainTopY, out)

        return out
    }

    override fun exportLayout(): String = buildString {
        append("""{"layout":"sofle-v5","stagger":{"left":[""")
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
}
