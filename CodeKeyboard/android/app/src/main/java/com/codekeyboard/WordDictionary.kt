package com.codekeyboard

/**
 * WordDictionary — pack-backed prefix/fuzzy completion over the char-trie (WORD tier).
 *
 * Wraps LanguagePack's char-trie section and provides:
 * - TrieAdapter<Int> implementation for BevaTrieSearch / FuzzyTrieSearch
 * - has(word): Boolean — exact word lookup
 * - suggest(prefix, max): List<String> — prefix completion, frequency-ranked
 *
 * This replaces the legacy Trie for locale dictionaries. The char-trie in the CKLM
 * pack stores all vocabulary words with log10-encoded unigram frequencies.
 */
class WordDictionary(private val pack: LanguagePack) : PrefixDictionary {

    // ── TrieAdapter<Int> for fuzzy search ──────────────────────────────────────

    /**
     * Adapter exposing the char-trie as a TrieAdapter<Int> for BevaTrieSearch.
     * Node type is Int (char-trie node index).
     */
    val adapter: TrieAdapter<Int> = object : TrieAdapter<Int> {
        override val root: Int = pack.charTrieRoot()

        override fun isTerminal(node: Int): Boolean = pack.charTrieIsTerminal(node)

        override fun frequency(node: Int): Int = pack.charTrieFreqByte(node)

        override fun iterateChildren(node: Int, block: (Char, Int) -> Unit) {
            pack.charTrieIterateChildren(node) { codePoint, childNodeIdx ->
                block(codePoint.toChar(), childNodeIdx)
            }
        }
    }

    // ── Public API (matches Trie surface) ──────────────────────────────────────

    /**
     * Returns true if the exact word exists in the char-trie (is a terminal node).
     * Case-sensitive — the char-trie stores lowercase words.
     */
    override fun has(word: String): Boolean = pack.has(word)

    /**
     * Returns up to `max` word completions for the given prefix, ranked by unigram score (descending).
     * Returns empty list if prefix not found or max <= 0.
     * Matches Trie.suggest signature: returns List<String> (words only, no scores).
     */
    override fun suggest(prefix: String, max: Int): List<String> {
        if (prefix.isEmpty() || max <= 0) return emptyList()
        return pack.suggest(prefix, max).map { it.first }
    }

    /** Returns the underlying LanguagePack for advanced use cases. */
    val languagePack: LanguagePack get() = pack

    // ── Correction (ADR-013) ───────────────────────────────────────────────────
    // SymSpell runtime build removed — build time (5-9s) causes ANR.
    // Correction uses BevaTrieSearch with QWERTY proximity cost injected into
    // the substitution weight. SymSpell pre-built index is planned for the next
    // .cklm compiler pass (offline build, mmap at startup).

    private val proximityScorer = ProximityScorer(QwertyAdjacency())

    override fun correct(word: String, maxResults: Int): List<FuzzyResult> {
        val threshold = FuzzyThreshold.forLength(word.length)
        if (threshold == 0) return emptyList()
        val results = BevaTrieSearch.search(adapter, word, threshold, maxResults)
        return proximityScorer.rank(word, results)
    }
}