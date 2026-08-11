package com.codekeyboard

/**
 * Reads SofleKeyData.LAYERS as a read-only source and applies a KeyMap to
 * the alpha block of the base layer. All other layers (lower, raise, adj, func)
 * contain no alpha keys and are returned unchanged.
 *
 * SofleKeyData.kt is never modified.
 */
class SofleBaseLayerProvider : BaseLayerProvider<SofleLayerData> {

    override val supportedLayers = SofleKeyData.LAYERS.keys.toList()

    override fun layers(keyMap: KeyMap): Map<String, SofleLayerData> {
        val source = SofleKeyData.LAYERS
        return buildMap {
            source.forEach { (name, data) ->
                put(name, if (name == "base") remap(data, keyMap) else data)
            }
        }
    }

    private fun remap(base: SofleLayerData, km: KeyMap): SofleLayerData {
        return base.copy(
            left  = base.left.mapIndexed  { ri, row -> remapRow(row, ri, km) },
            right = base.right.mapIndexed { ri, row -> remapRow(row, ri, km) },
        )
    }

    // Only remap alpha rows (0-2). Row 3 is the thumb/modifier row — never remapped.
    private fun remapRow(row: List<KeyDef>, rowIndex: Int, km: KeyMap): List<KeyDef> {
        if (rowIndex >= 3) return row
        return row.map { key ->
            if (key.label.length == 1 && key.label[0].isLetter()) {
                key.copy(label = km.map(key.label))
            } else {
                key
            }
        }
    }
}
