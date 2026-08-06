package com.codekeyboard

data class FuzzyResult(val word: String, val editDistance: Int, val frequency: Int)

// ── TrieAdapter — DI seam between FuzzyTrieSearch and concrete trie types ────

interface TrieAdapter<Node> {
    val root: Node
    fun isTerminal(node: Node): Boolean
    fun frequency(node: Node): Int
    fun iterateChildren(node: Node, block: (Char, Node) -> Unit)
}

class UserTrieAdapter(trie: UserTrie) : TrieAdapter<UserTrieNode> {
    override val root: UserTrieNode = trie.root
    override fun isTerminal(node: UserTrieNode) = node.isTerminal
    override fun frequency(node: UserTrieNode) = node.frequency
    override fun iterateChildren(node: UserTrieNode, block: (Char, UserTrieNode) -> Unit) =
        node.children.forEach { (ch, child) -> block(ch, child) }
}

class BaseTrieAdapter(private val trie: Trie) : TrieAdapter<Int> {
    override val root: Int = trie.rootIdx
    override fun isTerminal(node: Int) = trie.isTerminal(node)
    override fun frequency(node: Int) = trie.nodeFrequency(node)
    override fun iterateChildren(node: Int, block: (Char, Int) -> Unit) =
        trie.iterateChildren(node, block)
}

// ── Threshold — edit distance budget by query length ─────────────────────────

object FuzzyThreshold {
    fun forLength(len: Int): Int = when {
        len <= 3 -> 0
        len == 4 -> 1
        else     -> 2
    }
}

// ── FuzzyTrieSearch — Levenshtein DFS with DP row per node (Hanov 2011) ──────
//
// At each trie node, one DP row is computed from the parent row and the edge
// character. Pruning: if min(currentRow) > threshold, no descendant can be
// within edit distance — prune the entire subtree.
//
// Results are sorted by (editDistance ASC, frequency DESC).

object FuzzyTrieSearch {

    fun <Node> search(
        adapter: TrieAdapter<Node>,
        word: String,
        threshold: Int,
        maxResults: Int,
    ): List<FuzzyResult> {
        if (threshold <= 0 || word.isEmpty() || maxResults <= 0) return emptyList()
        val lower = word.lowercase()
        val results = mutableListOf<FuzzyResult>()
        val initialRow = IntArray(lower.length + 1) { it }
        dfs(adapter, adapter.root, StringBuilder(), initialRow, lower, threshold, results, maxResults)
        return results.sortedWith(compareBy({ it.editDistance }, { -it.frequency }))
    }

    private fun <Node> dfs(
        adapter: TrieAdapter<Node>,
        node: Node,
        prefix: StringBuilder,
        prevRow: IntArray,
        word: String,
        threshold: Int,
        results: MutableList<FuzzyResult>,
        maxResults: Int,
    ) {
        if (results.size >= maxResults) return

        if (adapter.isTerminal(node) && prefix.isNotEmpty()) {
            val dist = prevRow[word.length]
            if (dist <= threshold) {
                results += FuzzyResult(prefix.toString(), dist, adapter.frequency(node))
            }
        }

        // Prune: min of prevRow is a lower bound on edit distance for any
        // extension of the current prefix. If already over budget, stop.
        if (prevRow.min() > threshold) return

        adapter.iterateChildren(node) { ch, child ->
            if (results.size >= maxResults) return@iterateChildren
            val row = IntArray(word.length + 1)
            row[0] = prevRow[0] + 1
            for (j in 1..word.length) {
                val cost = if (word[j - 1] == ch) 0 else 1
                row[j] = minOf(row[j - 1] + 1, prevRow[j] + 1, prevRow[j - 1] + cost)
            }
            prefix.append(ch)
            dfs(adapter, child, prefix, row, word, threshold, results, maxResults)
            prefix.deleteCharAt(prefix.length - 1)
        }
    }
}
