package com.codekeyboard

/**
 * SymSpell delete-variant index (ADR-013).
 *
 * Pre-indexes every word in the vocabulary under all of its delete-variants up
 * to [maxDist]. At query time, we generate the delete-variants of the typed
 * input and look each one up — O(1) per variant — instead of comparing the
 * input against every dictionary word.
 *
 * Why delete-only is enough: any combination of insert+substitute in the query
 * is equivalent to *deletes* from the dictionary side. So "srwach" (a
 * multi-key slide smearing extra characters into "search") is reachable by
 * deleting 'r' and 'w' → "sach", which matches a delete-variant of "search".
 * This catches fat-finger slide errors that plain edit-distance-2 misses
 * because the query has more than 2 wrong characters.
 */
class SymSpellIndex private constructor(
    private val index: Map<String, Set<String>>,
) {

    /** All dictionary words reachable from this delete-variant. */
    fun lookup(variant: String): Set<String> = index[variant] ?: emptySet()

    /** Total distinct delete-variant keys (for sizing/memory instrumentation). */
    val size: Int get() = index.size

    companion object {

        /** Builds the index over [vocab] at delete-distance [maxDist]. */
        fun build(vocab: Set<String>, maxDist: Int = 2): SymSpellIndex {
            val map = HashMap<String, MutableSet<String>>()
            for (word in vocab) {
                // Exact word is reachable at dist 0.
                map.getOrPut(word) { mutableSetOf() }.add(word)
                // All delete-variants of this word map back to it.
                for (variant in generateDeletes(word, maxDist)) {
                    map.getOrPut(variant) { mutableSetOf() }.add(word)
                }
            }
            return SymSpellIndex(map)
        }

        /**
         * All distinct strings reachable by deleting 1..[maxDist] characters
         * from [word], in any order (deduplicated). Includes the word itself
         * only if called with it — callers add the exact word explicitly.
         */
        fun generateDeletes(word: String, maxDist: Int): Set<String> {
            val result = mutableSetOf<String>()
            if (maxDist <= 0) return result
            val queue = ArrayDeque<Pair<String, Int>>()
            queue.add(word to 0)

            while (queue.isNotEmpty()) {
                val (current, dist) = queue.removeFirst()
                if (dist >= maxDist) continue
                for (i in current.indices) {
                    val deleted = current.removeRange(i, i + 1)
                    if (result.add(deleted)) {
                        queue.add(deleted to dist + 1)
                    }
                }
            }
            return result
        }
    }
}