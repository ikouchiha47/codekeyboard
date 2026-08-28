package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test
import java.io.File

// Loads the CKLM pack from assets/ on disk (no Android context needed in unit tests).
// Run with: ./gradlew integrationTest
@IntegrationTest
class FuzzyIntegrationTest {

    // Locate en.cklm relative to the project root.
    private val packFile: File = run {
        val candidates = listOf(
            File("src/main/assets/en.cklm"),             // workingDir = app/ (default)
            File("app/src/main/assets/en.cklm"),         // workingDir = android/
            File("android/app/src/main/assets/en.cklm"), // workingDir = project root
        )
        candidates.firstOrNull { it.exists() }
            ?: error("en.cklm not found. Tried: ${candidates.map { it.absolutePath }}")
    }

    // The production dictionary is the pack's char-trie (WORD tier) — the legacy
    // en.trie asset was removed (ADR-010 tasks L/M). WordDictionary exposes it as
    // a TrieAdapter<Int> for BevaTrieSearch.
    private val adapter: TrieAdapter<Int> by lazy {
        WordDictionary(LanguagePack.open(packFile)).adapter
    }

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

    // ── Proximity BEVA — no garbage candidates ─────────────────────────────────

    @Test fun `sesrcg finds search and NOT seaver desert decry`() {
        val results = BevaTrieSearch.search(adapter, "sesrcg", 2, Int.MAX_VALUE, QwertyAdjacency())
        val words = results.map { it.word }.toSet()
        println("sesrcg results (${results.size}): ${results.sortedBy { it.editDistance }.take(15).map { "${it.word}(d=${it.editDistance})" }}")
        assertTrue("search must be found", "search" in words)
        assertFalse("seaver must NOT be found (weighted dist > 2)", "seaver" in words)
        assertFalse("desert must NOT be found", "desert" in words)
        assertFalse("decry must NOT be found", "decry" in words)
    }

    @Test fun `sescrg probe — does proximity BEVA find search at threshold 3`() {
        val results = BevaTrieSearch.search(adapter, "sescrg", 3, Int.MAX_VALUE, QwertyAdjacency())
        val words = results.map { it.word }.toSet()
        println("sescrg top-15 (t=3): ${results.sortedBy { it.editDistance }.take(15).map { "${it.word}(d=${it.editDistance})" }}")
        println("sescrg: search found=" + ("search" in words))
    }

    @Test fun `adwrxg finds search — QWERTY adjacent shift with one match`() {
        // adwrxg→search: a→s(adj) d→e(adj) w→a(adj) r→r(match) x→c(adj) g→h(adj)
        // 5 adjacent subs × 0.5 = 2.5 edit distance = 5 half-steps, within threshold 3 (k=6).
        val results = BevaTrieSearch.search(adapter, "adwrxg", 3, Int.MAX_VALUE, QwertyAdjacency())
        val words = results.map { it.word }.toSet()
        println("adwrxg top-15 (t=3): ${results.sortedBy { it.editDistance }.take(15).map { "${it.word}(d=${it.editDistance})" }}")
        assertTrue("search must be found for QWERTY shift 'adwrxg'", "search" in words)
    }

    @Test fun `drstvj finds search — QWERTY right-shift of all 6 keys`() {
        // search right-shifted one column on QWERTY: s→d e→r a→s r→t c→v h→j
        // All 6 subs are adjacent → 6×0.5 = 3.0 edit distance, exactly at threshold 3.
        val results = BevaTrieSearch.search(adapter, "drstvj", 3, Int.MAX_VALUE, QwertyAdjacency())
        val words = results.map { it.word }.toSet()
        println("drstvj top-15 (t=3): ${results.sortedBy { it.editDistance }.take(15).map { "${it.word}(d=${it.editDistance})" }}")
        assertTrue("search must be found for QWERTY right-shift 'drstvj'", "search" in words)
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