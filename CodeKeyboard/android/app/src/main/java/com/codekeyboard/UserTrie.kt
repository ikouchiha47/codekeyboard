package com.codekeyboard

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class ScoredWord(val word: String, val frequency: Int)

private sealed class TrieEntry { abstract val priority: Int }
private data class NodeEntry(val node: UserTrieNode, val word: String) : TrieEntry() {
    override val priority get() = node.maxDescendantFreq
}
private data class ResultEntry(val scored: ScoredWord) : TrieEntry() {
    override val priority get() = scored.frequency
}

class UserTrie {

    val root = UserTrieNode()
    private var totalNodes = 1

    // ── Insert ────────────────────────────────────────────────────────────────

    fun insert(word: String) {
        if (word.isBlank()) return
        var node = root
        for (ch in word) {
            node = node.children.getOrPut(ch) { UserTrieNode().also { totalNodes++ } }
        }
        node.frequency++
        updateMaxFreq(root, word, 0)
    }

    // Post-order update of maxDescendantFreq along the inserted path.
    private fun updateMaxFreq(node: UserTrieNode, word: String, depth: Int) {
        if (depth < word.length) {
            val child = node.children[word[depth]]!!
            updateMaxFreq(child, word, depth + 1)
        }
        val childMax = node.children.values.maxOfOrNull { it.maxDescendantFreq } ?: 0
        node.maxDescendantFreq = maxOf(node.frequency, childMax)
    }

    // ── Suggest — best-first traversal with subtree pruning ───────────────────
    // PQ ordered by maxDescendantFreq (upper bound on best reachable score).
    // Prune any node whose upper bound <= floor (k-th best score seen so far).
    // Equivalent to A* on a reward-maximisation graph; called "best-first
    // traversal on a Completion Trie" in Hsu & Ottaviano 2013.

    fun suggest(prefix: String, k: Int): List<ScoredWord> {
        if (k <= 0) return emptyList()

        var node = root
        for (ch in prefix) {
            node = node.children[ch] ?: return emptyList()
        }

        val pq = java.util.PriorityQueue<TrieEntry>(compareByDescending { it.priority })
        pq.add(NodeEntry(node, prefix))

        val results = mutableListOf<ScoredWord>()
        var floor = 0

        while (pq.isNotEmpty()) {
            if (pq.peek()!!.priority <= floor) break

            when (val entry = pq.poll()) {
                is ResultEntry -> {
                    results.add(entry.scored)
                    if (results.size == k) {
                        floor = results.minOf { it.frequency }
                    }
                }
                is NodeEntry -> {
                    val (cur, word) = entry
                    // If this node is terminal, queue it as a ResultEntry so it competes
                    // on actual frequency, not on maxDescendantFreq.
                    if (cur.isTerminal && cur.frequency > floor) {
                        pq.add(ResultEntry(ScoredWord(word, cur.frequency)))
                    }
                    cur.children.entries.forEach { (ch, child) ->
                        if (child.maxDescendantFreq > floor) {
                            pq.add(NodeEntry(child, word + ch))
                        }
                    }
                }
            }
        }

        return results
    }

    // ── Exhaustive DFS — ground truth for correctness validation ──────────────

    fun suggestDfs(prefix: String, k: Int): List<ScoredWord> {
        var node = root
        for (ch in prefix) {
            node = node.children[ch] ?: return emptyList()
        }
        val all = mutableListOf<ScoredWord>()
        collectAll(node, prefix, all)
        return all.sortedByDescending { it.frequency }.take(k)
    }

    private fun collectAll(node: UserTrieNode, word: String, out: MutableList<ScoredWord>) {
        if (node.isTerminal) out.add(ScoredWord(word, node.frequency))
        for ((ch, child) in node.children) collectAll(child, word + ch, out)
    }

    // ── Serialization ─────────────────────────────────────────────────────────

    fun save(file: File) {
        val tmp = File(file.parent, file.name + ".tmp")
        tmp.outputStream().use { out ->
            val buf = TrieWriter.serialize(this)
            out.write(buf)
        }
        tmp.renameTo(file)
    }

    companion object {
        fun load(file: File): UserTrie {
            if (!file.exists()) return UserTrie()
            return TrieWriter.deserialize(file.readBytes())
        }
    }
}
