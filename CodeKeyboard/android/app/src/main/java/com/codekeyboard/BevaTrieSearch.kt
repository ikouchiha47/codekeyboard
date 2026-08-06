package com.codekeyboard

// ── BevaTrieSearch — Edit Vector DFS (Zhou et al. 2016, BEVA, bitset variant) ─
//
// Inspired by the edit-vector DFS from Zhou et al. 2016. Per-node cost is
// O(k²) independent of query length for k small (k ≤ 2 in practice).
//
// State: evBits[e] is a bitmask of reachable query positions with ≤ e errors
// at the current trie node. Bit p set means "with ≤ e errors we can be sitting
// at query position p" (i.e., we have matched q[0..p-1]).
//
// Using a bitmask instead of a single max-position integer correctly handles
// the case where two paths at the same error budget reach different positions
// (e.g., a deletion path reaching position 0 and a substitution path reaching
// position 1 must both survive independently — max would discard position 0,
// causing a missed match on the next edge).
//
// Transition when descending trie edge with char ch:
//   Match:        q[p] == ch → set bit p+1 in newEv[e]      (no error)
//   Substitution: any p < n  → set bit p+1 in newEv[e+1]    (1 error)
//   Deletion:     skip ch    → set bit p   in newEv[e+1]     (1 error)
//   Insertion:    after above, propagate each position forward at +1 error per step
//
// Prune when newEv is all zeros (no reachable position at any error budget).
// Terminal: editDist = min e where bit n is set in evBits[e].
//
// Words up to 30 chars fit in a 32-bit Int bitmask (bits 0..n, n ≤ 30).

object BevaTrieSearch {

    fun <Node> search(
        adapter: TrieAdapter<Node>,
        word: String,
        threshold: Int,
        maxResults: Int,
    ): List<FuzzyResult> {
        if (threshold <= 0 || word.isEmpty() || maxResults <= 0) return emptyList()
        val q = word.lowercase()
        val n = q.length
        // At root: evBits[e] = bits 0..min(e,n) all set.
        // With e errors and 0 trie chars, we can have skipped up to e query chars.
        val initialEv = IntArray(threshold + 1) { e ->
            val maxPos = minOf(e, n)
            (1 shl (maxPos + 1)) - 1   // bits 0..maxPos
        }
        val results = mutableListOf<FuzzyResult>()
        dfs(adapter, adapter.root, StringBuilder(), initialEv, q, threshold, results, maxResults)
        return results.sortedWith(compareBy({ it.editDistance }, { -it.frequency }))
    }

    private fun <Node> dfs(
        adapter: TrieAdapter<Node>,
        node: Node,
        prefix: StringBuilder,
        ev: IntArray,
        q: String,
        k: Int,
        results: MutableList<FuzzyResult>,
        maxResults: Int,
    ) {
        if (results.size >= maxResults) return

        if (adapter.isTerminal(node) && prefix.isNotEmpty()) {
            val dist = editDist(ev, q.length)
            if (dist <= k) {
                results += FuzzyResult(prefix.toString(), dist, adapter.frequency(node))
            }
        }

        // Prune: if no error budget has any reachable position, stop.
        if (ev[k] == 0) return

        adapter.iterateChildren(node) { ch, child ->
            if (results.size >= maxResults) return@iterateChildren
            val newEv = transition(ev, ch, q, k) ?: return@iterateChildren
            prefix.append(ch)
            dfs(adapter, child, prefix, newEv, q, k, results, maxResults)
            prefix.deleteCharAt(prefix.length - 1)
        }
    }

    // Returns min e such that bit n is set in ev[e].
    // Returns k+1 if no such e exists.
    private fun editDist(ev: IntArray, n: Int): Int {
        val bit = 1 shl n
        for (e in ev.indices) if (ev[e] and bit != 0) return e
        return ev.size
    }

    // Compute new bitmask EV after descending trie edge with char ch.
    // Returns null if all entries are zero (prune the subtree).
    private fun transition(ev: IntArray, ch: Char, q: String, k: Int): IntArray? {
        val n = q.length
        // posMask: bits 0..n are valid (positions 0 = "matched nothing" to n = "matched all")
        val posMask = (1 shl (n + 1)) - 1
        // queryPosMask: bits 0..n-1 valid for matching (positions that still have a query char)
        val queryPosMask = posMask ushr 1   // bits 0..n-1

        // matchMask: bit p set if q[p] == ch (so a match at position p advances to p+1)
        var matchMask = 0
        for (p in 0 until n) if (q[p] == ch) matchMask = matchMask or (1 shl p)

        val nv = IntArray(k + 1)

        for (e in 0..k) {
            val bits = ev[e]
            if (bits == 0) continue

            // Match: positions where q[p] == ch → advance to p+1
            nv[e] = nv[e] or ((bits and matchMask) shl 1)

            if (e < k) {
                // Substitution: any position p < n → advance to p+1, cost 1 error
                nv[e + 1] = nv[e + 1] or ((bits and queryPosMask) shl 1)
                // Deletion: skip the trie char, stay at same position, cost 1 error
                nv[e + 1] = nv[e + 1] or bits
            }
        }

        // Clamp all entries to valid position range
        for (e in 0..k) nv[e] = nv[e] and posMask

        // Insertion propagation: from each reachable position, skip additional
        // query chars at 1 error per char.
        for (e in 0 until k) {
            var spread = nv[e]
            for (skip in 1..(k - e)) {
                spread = (spread shl 1) and posMask
                nv[e + skip] = nv[e + skip] or spread
            }
        }

        return if (nv.any { it != 0 }) nv else null
    }
}
