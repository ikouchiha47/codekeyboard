package com.codekeyboard

/**
 * Composition root for layout x keymap selection.
 * The only place in the codebase that maps setting strings to concrete computers.
 *
 * Adding a new physical layout: implement KeyboardLayoutComputer + BaseLayerProvider,
 * add one 'when' branch here.
 *
 * Adding a new keymap: implement KeyMap, add to KeyMapRegistry. No changes here.
 */
object LayoutRegistry {

    val LAYOUTS: Map<String, String> = mapOf(
        "sofle"  to "Sofle V5",
        "ferris" to "Ferris Sweep",
    )

    val DEFAULT_LAYOUT = "sofle"

    fun build(layoutId: String, keyMapId: String, density: Float): KeyboardLayoutComputer {
        val keyMap = KeyMapRegistry.get(keyMapId)
        return when (layoutId) {
            "ferris" -> FerrisSweepLayoutComputer(
                density  = density,
                provider = FerrisSweepBaseLayerProvider(),
                keyMap   = keyMap,
            )
            else -> SofleV5LayoutComputer(
                density  = density,
                provider = SofleBaseLayerProvider(),
                keyMap   = keyMap,
            )
        }
    }
}
