package com.codekeyboard

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Trie private constructor(private val buf: ByteBuffer) : PrefixDictionary {

    companion object {
        private const val MAGIC_TRIF = "TRIF"
        private const val MAGIC_TRIE2 = "TRIE2"
        private const val HEADER_SIZE = 12
        private const val NODE_SIZE_TRIF  = 12  // char(1)+flags(1)+childOff(4)+freq(4)+pad(2)
        private const val NODE_SIZE_TRIE2 =  8  // char(1)+flags(1)+childOff(4)+pad(2)

        fun load(context: Context): Trie {
            val bytes = context.assets.open("en.trie").use { it.readBytes() }
            return fromBytes(bytes)
        }

        // Android-free loader for JVM unit tests — reads the same asset file
        // straight off disk instead of through an Android Context.
        fun load(file: File): Trie = fromBytes(file.readBytes())

        fun fromBytes(bytes: ByteArray): Trie {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            // Read first 4 bytes as magic (TRIF) or 5 bytes (TRIE2)
            val magic4 = buildString { repeat(4) { append((buf.get().toInt() and 0xFF).toChar()) } }
            if (magic4 != MAGIC_TRIF) {
                // Try TRIE2: re-read all 5 bytes
                val fifth = (buf.get().toInt() and 0xFF).toChar()
                val magic5 = magic4 + fifth
                require(magic5 == MAGIC_TRIE2) {
                    "Unknown trie magic: '$magic4' — expected TRIF or TRIE2"
                }
            }
            return Trie(buf)
        }
    }

    // Detect format from node size: TRIF has 12-byte nodes, TRIE2 has 8-byte nodes.
    // We infer by checking whether the magic was TRIF (4 bytes read) or TRIE2 (5 bytes).
    // Simplest: re-derive from the raw bytes.
    private val nodeSize: Int = run {
        val magic4 = buildString { repeat(4) { append((buf.get(it).toInt() and 0xFF).toChar()) } }
        if (magic4 == MAGIC_TRIF) NODE_SIZE_TRIF else NODE_SIZE_TRIE2
    }

    private val nodeCount: Int = buf.getInt(8)
    private val childrenBase: Int = HEADER_SIZE + nodeCount * nodeSize

    private fun nodeFlags(idx: Int): Int =
        buf.get(HEADER_SIZE + idx * nodeSize + 1).toInt() and 0xFF

    private fun childrenBlockOffset(idx: Int): Int =
        buf.getInt(HEADER_SIZE + idx * nodeSize + 2)

    internal fun nodeFrequency(idx: Int): Int =
        if (nodeSize == NODE_SIZE_TRIF) buf.getInt(HEADER_SIZE + idx * nodeSize + 6) else 0

    private fun readChildIdx(base: Int): Int =
        (buf.get(base).toInt() and 0xFF) or
        ((buf.get(base + 1).toInt() and 0xFF) shl 8) or
        ((buf.get(base + 2).toInt() and 0xFF) shl 16)

    private fun findChild(nodeIdx: Int, c: Char): Int {
        val flags = nodeFlags(nodeIdx)
        if (flags and 2 == 0) return -1
        val blockOff = childrenBase + childrenBlockOffset(nodeIdx)
        val childCount = buf.get(blockOff).toInt() and 0xFF
        val target = c.code
        for (i in 0 until childCount) {
            val base = blockOff + 1 + i * 4
            if (buf.get(base).toInt() and 0xFF == target) {
                return readChildIdx(base + 1)
            }
        }
        return -1
    }

    private fun walk(prefix: String): Int {
        var idx = 0
        for (ch in prefix) {
            idx = findChild(idx, ch)
            if (idx < 0) return -1
        }
        return idx
    }

    // ── Internal API for FuzzyTrieSearch ─────────────────────────────────────

    internal val rootIdx: Int = 0

    internal fun isTerminal(nodeIdx: Int): Boolean =
        (nodeFlags(nodeIdx) and 1) != 0

    internal fun iterateChildren(nodeIdx: Int, block: (Char, Int) -> Unit) {
        val flags = nodeFlags(nodeIdx)
        if (flags and 2 == 0) return
        val blockOff = childrenBase + childrenBlockOffset(nodeIdx)
        val childCount = buf.get(blockOff).toInt() and 0xFF
        for (i in 0 until childCount) {
            val base = blockOff + 1 + i * 4
            val ch = (buf.get(base).toInt() and 0xFF).toChar()
            val cidx = readChildIdx(base + 1)
            block(ch, cidx)
        }
    }

    override fun has(word: String): Boolean {
        if (word.isEmpty()) return false
        val idx = walk(word.lowercase())
        if (idx < 0) return false
        return (nodeFlags(idx) and 1) != 0
    }

    override fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty() || max <= 0) return emptyList()
        val lower = prefix.lowercase()
        val startIdx = walk(lower)
        if (startIdx < 0) return emptyList()

        val results = mutableListOf<Pair<String, Int>>() // word, frequency
        val stack = ArrayDeque<Pair<Int, String>>()
        stack.addLast(startIdx to "")

        while (stack.isNotEmpty()) {
            val (nidx, suffix) = stack.removeLast()
            val flags = nodeFlags(nidx)
            if (flags and 1 != 0 && suffix.isNotEmpty()) {
                results.add((lower + suffix) to nodeFrequency(nidx))
            }
            if (flags and 2 != 0) {
                val blockOff = childrenBase + childrenBlockOffset(nidx)
                val childCount = buf.get(blockOff).toInt() and 0xFF
                for (i in childCount - 1 downTo 0) {
                    val base = blockOff + 1 + i * 4
                    val ch = (buf.get(base).toInt() and 0xFF).toChar()
                    val cidx = readChildIdx(base + 1)
                    stack.addLast(cidx to (suffix + ch))
                }
            }
        }

        return results
            .sortedByDescending { it.second }
            .take(max)
            .map { it.first }
    }
}
