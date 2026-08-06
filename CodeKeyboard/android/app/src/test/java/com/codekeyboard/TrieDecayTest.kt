package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

class TrieDecayTest {

    private fun trie(vararg pairs: Pair<String, Int>): UserTrie {
        val t = UserTrie()
        pairs.forEach { (w, n) -> repeat(n) { t.insert(w) } }
        return t
    }

    // ── Frequency decay ───────────────────────────────────────────────────────

    @Test fun `word inserted once decays to zero after enough epochs`() {
        val t = trie("hello" to 1)
        // 0.9^25 < 0.08 → rounds to 0
        t.applyDecay(factor = 0.9, newEpoch = 25)
        assertTrue("word should no longer be suggested", t.suggest("hello", 5).isEmpty())
    }

    @Test fun `word inserted 10 times survives one decay epoch`() {
        val t = trie("hello" to 10)
        t.applyDecay(factor = 0.9, newEpoch = 1)
        // 10 * 0.9 = 9 → still suggested
        val results = t.suggest("hello", 5)
        assertTrue("word should still be suggested", results.any { it.word == "hello" })
        assertEquals(9, results.first { it.word == "hello" }.frequency)
    }

    @Test fun `decayed word has reduced frequency`() {
        val t = trie("raining" to 100)
        t.applyDecay(factor = 0.9, newEpoch = 1)
        val freq = t.suggest("raining", 1).firstOrNull()?.frequency
        assertNotNull(freq)
        assertTrue("freq should be reduced", freq!! < 100)
        assertEquals(90, freq)
    }

    @Test fun `high-frequency word survives many epochs`() {
        val t = trie("keyboard" to 1000)
        t.applyDecay(factor = 0.9, newEpoch = 10)
        // 1000 * 0.9^10 = ~349
        val results = t.suggest("keyboard", 5)
        assertTrue("high-freq word should survive", results.any { it.word == "keyboard" })
    }

    @Test fun `epoch delta is respected for successive decays`() {
        val t = trie("hello" to 10)
        t.applyDecay(factor = 0.9, newEpoch = 1)  // 10*0.9 = 9
        t.applyDecay(factor = 0.9, newEpoch = 2)  // 9*0.9 = 8
        val freq = t.suggest("hello", 1).firstOrNull()?.frequency
        assertEquals(8, freq)
    }

    @Test fun `decayEpoch increments on each apply`() {
        val t = trie("hello" to 5)
        assertEquals(0, t.decayEpoch)
        t.applyDecay(factor = 0.9, newEpoch = 1)
        assertEquals(1, t.decayEpoch)
        t.applyDecay(factor = 0.9, newEpoch = 2)
        assertEquals(2, t.decayEpoch)
    }

    // ── Compaction ────────────────────────────────────────────────────────────

    @Test fun `dead-frequency word is removed from suggestions after compaction`() {
        val t = trie("hi" to 1)
        t.applyDecay(factor = 0.9, newEpoch = 30)
        // 0.9^30 < 0.05 → freq rounds to 0
        val results = t.suggest("hi", 5)
        assertTrue("dead word should not appear", results.none { it.word == "hi" })
    }

    @Test fun `compaction does not remove surviving words`() {
        val t = trie("hello" to 100, "hell" to 1)
        t.applyDecay(factor = 0.9, newEpoch = 30)
        // "hell" freq: 1 * 0.9^30 → 0 (removed)
        // "hello" freq: 100 * 0.9^30 = ~4 (survives)
        val results = t.suggest("hel", 5)
        assertTrue("high-freq word should survive compaction", results.any { it.word == "hello" })
        assertTrue("zero-freq word should be removed", results.none { it.word == "hell" })
    }

    @Test fun `maxDescendantFreq is recomputed after decay`() {
        val t = trie("apple" to 10)
        t.applyDecay(factor = 0.9, newEpoch = 1)
        // root.maxDescendantFreq should reflect the decayed value
        assertTrue("maxDescendantFreq should be updated", t.root.maxDescendantFreq < 10)
    }

    // ── Hard cap ──────────────────────────────────────────────────────────────

    @Test fun `trie with many terminals is pruned to 5000 after cap exceeded`() {
        val t = UserTrie()
        // Insert 6000 distinct words
        for (i in 0 until 6000) {
            val word = "word${i.toString().padStart(5, '0')}"
            repeat((i % 10) + 1) { t.insert(word) }
        }
        t.applyDecay(factor = 0.9, newEpoch = 1, maxNodes = 50_000)
        // After decay with maxNodes check at 50k, won't prune here — test the explicit threshold
        // Use a small maxNodes to force pruning
        t.applyDecay(factor = 0.9, newEpoch = 2, maxNodes = 100)
        val allWords = mutableListOf<String>()
        fun dfs(node: UserTrieNode, prefix: String) {
            if (node.isTerminal) allWords += prefix
            node.children.forEach { (ch, child) -> dfs(child, prefix + ch) }
        }
        dfs(t.root, "")
        assertTrue("should be pruned to at most 5000", allWords.size <= 5000)
    }

    // ── Round-trip ────────────────────────────────────────────────────────────

    @Test fun `save and load preserves decayed frequencies and epoch`() {
        val t = trie("hello" to 20, "world" to 10)
        t.applyDecay(factor = 0.9, newEpoch = 3)

        val bytes = TrieWriter.serialize(t)
        val loaded = TrieWriter.deserialize(bytes)

        assertEquals("decayEpoch should round-trip", 3, loaded.decayEpoch)

        val helloFreq = loaded.suggest("hello", 1).firstOrNull()?.frequency
        val worldFreq = loaded.suggest("world", 1).firstOrNull()?.frequency

        assertNotNull(helloFreq)
        assertNotNull(worldFreq)
        // 20 * 0.9^3 = 14, 10 * 0.9^3 = 7
        assertEquals(14, helloFreq)
        assertEquals(7, worldFreq)
    }

    @Test fun `lastDecayEpoch is preserved per-node after round-trip`() {
        val t = trie("hello" to 5)
        t.applyDecay(factor = 0.9, newEpoch = 5)
        val bytes = TrieWriter.serialize(t)
        val loaded = TrieWriter.deserialize(bytes)
        // Navigate to the terminal node for "hello"
        var node: UserTrieNode? = loaded.root
        for (ch in "hello") node = node?.children?.get(ch)
        assertNotNull(node)
        assertEquals(5, node!!.lastDecayEpoch)
    }

    // ── Backward compatibility ─────────────────────────────────────────────────

    @Test fun `v1 format file loads without crash and defaults lastDecayEpoch to 0`() {
        // Build a v1-format byte array manually.
        val magic = 0x54524933.toInt()
        val nodeCount = 2 // root + one terminal
        val HEADER_SIZE = 16
        val NODE_SIZE_V1 = 16
        val childrenSize = 1 * 6 // 1 child in root: (char:2 + idx:4)

        val buf = java.nio.ByteBuffer.allocate(HEADER_SIZE + nodeCount * NODE_SIZE_V1 + childrenSize)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)

        // Header
        buf.putInt(magic)
        buf.putInt(nodeCount)
        buf.putInt(5)   // totalCommits
        buf.putInt(0)   // was reserved, now decayEpoch (still reads as 0)

        val childrenBase = HEADER_SIZE + nodeCount * NODE_SIZE_V1

        // Node 0: root, 1 child 'a', not terminal
        buf.putInt(0)           // childrenOffset=0
        buf.putShort(1)         // childCount=1
        buf.put(0)              // isTerminal=false
        buf.put(0)              // pad
        buf.putInt(0)           // frequency=0
        buf.putInt(5)           // maxDescendantFreq=5

        // Node 1: terminal 'a', no children
        buf.putInt(6)           // childrenOffset past the first child entry
        buf.putShort(0)         // childCount=0
        buf.put(1)              // isTerminal=true
        buf.put(0)              // pad
        buf.putInt(5)           // frequency=5
        buf.putInt(5)           // maxDescendantFreq=5

        // Children section: root has child 'a' → node 1
        buf.putShort('a'.code.toShort())
        buf.putInt(1)

        val loaded = TrieWriter.deserialize(buf.array())
        val results = loaded.suggest("a", 5)
        assertTrue("should find 'a'", results.any { it.word == "a" })
        assertEquals("decayEpoch should default to 0", 0, loaded.decayEpoch)

        // Navigate to terminal node and check lastDecayEpoch defaulted to 0
        val node = loaded.root.children['a']
        assertNotNull(node)
        assertEquals(0, node!!.lastDecayEpoch)
    }
}
