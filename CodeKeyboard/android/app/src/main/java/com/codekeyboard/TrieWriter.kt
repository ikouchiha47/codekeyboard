package com.codekeyboard

import java.nio.ByteBuffer
import java.nio.ByteOrder

object TrieWriter {

    private const val MAGIC = 0x54524933.toInt() // "TRI3"
    private const val HEADER_SIZE = 16
    private const val NODE_SIZE = 16

    // ── Serialize ─────────────────────────────────────────────────────────────

    fun serialize(trie: UserTrie): ByteArray {
        // BFS to assign each node a stable index and collect children lists.
        val order = mutableListOf<UserTrieNode>()
        val index = HashMap<UserTrieNode, Int>()
        val queue = ArrayDeque<UserTrieNode>()
        queue.add(trie.root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            index[n] = order.size
            order.add(n)
            // Sort children by char for deterministic output.
            n.children.keys.sorted().forEach { ch -> queue.add(n.children[ch]!!) }
        }

        val nodeCount = order.size

        // Children section: each node's children are a block of (char, childIndex) pairs.
        // We store them as sequential child-index arrays with a parallel char array.
        // Layout: for each node, childCount × (char:2 + nodeIndex:4) = 6 bytes per child.
        // childrenOffset in each node header points into this section (relative to section start).
        data class ChildEntry(val ch: Char, val nodeIdx: Int)
        val childBlocks = order.map { n ->
            n.children.keys.sorted().map { ch -> ChildEntry(ch, index[n.children[ch]!!]!!) }
        }

        val childrenSectionSize = childBlocks.sumOf { it.size * 6 }
        val totalSize = HEADER_SIZE + nodeCount * NODE_SIZE + childrenSectionSize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header
        val totalCommits = order.sumOf { it.frequency }
        buf.putInt(MAGIC)
        buf.putInt(nodeCount)
        buf.putInt(totalCommits)
        buf.putInt(0) // reserved

        // Compute childrenOffset for each node (relative to children section start).
        val childrenBase = HEADER_SIZE + nodeCount * NODE_SIZE
        val offsets = IntArray(nodeCount)
        var offset = 0
        childBlocks.forEachIndexed { i, block ->
            offsets[i] = offset
            offset += block.size * 6
        }

        // Node array
        order.forEachIndexed { i, n ->
            buf.putInt(offsets[i])                         // childrenOffset
            buf.putShort(n.children.size.toShort())        // childCount
            buf.put(if (n.isTerminal) 1.toByte() else 0)   // isTerminal
            buf.put(0)                                     // reserved
            buf.putInt(n.frequency)                        // frequency
            buf.putInt(n.maxDescendantFreq)                // maxDescendantFreq
        }

        // Children section
        childBlocks.forEach { block ->
            block.forEach { (ch, idx) ->
                buf.putShort(ch.code.toShort())
                buf.putInt(idx)
            }
        }

        return buf.array()
    }

    // ── Deserialize ───────────────────────────────────────────────────────────

    fun deserialize(bytes: ByteArray): UserTrie {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.getInt()
        require(magic == MAGIC) { "Not a TRIE3 file (magic=0x${magic.toString(16)})" }
        val nodeCount = buf.getInt()
        buf.getInt() // totalCommits — not used on load
        buf.getInt() // reserved

        val childrenBase = HEADER_SIZE + nodeCount * NODE_SIZE

        // Read node metadata.
        data class NodeMeta(
            val childrenOffset: Int,
            val childCount: Int,
            val isTerminal: Boolean,
            val frequency: Int,
            val maxDescendantFreq: Int,
        )
        val metas = Array(nodeCount) {
            val childrenOffset    = buf.getInt()
            val childCount        = buf.getShort().toInt() and 0xFFFF
            val isTerminal        = buf.get().toInt() != 0
            buf.get()             // reserved
            val frequency         = buf.getInt()
            val maxDescendantFreq = buf.getInt()
            NodeMeta(childrenOffset, childCount, isTerminal, frequency, maxDescendantFreq)
        }

        // Create nodes first, then wire children.
        val nodes = Array(nodeCount) { UserTrieNode() }
        nodes.forEachIndexed { i, node ->
            val meta = metas[i]
            node.frequency = meta.frequency
            node.maxDescendantFreq = meta.maxDescendantFreq
            val childBase = childrenBase + meta.childrenOffset
            repeat(meta.childCount) { j ->
                val pos = childBase + j * 6
                val ch = (bytes[pos].toInt() and 0xFF or ((bytes[pos + 1].toInt() and 0xFF) shl 8)).toChar()
                val childIdx = ByteBuffer.wrap(bytes, pos + 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                node.children[ch] = nodes[childIdx]
            }
        }

        val trie = UserTrie()
        // Replace root's children with the deserialized root's children.
        trie.root.children.clear()
        trie.root.children.putAll(nodes[0].children)
        trie.root.frequency = nodes[0].frequency
        trie.root.maxDescendantFreq = nodes[0].maxDescendantFreq
        return trie
    }
}
