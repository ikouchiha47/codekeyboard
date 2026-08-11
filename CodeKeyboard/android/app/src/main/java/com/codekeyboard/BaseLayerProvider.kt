package com.codekeyboard

/**
 * Owns the canonical key definitions for one physical form factor and applies
 * a KeyMap to the alpha block of the base layer.
 *
 * T is the layout-specific layer data struct (SofleLayerData, FerrisSweepLayerData, …).
 * The layout computer is the only consumer of T — callers outside the layout pair
 * only ever see the opaque Map<String, T>.
 */
interface BaseLayerProvider<T> {
    val supportedLayers: List<String>
    fun layers(keyMap: KeyMap): Map<String, T>
}
