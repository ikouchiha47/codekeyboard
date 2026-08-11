package com.codekeyboard

/**
 * Layer data for the Ferris Sweep variant.
 *
 * Structure (per layer):
 *   topRow  — 8 keys, full-width, no stagger (identical to Sofle)
 *   left    — 3 rows x 5 cols (Sofle rows 0-2; no bottom row)
 *   right   — 3 rows x 5 cols (Sofle rows 0-2; no bottom row)
 *   thumbL  — 2 keys, left thumb cluster
 *   thumbR  — 2 keys, right thumb cluster
 *
 * The thumb cluster replaces the entire 5-key bottom row from Sofle.
 * It is geometrically distinct: two larger keys centred under each half,
 * not column-staggered.
 */
data class FerrisSweepLayerData(
    val topRow: List<KeyDef>,
    val left:   List<List<KeyDef>>,
    val right:  List<List<KeyDef>>,
    val thumbL: List<KeyDef>,
    val thumbR: List<KeyDef>,
)
