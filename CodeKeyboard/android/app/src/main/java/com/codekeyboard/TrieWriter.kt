package com.codekeyboard

import java.nio.ByteBuffer
import java.nio.ByteOrder

object TrieWriter {

    private const val MAGIC = 0x54524933.toInt() // "TRI3"

    // Header: magic(4) + nodeCount(4) + totalCommits(4) + decayEpoch(4) = 16 bytes
    private const val HEADER_SIZE = 16

    // v1 node: childrenOffset(4) + childCount(2) + isTerminal(1) + pad(1) + frequency(4) + maxDescendantFreq(4) = 16 bytes
    private const val NODE_SIZE_V1 = 16

    // v2 node: same + lastDecayEpoch(4) = 20 bytes
    private const val NODE_SIZE_V2 = 20

    // ── Serialize (always writes v2) ──────────────────────────────────────────

    fun serialize(trie: UserTrie): ByteArray {
        val order = mutableListOf<UserTrieNode>()
        val index = HashMap<UserTrieNode, Int>()
        val queue = ArrayDeque<UserTrieNode>()
        queue.add(trie.root)
        while (queue.isNotEmpty()) {
            val n = queue.removeFirst()
            index[n] = order.size
            order.add(n)
            n.children.keys.sorted().forEach { ch -> queue.add(n.children[ch]!!) }
        }

        val nodeCount = order.size

        data class ChildEntry(val ch: Char, val nodeIdx: Int)
        val childBlocks = order.map { n ->
            n.children.keys.sorted().map { ch -> ChildEntry(ch, index[n.children[ch]!!]!!) }
        }

        val childrenSectionSize = childBlocks.sumOf { it.size * 6 }
        val totalSize = HEADER_SIZE + nodeCount * NODE_SIZE_V2 + childrenSectionSize

        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        val totalCommits = order.sumOf { it.frequency }
        buf.putInt(MAGIC)
        buf.putInt(nodeCount)
        buf.putInt(totalCommits)
        buf.putInt(trie.decayEpoch)

        val childrenBase = HEADER_SIZE + nodeCount * NODE_SIZE_V2
        val offsets = IntArray(nodeCount)
        var offset = 0
        childBlocks.forEachIndexed { i, block ->
            offsets[i] = offset
            offset += block.size * 6
        }

        order.forEachIndexed { i, n ->
            buf.putInt(offsets[i])
            buf.putShort(n.children.size.toShort())
            buf.put(if (n.isTerminal) 1.toByte() else 0)
            buf.put(0)
            buf.putInt(n.frequency)
            buf.putInt(n.maxDescendantFreq)
            buf.putInt(n.lastDecayEpoch)
        }

        childBlocks.forEach { block ->
            block.forEach { (ch, idx) ->
                buf.putShort(ch.code.toShort())
                buf.putInt(idx)
            }
        }

        return buf.array()
    }

    // ── Deserialize (handles v1 and v2) ───────────────────────────────────────

    fun deserialize(bytes: ByteArray): UserTrie {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        val magic = buf.getInt()
        require(magic == MAGIC) { "Not a TRIE3 file (magic=0x${magic.toString(16)})" }
        val nodeCount = buf.getInt()
        buf.getInt() // totalCommits
        val decayEpoch = buf.getInt()

        // Detect format version by file size.
        val childrenSectionOffset = bytes.size - (bytes.size - HEADER_SIZE - nodeCount * NODE_SIZE_V2)
        val isV2 = (bytes.size - HEADER_SIZE) % (nodeCount) == 0 &&
            run {
                // Check whether node section fits v2 layout: remaining bytes after header must
                // be divisible with v2 node size once children section is removed.
                // Simpler: compare expected file size for v2 vs v1.
                val childrenSectionSize = bytes.size - HEADER_SIZE - nodeCount * NODE_SIZE_V2
                childrenSectionSize >= 0 && (bytes.size - HEADER_SIZE - nodeCount * NODE_SIZE_V1) != childrenSectionSize
            }

        // Reliable version detection: old v1 files have NODE_SIZE_V1 nodes.
        // We infer version from which node size produces a non-negative children section.
        val nodeSize = if (bytes.size >= HEADER_SIZE + nodeCount * NODE_SIZE_V2) NODE_SIZE_V2 else NODE_SIZE_V1
        val childrenBase = HEADER_SIZE + nodeCount * nodeSize

        data class NodeMeta(
            val childrenOffset: Int,
            val childCount: Int,
            val isTerminal: Boolean,
            val frequency: Int,
            val maxDescendantFreq: Int,
            val lastDecayEpoch: Int,
        )

        val metas = Array(nodeCount) {
            val childrenOffset    = buf.getInt()
            val childCount        = buf.getShort().toInt() and 0xFFFF
            val isTerminal        = buf.get().toInt() != 0
            buf.get()             // pad
            val frequency         = buf.getInt()
            val maxDescendantFreq = buf.getInt()
            val lastDecayEpoch    = if (nodeSize == NODE_SIZE_V2) buf.getInt() else 0
            NodeMeta(childrenOffset, childCount, isTerminal, frequency, maxDescendantFreq, lastDecayEpoch)
        }

        val nodes = Array(nodeCount) { UserTrieNode() }
        nodes.forEachIndexed { i, node ->
            val meta = metas[i]
            node.frequency = meta.frequency
            node.maxDescendantFreq = meta.maxDescendantFreq
            node.lastDecayEpoch = meta.lastDecayEpoch
            val childBase = childrenBase + meta.childrenOffset
            repeat(meta.childCount) { j ->
                val pos = childBase + j * 6
                val ch = (bytes[pos].toInt() and 0xFF or ((bytes[pos + 1].toInt() and 0xFF) shl 8)).toChar()
                val childIdx = ByteBuffer.wrap(bytes, pos + 2, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                node.children[ch] = nodes[childIdx]
            }
        }

        val trie = UserTrie()
        trie.root.children.clear()
        trie.root.children.putAll(nodes[0].children)
        trie.root.frequency = nodes[0].frequency
        trie.root.maxDescendantFreq = nodes[0].maxDescendantFreq
        trie.root.lastDecayEpoch = nodes[0].lastDecayEpoch
        trie.decayEpoch = decayEpoch
        return trie
    }
}
