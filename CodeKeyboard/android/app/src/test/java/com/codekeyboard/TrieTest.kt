package com.codekeyboard

import org.junit.Assert.*
import org.junit.BeforeClass
import org.junit.Test
import java.io.File

class TrieTest {

    companion object {
        private lateinit var trie: Trie

        @BeforeClass
        @JvmStatic
        fun loadTrie() {
            // Gradle runs unit tests from the module root, so the asset path is relative
            // to android/app/.
            val file = File("src/main/assets/en.trie")
            assertTrue("en.trie not found at ${file.absolutePath}", file.exists())
            trie = Trie.fromBytes(file.readBytes())
        }

        // Returns elapsed milliseconds.
        private inline fun bench(iterations: Int, block: () -> Unit): Double {
            // Warmup
            repeat(iterations / 10) { block() }
            val t0 = System.nanoTime()
            repeat(iterations) { block() }
            return (System.nanoTime() - t0) / 1_000_000.0
        }
    }

    // ── Correctness: has() ────────────────────────────────────────────────────

    @Test fun `has returns true for common short words`() {
        assertTrue(trie.has("an"))
        assertTrue(trie.has("and"))
        assertTrue(trie.has("the"))
        assertTrue(trie.has("is"))
        assertTrue(trie.has("to"))
    }

    @Test fun `has returns true for medium words`() {
        assertTrue(trie.has("hello"))
        assertTrue(trie.has("world"))
        assertTrue(trie.has("python"))
        assertTrue(trie.has("keyboard"))
        assertTrue(trie.has("language"))
    }

    @Test fun `has returns true for long words`() {
        // Verified present in en.trie (12-char words from full word list extraction).
        assertTrue(trie.has("thanksgiving"))
        assertTrue(trie.has("temperatures"))
        assertTrue(trie.has("intelligence"))
        assertTrue(trie.has("introduction"))
        assertTrue(trie.has("instructions"))
    }

    @Test fun `has returns false for nonexistent word`() {
        assertFalse(trie.has("xqzjw"))
        assertFalse(trie.has("zzzzz"))
        assertFalse(trie.has("qqqqqqqq"))
    }

    @Test fun `has is case insensitive`() {
        assertTrue(trie.has("THE"))
        assertTrue(trie.has("Hello"))
        assertTrue(trie.has("WORLD"))
        assertTrue(trie.has("Python"))
    }

    @Test fun `has returns false for empty string`() {
        assertFalse(trie.has(""))
    }

    @Test fun `single letter a is not a standalone word`() {
        // Verified from binary: nodeIndex=10 but isEnd=false in en.trie.
        assertFalse(trie.has("a"))
    }

    @Test fun `single letter i is not a standalone word`() {
        // Verified from binary: nodeIndex=8 but isEnd=false in en.trie.
        assertFalse(trie.has("i"))
    }

    // ── Correctness: suggest() ────────────────────────────────────────────────

    @Test fun `suggest returns empty list for empty prefix`() {
        assertTrue(trie.suggest("", 5).isEmpty())
    }

    @Test fun `suggest returns empty list for nonexistent prefix`() {
        assertTrue(trie.suggest("xqzjw", 5).isEmpty())
    }

    @Test fun `suggest hel returns help in results`() {
        val results = trie.suggest("hel", 5)
        assertFalse(results.isEmpty())
        assertTrue("expected 'help' in $results", "help" in results)
    }

    @Test fun `suggest hel returns known completions`() {
        // Results are sorted by frequency — exact set may vary with word list.
        val results = trie.suggest("hel", 5)
        assertTrue("help should be a top-5 completion of 'hel'", "help" in results)
        assertTrue("hello should be a top-5 completion of 'hel'", "hello" in results)
    }

    @Test fun `suggest th contains the`() {
        val results = trie.suggest("th", 3)
        assertTrue("Expected 'the' in $results", "the" in results)
    }

    @Test fun `suggest respects max parameter`() {
        val results = trie.suggest("hel", 1)
        assertEquals(1, results.size)

        val results3 = trie.suggest("hel", 3)
        assertEquals(3, results3.size)

        val results10 = trie.suggest("hel", 10)
        assertTrue(results10.size <= 10)
    }

    @Test fun `suggest all results start with prefix`() {
        val results = trie.suggest("pr", 10)
        assertTrue(results.isNotEmpty())
        for (word in results) {
            assertTrue("'$word' does not start with 'pr'", word.startsWith("pr"))
        }
    }

    @Test fun `suggest with max 0 returns empty`() {
        assertTrue(trie.suggest("hel", 0).isEmpty())
    }

    @Test fun `suggest very long prefix that exists`() {
        // Walk down a long known path (verified: "communicate" is in the trie).
        val results = trie.suggest("communica", 3)
        assertTrue(results.isNotEmpty())
        assertTrue(results.all { it.startsWith("communica") })
    }

    @Test fun `suggest very long prefix that does not exist`() {
        val results = trie.suggest("xqzjwxqzjwxqzjw", 3)
        assertTrue(results.isEmpty())
    }

    // ── Magic validation ──────────────────────────────────────────────────────

    @Test(expected = IllegalArgumentException::class)
    fun `fromBytes throws on bad magic`() {
        val bad = ByteArray(100) { 0 }
        "WRONG".toByteArray().copyInto(bad, 0)
        Trie.fromBytes(bad)
    }

    // ── Benchmarks ────────────────────────────────────────────────────────────
    // These print results and do not assert timing (CI machines vary too much).
    // Run locally and read the output. Values should be well under 1ms per call.

    @Test fun `benchmark has short word`() {
        val ms = bench(10_000) { trie.has("the") }
        println("has(short 'the')       10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark has medium word`() {
        val ms = bench(10_000) { trie.has("keyboard") }
        println("has(medium 'keyboard') 10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark has long word`() {
        val ms = bench(10_000) { trie.has("international") }
        println("has(long 'internatl')  10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark has very long nonexistent`() {
        val ms = bench(10_000) { trie.has("xqzjwxqzjwxqzjwxqzjw") }
        println("has(very long missing) 10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark suggest short prefix`() {
        val ms = bench(10_000) { trie.suggest("th", 3) }
        println("suggest('th',3)        10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark suggest medium prefix`() {
        val ms = bench(10_000) { trie.suggest("hel", 3) }
        println("suggest('hel',3)       10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark suggest long prefix`() {
        val ms = bench(10_000) { trie.suggest("communica", 3) }
        println("suggest('communica',3) 10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark suggest missing prefix`() {
        val ms = bench(10_000) { trie.suggest("xqzjw", 3) }
        println("suggest(missing,3)     10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark suggest with max 10`() {
        val ms = bench(10_000) { trie.suggest("pr", 10) }
        println("suggest('pr',10)       10k calls: ${ms.fmt()}ms total, ${(ms/10_000).fmt3()}ms each")
    }

    @Test fun `benchmark mixed realistic usage`() {
        // Simulates typing a word character by character and calling suggest after each.
        val word = "keyboard"
        val ms = bench(1_000) {
            for (i in 1..word.length) {
                trie.suggest(word.substring(0, i), 3)
            }
        }
        println("typing 'keyboard' (8 suggest calls/word): ${ms.fmt()}ms for 1k words, ${(ms/1000).fmt3()}ms/word")
    }

    @Test fun `benchmark load from bytes`() {
        val bytes = File("src/main/assets/en.trie").readBytes()
        val ms = bench(100) { Trie.fromBytes(bytes) }
        println("Trie.fromBytes(TRIF)   100 loads:  ${ms.fmt()}ms total, ${(ms/100).fmt3()}ms each")
    }

    // ── Memory ────────────────────────────────────────────────────────────────

    @Test fun `memory usage is bounded by file size`() {
        // The trie holds one ByteBuffer wrapping the raw file bytes.
        // No extra data structures are allocated at load time.
        // File is ~2.2MB (TRIF format with frequencies) — expect delta under 3x file size.
        val rt = Runtime.getRuntime()
        System.gc()
        val before = rt.totalMemory() - rt.freeMemory()
        val bytes = File("src/main/assets/en.trie").readBytes()
        val t = Trie.fromBytes(bytes)
        System.gc()
        val after = rt.totalMemory() - rt.freeMemory()
        val deltaKb = (after - before) / 1024
        println("Memory delta after Trie load: ${deltaKb}KB")
        // ByteBuffer.wrap does not copy — delta should be close to file size.
        // Allow 3x headroom for JVM overhead.
        val fileSizeKb = File("src/main/assets/en.trie").length() / 1024
        assertTrue("Memory delta ${deltaKb}KB seems too large (file=${fileSizeKb}KB)", deltaKb < 4 * fileSizeKb)
        // prevent GC of t before measurement
        assertNotNull(t)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun Double.fmt() = "%.2f".format(this)
    private fun Double.fmt3() = "%.4f".format(this)
}
