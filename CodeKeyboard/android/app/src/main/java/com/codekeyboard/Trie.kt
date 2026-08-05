package com.codekeyboard

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Trie private constructor(private val buf: ByteBuffer) {

    companion object {
        private const val MAGIC = "TRIE2"
        private const val HEADER_SIZE = 12
        private const val NODE_SIZE = 8

        fun load(context: Context): Trie {
            val bytes = context.assets.open("en.trie").use { it.readBytes() }
            return fromBytes(bytes)
        }

        fun fromBytes(bytes: ByteArray): Trie {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic = buildString { repeat(5) { append((buf.get().toInt() and 0xFF).toChar()) } }
            require(magic == MAGIC) { "Invalid trie magic: $magic" }
            return Trie(buf)
        }
    }

    // Read nodeCount from bytes 8-11 (uint32 LE).
    private val nodeCount: Int = buf.getInt(8)

    // Children section starts immediately after the node array.
    private val childrenBase: Int = HEADER_SIZE + nodeCount * NODE_SIZE

    private fun nodeFlags(idx: Int): Int =
        buf.get(HEADER_SIZE + idx * NODE_SIZE + 1).toInt() and 0xFF

    private fun childrenBlockOffset(idx: Int): Int =
        buf.getInt(HEADER_SIZE + idx * NODE_SIZE + 2)

    // Linear scan over the children block for nodeIdx looking for char c.
    // Returns nodeIndex of matching child, or -1 if not found.
    private fun findChild(nodeIdx: Int, c: Char): Int {
        val flags = nodeFlags(nodeIdx)
        if (flags and 2 == 0) return -1
        val blockOff = childrenBase + childrenBlockOffset(nodeIdx)
        val childCount = buf.get(blockOff).toInt() and 0xFF
        val target = c.code
        for (i in 0 until childCount) {
            val base = blockOff + 1 + i * 3
            if (buf.get(base).toInt() and 0xFF == target) {
                return buf.getShort(base + 1).toInt() and 0xFFFF
            }
        }
        return -1
    }

    // Walk prefix character by character, return the nodeIndex at end of prefix
    // or -1 if any character along the path is not found.
    private fun walk(prefix: String): Int {
        var idx = 0
        for (ch in prefix) {
            idx = findChild(idx, ch)
            if (idx < 0) return -1
        }
        return idx
    }

    // Returns true if word exists as a complete word in the trie.
    fun has(word: String): Boolean {
        if (word.isEmpty()) return false
        val idx = walk(word.lowercase())
        if (idx < 0) return false
        return (nodeFlags(idx) and 1) != 0
    }

    // Returns up to max completions for the given prefix in DFS order.
    fun suggest(prefix: String, max: Int = 3): List<String> {
        if (prefix.isEmpty() || max <= 0) return emptyList()
        val lower = prefix.lowercase()
        val startIdx = walk(lower)
        if (startIdx < 0) return emptyList()

        val results = mutableListOf<String>()
        // Stack holds (nodeIndex, suffix-so-far).
        // Using ArrayDeque as a stack (addLast/removeLast) for allocation efficiency.
        val stack = ArrayDeque<Pair<Int, String>>()
        stack.addLast(startIdx to "")

        while (stack.isNotEmpty() && results.size < max) {
            val (nidx, suffix) = stack.removeLast()
            val flags = nodeFlags(nidx)
            if (flags and 1 != 0 && suffix.isNotEmpty()) {
                results.add(lower + suffix)
            }
            if (flags and 2 != 0) {
                val blockOff = childrenBase + childrenBlockOffset(nidx)
                val childCount = buf.get(blockOff).toInt() and 0xFF
                // Push in reverse order so first child is processed first (LIFO).
                for (i in childCount - 1 downTo 0) {
                    val base = blockOff + 1 + i * 3
                    val ch = (buf.get(base).toInt() and 0xFF).toChar()
                    val cidx = buf.getShort(base + 1).toInt() and 0xFFFF
                    stack.addLast(cidx to (suffix + ch))
                }
            }
        }
        return results
    }
}
