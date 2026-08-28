package com.codekeyboard

import kotlin.math.sqrt

/**
 * Keyboard adjacency derived from actual key center positions.
 *
 * Unlike QwertyAdjacency (hardcoded QWERTY neighbours), this computes
 * adjacency geometrically: two keys are neighbours if the Euclidean distance
 * between their screen-space centers is within 1.6× the median key height.
 * That threshold catches horizontal, vertical, and diagonal neighbours on
 * any column-staggered layout (Sofle, Ferris Sweep, etc.) without reaching
 * two keys away.
 *
 * Built once from the computed layout at the known screen width, so the
 * stagger geometry is exact for whichever physical layout is active.
 */
class LayoutAdjacency(positionedKeys: List<PositionedKey>) : KeyAdjacency {

    private val neighbours: Map<Char, Set<Char>>

    init {
        // Single-letter alpha keys only — correction operates on word chars.
        val centers = mutableMapOf<Char, Pair<Float, Float>>()
        for (pk in positionedKeys) {
            val label = pk.key.label
            if (label.length == 1 && label[0].isLetter()) {
                centers[label[0].lowercaseChar()] = pk.rect.centerX to pk.rect.centerY
            }
        }

        val heights = positionedKeys.map { it.rect.height }.sorted()
        val medianH = heights[heights.size / 2]
        val threshold = medianH * 1.6f

        val map = mutableMapOf<Char, MutableSet<Char>>()
        val chars = centers.keys.toList()
        for (a in chars) {
            val (ax, ay) = centers[a]!!
            for (b in chars) {
                if (a == b) continue
                val (bx, by) = centers[b]!!
                val dist = sqrt((ax - bx) * (ax - bx) + (ay - by) * (ay - by))
                if (dist <= threshold) {
                    map.getOrPut(a) { mutableSetOf() }.add(b)
                }
            }
        }
        neighbours = map
    }

    override fun substitutionCost(typed: Char, intended: Char): Float {
        if (typed == intended) return 0f
        return if (neighbours[intended]?.contains(typed) == true) 0.5f else 1.0f
    }
}
