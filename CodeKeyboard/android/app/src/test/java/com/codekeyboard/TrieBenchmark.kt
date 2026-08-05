package com.codekeyboard

import org.junit.BeforeClass
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runners.MethodSorters
import java.io.File

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TrieBenchmark {

    companion object {
        private lateinit var trie: Trie
        private val results = mutableListOf<String>()

        @BeforeClass @JvmStatic
        fun load() {
            trie = Trie.fromBytes(File("src/main/assets/en.trie").readBytes())
        }

        @org.junit.AfterClass @JvmStatic
        fun printAll() {
            println("\n========== TRIE BENCHMARK RESULTS ==========")
            results.forEach { println(it) }
            println("============================================\n")
        }

        private fun row(label: String, ms: Double, iters: Int) {
            val perCall = ms / iters * 1000  // microseconds
            results.add("%-42s %8.2fms total   %7.3fµs/call".format(label, ms, perCall))
        }

        private inline fun bench(warmup: Int, iters: Int, block: () -> Unit): Double {
            repeat(warmup) { block() }
            val t0 = System.nanoTime()
            repeat(iters) { block() }
            return (System.nanoTime() - t0) / 1_000_000.0
        }
    }

    // ── has() ─────────────────────────────────────────────────────────────────

    @Test fun bench01_has_single_char() {
        row("has('a') [1 char, not a word]", bench(1000, 100_000) { trie.has("a") }, 100_000)
    }

    @Test fun bench02_has_short() {
        row("has('the') [3 chars]", bench(1000, 100_000) { trie.has("the") }, 100_000)
    }

    @Test fun bench03_has_medium() {
        row("has('keyboard') [8 chars]", bench(1000, 100_000) { trie.has("keyboard") }, 100_000)
    }

    @Test fun bench04_has_long() {
        row("has('thanksgiving') [12 chars]", bench(1000, 100_000) { trie.has("thanksgiving") }, 100_000)
    }

    @Test fun bench05_has_miss_early() {
        row("has('xqz...') [miss at char 1]", bench(1000, 100_000) { trie.has("xqzjw") }, 100_000)
    }

    @Test fun bench06_has_miss_late() {
        row("has('thanksgivingX') [miss at char 13]", bench(1000, 100_000) { trie.has("thanksgivingx") }, 100_000)
    }

    // ── suggest() ─────────────────────────────────────────────────────────────

    @Test fun bench07_suggest_1char_max3() {
        row("suggest('t', 3) [1-char prefix]", bench(1000, 10_000) { trie.suggest("t", 3) }, 10_000)
    }

    @Test fun bench08_suggest_short_max3() {
        row("suggest('th', 3) [2-char prefix]", bench(1000, 10_000) { trie.suggest("th", 3) }, 10_000)
    }

    @Test fun bench09_suggest_medium_max3() {
        row("suggest('hel', 3) [3-char prefix]", bench(1000, 10_000) { trie.suggest("hel", 3) }, 10_000)
    }

    @Test fun bench10_suggest_long_max3() {
        row("suggest('communica', 3) [9-char prefix]", bench(1000, 10_000) { trie.suggest("communica", 3) }, 10_000)
    }

    @Test fun bench11_suggest_miss() {
        row("suggest('xqzjw', 3) [prefix miss]", bench(1000, 10_000) { trie.suggest("xqzjw", 3) }, 10_000)
    }

    @Test fun bench12_suggest_max1() {
        row("suggest('hel', 1) [max=1]", bench(1000, 10_000) { trie.suggest("hel", 1) }, 10_000)
    }

    @Test fun bench13_suggest_max10() {
        row("suggest('pr', 10) [max=10, wide subtree]", bench(1000, 10_000) { trie.suggest("pr", 10) }, 10_000)
    }

    // ── Realistic typing simulation ───────────────────────────────────────────

    @Test fun bench14_typing_short_word() {
        // Typing "the" — 3 suggest calls (after each char)
        val word = "the"
        val ms = bench(500, 10_000) {
            for (i in 1..word.length) trie.suggest(word.substring(0, i), 3)
        }
        row("typing 'the' [3 suggest calls]", ms, 10_000)
    }

    @Test fun bench15_typing_medium_word() {
        // Typing "keyboard" — 8 suggest calls
        val word = "keyboard"
        val ms = bench(500, 10_000) {
            for (i in 1..word.length) trie.suggest(word.substring(0, i), 3)
        }
        row("typing 'keyboard' [8 suggest calls]", ms, 10_000)
    }

    @Test fun bench16_typing_long_word() {
        // Typing "thanksgiving" — 12 suggest calls
        val word = "thanksgiving"
        val ms = bench(500, 10_000) {
            for (i in 1..word.length) trie.suggest(word.substring(0, i), 3)
        }
        row("typing 'thanksgiving' [12 suggest calls]", ms, 10_000)
    }

    // ── Load ──────────────────────────────────────────────────────────────────

    @Test fun bench17_load() {
        val bytes = File("src/main/assets/en.trie").readBytes()
        val ms = bench(10, 200) { Trie.fromBytes(bytes) }
        row("Trie.fromBytes(438KB) [parse + wrap]", ms, 200)
    }

    // ── Memory ────────────────────────────────────────────────────────────────

    @Test fun bench18_memory() {
        val rt = Runtime.getRuntime()
        System.gc(); Thread.sleep(50)
        val before = rt.totalMemory() - rt.freeMemory()
        val bytes = File("src/main/assets/en.trie").readBytes()
        val t = Trie.fromBytes(bytes)
        System.gc(); Thread.sleep(50)
        val after = rt.totalMemory() - rt.freeMemory()
        val deltaKB = (after - before) / 1024
        results.add("%-42s %8dKB heap delta  (file=438KB)".format("Memory: Trie.fromBytes()", deltaKB))
        // suppress unused warning
        assert(t.has("the"))
    }
}
