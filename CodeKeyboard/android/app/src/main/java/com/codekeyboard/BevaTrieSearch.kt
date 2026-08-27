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

    // adjacency: optional KeyAdjacency for proximity-weighted substitution cost.
    // Adjacent-key substitutions cost 1 half-step; non-adjacent cost 2 half-steps.
    // Threshold is doubled internally so half-steps fit in the Int error array.
    // With NoAdjacency (default) behaviour is identical to uniform-cost BEVA.
    fun <Node> search(
        adapter: TrieAdapter<Node>,
        word: String,
        threshold: Int,
        maxResults: Int,
        adjacency: KeyAdjacency = NoAdjacency,
    ): List<FuzzyResult> {
        if (threshold <= 0 || word.isEmpty() || maxResults <= 0) return emptyList()
        val q = word.lowercase()
        val n = q.length
        // Scale threshold to half-steps so adjacent subs (cost 0.5) fit as integers.
        val k = threshold * 2
        // Each insertion costs 2 half-steps, so e half-steps allows only e/2 free insertions at root.
        val initialEv = IntArray(k + 1) { e ->
            val maxPos = minOf(e / 2, n)
            (1 shl (maxPos + 1)) - 1
        }
        val results = mutableListOf<FuzzyResult>()
        dfs(adapter, adapter.root, StringBuilder(), initialEv, q, k, adjacency, results, maxResults)
        // Convert half-step distances back to whole-step for FuzzyResult.editDistance.
        return results.sortedWith(compareBy({ it.editDistance }, { -it.frequency }))
    }

    private fun <Node> dfs(
        adapter: TrieAdapter<Node>,
        node: Node,
        prefix: StringBuilder,
        ev: IntArray,
        q: String,
        k: Int,
        adjacency: KeyAdjacency,
        results: MutableList<FuzzyResult>,
        maxResults: Int,
    ) {
        if (results.size >= maxResults) return

        if (adapter.isTerminal(node) && prefix.isNotEmpty()) {
            val halfDist = editDist(ev, q.length)
            if (halfDist <= k) {
                // Round up half-steps to whole edit distance for external consumers.
                val dist = (halfDist + 1) / 2
                results += FuzzyResult(prefix.toString(), dist, adapter.frequency(node))
            }
        }

        if (ev[k] == 0) return

        adapter.iterateChildren(node) { ch, child ->
            if (results.size >= maxResults) return@iterateChildren
            val newEv = transition(ev, ch, q, k, adjacency) ?: return@iterateChildren
            prefix.append(ch)
            dfs(adapter, child, prefix, newEv, q, k, adjacency, results, maxResults)
            prefix.deleteCharAt(prefix.length - 1)
        }
    }

    private fun editDist(ev: IntArray, n: Int): Int {
        val bit = 1 shl n
        for (e in ev.indices) if (ev[e] and bit != 0) return e
        return ev.size
    }

    private fun transition(ev: IntArray, ch: Char, q: String, k: Int, adjacency: KeyAdjacency): IntArray? {
        val n = q.length
        val posMask = (1 shl (n + 1)) - 1
        val queryPosMask = posMask ushr 1

        // Three masks based on adjacency cost (in half-steps):
        //   matchMask    — q[p] == ch             → cost 0
        //   adjMask      — q[p] adjacent to ch    → cost 1 half-step
        //   nonAdjMask   — everything else        → cost 2 half-steps (= 1 whole error)
        var matchMask = 0
        var adjMask = 0
        for (p in 0 until n) {
            val qc = q[p]
            when {
                qc == ch -> matchMask = matchMask or (1 shl p)
                adjacency.substitutionCost(qc, ch) < 1f -> adjMask = adjMask or (1 shl p)
            }
        }
        val nonAdjMask = queryPosMask and matchMask.inv() and adjMask.inv()

        val nv = IntArray(k + 1)

        for (e in 0..k) {
            val bits = ev[e]
            if (bits == 0) continue

            // Exact match: no cost
            nv[e] = nv[e] or ((bits and matchMask) shl 1)

            // Adjacent substitution: 1 half-step
            if (e + 1 <= k) nv[e + 1] = nv[e + 1] or ((bits and adjMask) shl 1)

            // Non-adjacent substitution: 2 half-steps
            if (e + 2 <= k) nv[e + 2] = nv[e + 2] or ((bits and nonAdjMask) shl 1)

            // Deletion: 2 half-steps (skip trie char, stay at same query position)
            if (e + 2 <= k) nv[e + 2] = nv[e + 2] or bits
        }

        for (e in 0..k) nv[e] = nv[e] and posMask

        // Insertion propagation: skip query chars at 2 half-steps per char.
        for (e in 0 until k) {
            var spread = nv[e]
            var remaining = k - e
            var skip = 2
            while (skip <= remaining) {
                spread = (spread shl 1) and posMask
                nv[e + skip] = nv[e + skip] or spread
                skip += 2
            }
        }

        return if (nv.any { it != 0 }) nv else null
    }
}
