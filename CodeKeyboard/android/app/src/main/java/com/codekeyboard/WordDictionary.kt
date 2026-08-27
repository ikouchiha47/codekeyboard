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

    // ── SymSpell correction (ADR-013) ──────────────────────────────────────────

    /**
     * Lazily-built SymSpell delete-index over the full pack vocabulary.
     * Built once on first use; memory is bounded by the vocab size × delete
     * variants at maxDist 2 (ADR-013 Task H gates this on device).
     */
    private val symSpellIndex: SymSpellIndex by lazy {
        val vocab = pack.allWords().filter { it.length > 3 }.toSet()
        SymSpellIndex.build(vocab, maxDist = 2)
    }

    private val symSpellCorrector: SymSpellCorrector by lazy {
        SymSpellCorrector(symSpellIndex, QwertyAdjacency(), maxDist = 2)
    }

    /**
     * Correction path (ADR-013): merges BevaTrieSearch (trie edit-distance)
     * with SymSpell (delete-index reachability, catches multi-key slides),
     * deduplicates, and reranks with the layout-aware [ProximityScorer].
     *
     * Returns candidates with the adjacency-weighted distance, best first.
     */
    override fun correct(word: String, maxResults: Int): List<FuzzyResult> {
        val threshold = FuzzyThreshold.forLength(word.length)
        if (threshold == 0) return emptyList()

        val beva = BevaTrieSearch.search(adapter, word, threshold, maxResults * 2)
        val sym = symSpellCorrector.correct(word)
        return merge(word, beva, sym).take(maxResults)
    }

    /** Merge by word, keeping the lower edit distance; rerank with ProximityScorer. */
    private fun merge(input: String, a: List<FuzzyResult>, b: List<FuzzyResult>): List<FuzzyResult> {
        val byWord = LinkedHashMap<String, FuzzyResult>()
        for (r in a + b) {
            val existing = byWord[r.word]
            if (existing == null || r.editDistance < existing.editDistance) {
                byWord[r.word] = r
            }
        }
        return ProximityScorer(QwertyAdjacency()).rank(input, byWord.values.toList())
    }
}