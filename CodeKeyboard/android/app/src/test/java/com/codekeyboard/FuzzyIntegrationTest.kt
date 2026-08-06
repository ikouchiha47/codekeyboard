package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

// Loads en.trie from assets/ on disk (no Android context needed in unit tests).
// Run with: ./gradlew integrationTest
@IntegrationTest
class FuzzyIntegrationTest {

    // Locate en.trie relative to the project root.
    private val trieFile: File = run {
        val candidates = listOf(
            File("src/main/assets/en.trie"),             // workingDir = app/ (default)
            File("app/src/main/assets/en.trie"),         // workingDir = android/
            File("android/app/src/main/assets/en.trie"), // workingDir = project root
        )
        candidates.firstOrNull { it.exists() }
            ?: error("en.trie not found. Tried: ${candidates.map { it.absolutePath }}")
    }

    private val trie: BaseTrie by lazy { BaseTrie.fromBytes(trieFile.readBytes()) }
    private val adapter: TrieAdapter<Int> by lazy { BaseTrie.adapter(trie) }

    // ── Correctness ────────────────────────────────────────────────────────────

    @Test fun `raiming finds raining in top 3`() {
        val results = BevaTrieSearch.search(adapter, "raiming", 2, Int.MAX_VALUE)
            .sortedWith(compareBy({ it.editDistance }, { -commonPrefixLength("raiming", it.word) }, { -it.frequency }))
        println("raiming top-10: ${results.take(10).map { "${it.word}(d=${it.editDistance})" }}")
        val top3 = results.take(3).map { it.word }
        assertTrue("raining should be in top 3 (got: $top3)", "raining" in top3)
        assertEquals("top result should be edit distance 1", 1, results.first().editDistance)
    }

    @Test fun `raining ranks above aiming for raiming`() {
        val results = BevaTrieSearch.search(adapter, "raiming", 2, Int.MAX_VALUE)
            .sortedWith(compareBy({ it.editDistance }, { -commonPrefixLength("raiming", it.word) }, { -it.frequency }))
        val words = results.map { it.word }
        val idxRaining = words.indexOf("raining")
        val idxAiming  = words.indexOf("aiming")
        assertTrue("raining not found", idxRaining >= 0)
        assertTrue("aiming not found",  idxAiming  >= 0)
        assertTrue("raining (dist=1, prefix=ra) must rank above aiming (dist=1, prefix='')",
            idxRaining < idxAiming)
    }

    @Test fun `timing ranks below raining for raiming`() {
        val results = BevaTrieSearch.search(adapter, "raiming", 2, Int.MAX_VALUE)
            .sortedWith(compareBy({ it.editDistance }, { -commonPrefixLength("raiming", it.word) }, { -it.frequency }))
        val words = results.map { it.word }
        val idxRaining = words.indexOf("raining")
        val idxTiming  = words.indexOf("timing")
        assertTrue("raining not found", idxRaining >= 0)
        // timing is edit distance 2 from raiming; must never appear above a distance-1 result
        if (idxTiming >= 0) {
            assertTrue("timing (dist=2) must rank below raining (dist=1)", idxRaining < idxTiming)
        }
    }

    @Test fun `receive corrects recieve`() {
        val results = BevaTrieSearch.search(adapter, "recieve", 2, Int.MAX_VALUE)
            .sortedWith(compareBy({ it.editDistance }, { -commonPrefixLength("recieve", it.word) }, { -it.frequency }))
        println("recieve top-10: ${results.take(10).map { "${it.word}(d=${it.editDistance})" }}")
        // "recieve" → "receive" is a transposition (2 edits). Verify it's found within threshold.
        assertTrue("should find 'receive' within threshold", results.any { it.word == "receive" })
        // All top-5 results must be distance 1 or 2 — no garbage
        results.take(5).forEach { r ->
            assertTrue("result '${r.word}' edit dist ${r.editDistance} exceeds threshold", r.editDistance <= 2)
        }
    }

    @Test fun `occurred corrects occured`() {
        val results = BevaTrieSearch.search(adapter, "occured", 2, Int.MAX_VALUE)
            .sortedWith(compareBy({ it.editDistance }, { -commonPrefixLength("occured", it.word) }, { -it.frequency }))
        println("occured top-5: ${results.take(5).map { "${it.word}(d=${it.editDistance})" }}")
        assertTrue("should find 'occurred'", results.any { it.word == "occurred" })
    }

    // ── Performance ────────────────────────────────────────────────────────────

    @Test fun `benchmark raiming k=2 no cap`() {
        val results = measure("raiming k=2 no-cap") {
            BevaTrieSearch.search(adapter, "raiming", 2, Int.MAX_VALUE)
        }
        println("  result count: ${results.size}")
        println("  top-5 sorted: ${results.sortedWith(compareBy({it.editDistance},{-commonPrefixLength("raiming",it.word)})).take(5).map{"${it.word}(d=${it.editDistance})"}}")
    }

    @Test fun `benchmark helo k=1 no cap`() {
        measure("helo k=1 no-cap") {
            BevaTrieSearch.search(adapter, "helo", 1, Int.MAX_VALUE)
        }
    }

    @Test fun `benchmark recieve k=2 no cap`() {
        measure("recieve k=2 no-cap") {
            BevaTrieSearch.search(adapter, "recieve", 2, Int.MAX_VALUE)
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun <T> measure(label: String, warmup: Int = 3, runs: Int = 30, block: () -> T): T {
        var last: T? = null
        repeat(warmup) { last = block() }
        val times = LongArray(runs) { i ->
            val t = System.nanoTime(); last = block(); System.nanoTime() - t
        }
        times.sort()
        val p50 = times[runs / 2] / 1_000
        val p99 = times[(runs * 0.99).toInt().coerceAtMost(runs - 1)] / 1_000
        println("%-40s  p50=%5dµs  p99=%5dµs".format(label, p50, p99))
        return last!!
    }
}

// ── Minimal Trie reader (no Android context) ─────────────────────────────────

class BaseTrie private constructor(private val buf: ByteBuffer, private val nodeSize: Int) {
    private val nodeCount: Int = buf.getInt(8)
    private val childrenBase: Int = 12 + nodeCount * nodeSize

    private fun flags(idx: Int)    = buf.get(12 + idx * nodeSize + 1).toInt() and 0xFF
    private fun blockOff(idx: Int) = buf.getInt(12 + idx * nodeSize + 2)
    fun frequency(idx: Int)        = if (nodeSize == 12) buf.getInt(12 + idx * nodeSize + 6) else 0

    fun isTerminal(idx: Int) = (flags(idx) and 1) != 0
    fun iterChildren(idx: Int, block: (Char, Int) -> Unit) {
        if (flags(idx) and 2 == 0) return
        val off = childrenBase + blockOff(idx)
        val cnt = buf.get(off).toInt() and 0xFF
        for (i in 0 until cnt) {
            val base = off + 1 + i * 4
            val ch = (buf.get(base).toInt() and 0xFF).toChar()
            val cidx = (buf.get(base+1).toInt() and 0xFF) or
                       ((buf.get(base+2).toInt() and 0xFF) shl 8) or
                       ((buf.get(base+3).toInt() and 0xFF) shl 16)
            block(ch, cidx)
        }
    }

    companion object {
        fun fromBytes(bytes: ByteArray): BaseTrie {
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val magic4 = buildString { repeat(4) { append((buf.get(it).toInt() and 0xFF).toChar()) } }
            val nodeSize = if (magic4 == "TRIF") 12 else 8
            if (magic4 != "TRIF") {
                val magic5 = magic4 + (buf.get(4).toInt() and 0xFF).toChar()
                require(magic5 == "TRIE2") { "bad magic: $magic5" }
            }
            return BaseTrie(buf, nodeSize)
        }

        fun adapter(trie: BaseTrie): TrieAdapter<Int> = object : TrieAdapter<Int> {
            override val root: Int = 0
            override fun isTerminal(node: Int) = trie.isTerminal(node)
            override fun frequency(node: Int) = trie.frequency(node)
            override fun iterateChildren(node: Int, block: (Char, Int) -> Unit) =
                trie.iterChildren(node, block)
        }
    }
}
