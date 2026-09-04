package com.codekeyboard

import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UserCorrectionStoreTest {

    @get:Rule val tmp = TemporaryFolder()
    private lateinit var file: File
    private lateinit var store: UserCorrectionStore

    @Before fun setUp() {
        file = tmp.newFile("corrections.tsv")
        store = UserCorrectionStore(file)
    }

    // ── basic record + lookup ─────────────────────────────────────────────────

    @Test fun `record and lookup roundtrip`() {
        store.record("sescrg", "search")
        assertEquals("search", store.lookup("sescrg"))
    }

    @Test fun `lookup unknown word returns null`() {
        assertNull(store.lookup("unknowntypo"))
    }

    @Test fun `lookup is case insensitive`() {
        store.record("Sescrg", "search")
        assertEquals("search", store.lookup("sescrg"))
        assertEquals("search", store.lookup("SESCRG"))
        assertEquals("search", store.lookup("Sescrg"))
    }

    @Test fun `typo equal to correction is not stored`() {
        store.record("search", "search")
        assertNull(store.lookup("search"))
    }

    @Test fun `blank typo is ignored`() {
        store.record("", "search")
        store.record("   ", "search")
        assertNull(store.lookup(""))
    }

    @Test fun `blank correction is ignored`() {
        store.record("sescrg", "")
        store.record("sescrg", "   ")
        assertNull(store.lookup("sescrg"))
    }

    @Test fun `later record overwrites earlier for same typo`() {
        store.record("teh", "the")
        store.record("teh", "ten")
        assertEquals("ten", store.lookup("teh"))
    }

    // ── persistence ───────────────────────────────────────────────────────────

    @Test fun `save and reload preserves entries`() {
        store.record("sescrg", "search")
        store.record("teh", "the")
        store.record("recieve", "receive")
        store.save()

        val reloaded = UserCorrectionStore(file)
        assertEquals("search",  reloaded.lookup("sescrg"))
        assertEquals("the",     reloaded.lookup("teh"))
        assertEquals("receive", reloaded.lookup("recieve"))
    }

    @Test fun `loading from non-existent file does not crash`() {
        val missing = File(tmp.root, "missing.tsv")
        val s = UserCorrectionStore(missing)
        assertNull(s.lookup("anything"))
    }

    @Test fun `save to empty store writes empty file`() {
        store.save()
        assertEquals(0L, file.length())
    }

    // ── MergedSuggestionStrategy integration ──────────────────────────────────

    private val emptyBaseAdapter = object : TrieAdapter<Int> {
        override val root: Int = -1
        override fun isTerminal(node: Int) = false
        override fun frequency(node: Int) = 0
        override fun iterateChildren(node: Int, block: (Char, Int) -> Unit) {}
    }

    @Test fun `stored correction is prepended in suggest results`() {
        store.record("sescrg", "search")

        val baseTrie = UserTrie().also { t ->
            repeat(50) { t.insert("search") }
            repeat(30) { t.insert("serene") }
        }
        val baseDict = object : PrefixDictionary {
            override fun suggest(prefix: String, max: Int) =
                UserTrieAdapter(baseTrie).suggest(prefix, max)
            override fun has(word: String) = baseTrie.suggest(word, 1).any { it.word == word }
            override fun correct(word: String, maxResults: Int): List<FuzzyResult> =
                BevaTrieSearch.search(UserTrieAdapter(baseTrie), word, FuzzyThreshold.forLength(word.length), maxResults)
        }

        val strategy = MergedSuggestionStrategy(
            UserTrieAdapter(UserTrie()), emptyBaseAdapter, baseDict, store,
        )

        val results = strategy.suggest("sescrg", 5)
        assertEquals("search", results.first())
    }

    @Test fun `stored correction not duplicated in result list`() {
        store.record("sescrg", "search")

        val baseDict = object : PrefixDictionary {
            override fun suggest(prefix: String, max: Int) = emptyList<String>()
            override fun has(word: String) = true
            override fun correct(word: String, maxResults: Int) = emptyList<FuzzyResult>()
        }

        val strategy = MergedSuggestionStrategy(
            UserTrieAdapter(UserTrie()), emptyBaseAdapter, baseDict, store,
        )

        val results = strategy.suggest("sescrg", 5)
        assertEquals(1, results.count { it == "search" })
    }

    // ── 20K stress: file size + load time ────────────────────────────────────

    @Test fun `20K entries save and reload within acceptable bounds`() {
        val n = 20_000
        repeat(n) { i -> store.record("typo$i", "correction$i") }

        val saveStart = System.currentTimeMillis()
        store.save()
        val saveMs = System.currentTimeMillis() - saveStart

        val sizeMb = file.length().toDouble() / (1024 * 1024)
        println("20K entries — file size: %.2f MB, save: ${saveMs}ms".format(sizeMb))

        val loadStart = System.currentTimeMillis()
        val reloaded = UserCorrectionStore(file)
        val loadMs = System.currentTimeMillis() - loadStart
        println("20K entries — load: ${loadMs}ms")

        // Spot-check a few entries survive reload
        assertEquals("correction0",     reloaded.lookup("typo0"))
        assertEquals("correction9999",  reloaded.lookup("typo9999"))
        assertEquals("correction19999", reloaded.lookup("typo19999"))

        // File should be well under 5MB, load under 500ms
        assertTrue("file too large: ${sizeMb}MB", sizeMb < 5.0)
        assertTrue("load too slow: ${loadMs}ms", loadMs < 500)
    }
}
