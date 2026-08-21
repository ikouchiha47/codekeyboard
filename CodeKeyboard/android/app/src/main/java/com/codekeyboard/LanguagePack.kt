package com.codekeyboard

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlin.math.pow

/**
 * CKLM v1 binary language-pack reader.
 *
 * File format (little-endian, mmap-friendly):
 * - Header (96 bytes): magic, version, word_id_bytes, score range, counts, section offsets
 * - Vocab table: u32 offsets (relative to vocab section) + NUL-terminated UTF-8 blob
 * - Char-trie section (WORD tier): node array + children data
 * - Context-trie nodes (20 bytes each): children_offset u32, child_count u16, followers_offset u32,
 *   follower_count u8, phrase_score u8, flags u8, support u32, 3 reserved
 * - Children (6 bytes each, sorted by word_id): word_id u16, child_node u32
 * - Followers (3 bytes each, sorted by score DESC): word_id u16, score u8
 *
 * Score decode: score = 10^(score_min + (byte / 255.0) * (score_max - score_min))
 *
 * Depth semantics: root (0) = unigram context, depth-1 = bigram context, depth-2 = trigram context.
 * Phrase terminals: nodes with phrase_score > 0.
 * Char-trie (WORD tier): root=0, terminals have freq > 0 (log10-encoded unigram score).
 */
class LanguagePack private constructor(private val buf: ByteBuffer) {

    companion object {
        private const val MAGIC = "CKLM"
        private const val VERSION = 1
        private const val WORD_ID_BYTES = 2
        private const val HEADER_SIZE = 96
        private const val CONTEXT_TRIE_NODE_SIZE = 20  // children_offset u32, child_count u16, followers_offset u32, follower_count u8, phrase_score u8, flags u8, support u32, 3 reserved
        private const val CHAR_TRIE_NODE_SIZE = 12  // char u32, flags u8, children_offset u32, freq u8, 2 reserved
        private const val CHAR_TRIE_CHILD_ENTRY_SIZE = 8  // char u32 + child_index u32

        /**
         * Opens a CKLM file via memory-mapped read-only ByteBuffer.
         * Validates header magic, version, and word_id_bytes.
         */
        @Throws(IOException::class)
        fun open(file: File): LanguagePack {
            val channel = FileChannel.open(file.toPath(), StandardOpenOption.READ)
            try {
                val buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size())
                    .order(ByteOrder.LITTLE_ENDIAN)
                return LanguagePack(buf)
            } catch (e: Exception) {
                channel.close()
                throw e
            }
        }
    }

    // ── Header fields ──────────────────────────────────────────────────────────

    private val scoreMin: Float = buf.getFloat(4 + 2 + 1 + 1)  // after magic(4) + version(2) + word_id_bytes(1) + reserved(1)
    private val scoreMax: Float = buf.getFloat(4 + 2 + 1 + 1 + 4)

    private val vocabCount: Int = buf.getInt(4 + 2 + 1 + 1 + 4 + 4)
    private val nodeCountVal: Int = buf.getInt(4 + 2 + 1 + 1 + 4 + 4 + 4)
    private val followerCount: Int = buf.getInt(4 + 2 + 1 + 1 + 4 + 4 + 4 + 4)
    private val phraseCountVal: Int = buf.getInt(4 + 2 + 1 + 1 + 4 + 4 + 4 + 4 + 4)

    private val offsetVocab: Long = buf.getLong(4 + 2 + 1 + 1 + 4 + 4 + 4 + 4 + 4 + 4)
    private val offsetNodes: Long = buf.getLong(4 + 2 + 1 + 1 + 4 + 4 + 4 + 4 + 4 + 4 + 8)
    private val offsetChildren: Long = buf.getLong(4 + 2 + 1 + 1 + 4 + 4 + 4 + 4 + 4 + 4 + 8 + 8)
    private val offsetFollowers: Long = buf.getLong(4 + 2 + 1 + 1 + 4 + 4 + 4 + 4 + 4 + 4 + 8 + 8 + 8)
    // fileSize at +8 (offset 64), reserved2 at +24 (offset 72)
    // New fields in reserved2 area:
    private val charTrieOffset: Long = buf.getLong(72)   // byte 72
    private val charTrieNodes: Int = buf.getInt(80)      // byte 80

    // ── Section base pointers (absolute file offsets, fit in 32-bit for this file size) ──────────────────────────

    private val vocabBase: Int = offsetVocab.toInt()
    private val charTrieBase: Int = charTrieOffset.toInt()
    private val nodesBase: Int = offsetNodes.toInt()
    private val childrenBase: Int = offsetChildren.toInt()
    private val followersBase: Int = offsetFollowers.toInt()

    // ── Vocab: offsets array + string blob ─────────────────────────────────────

    private val vocabOffsets: IntArray = IntArray(vocabCount) { i ->
        buf.getInt(vocabBase + i * 4)
    }
    private val vocabBlobStart: Int = vocabBase + vocabCount * 4

    init {
        // Validate header
        val magicBytes = ByteArray(4)
        buf.position(0)
        buf.get(magicBytes)
        val magic = String(magicBytes)
        require(magic == MAGIC) { "Invalid magic: '$magic', expected '$MAGIC'" }

        val version = buf.getShort(4).toInt()
        require(version == VERSION) { "Unsupported version: $version, expected $VERSION" }

        val wordIdBytes = buf.get(6).toInt()
        require(wordIdBytes == WORD_ID_BYTES) { "Unsupported word_id_bytes: $wordIdBytes, expected $WORD_ID_BYTES" }
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /** Returns the word for the given word-ID (0 <= id < vocabCount). */
    fun word(id: Int): String {
        require(id in 0 until vocabCount) { "word id $id out of range [0, $vocabCount)" }
        val offset = vocabOffsets[id]
        val start = vocabBlobStart + offset
        // Find NUL terminator
        var end = start
        val capacity = buf.capacity()
        while (end < capacity && buf.get(end) != 0.toByte()) {
            end++
        }
        val length = end - start
        val bytes = ByteArray(length)
        buf.position(start)
        buf.get(bytes)
        return String(bytes, java.nio.charset.StandardCharsets.UTF_8)
    }

    /** Returns the word-ID for the given word, or -1 if not found (binary search over sorted vocab). */
    fun id(word: String): Int {
        var lo = 0
        var hi = vocabCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val midWord = word(mid)
            val cmp = midWord.compareTo(word)
            if (cmp == 0) return mid
            if (cmp < 0) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    /** Vocabulary size. */
    val vocabSize: Int get() = vocabCount

    /** Total number of context-trie nodes. */
    val nodeCount: Int get() = nodeCountVal

    /** Total number of follower entries across all context-trie nodes. */
    val totalFollowers: Int get() = followerCount

    /** Number of phrase terminals. */
    val phraseCount: Int get() = phraseCountVal

    /** Number of char-trie nodes. */
    val charTrieNodeCount: Int get() = charTrieNodes

    /** Log10 score range [min, max]. */
    val scoreRange: Pair<Float, Float> get() = scoreMin to scoreMax

    /**
     * Returns ranked followers for the given context (list of word-IDs).
     * Walks the context-trie from root, binary-searching each node's sorted children.
     * Returns list of (wordId, decodedScore) in file order (already score-descending).
     * Returns empty list if context path doesn't exist.
     */
    fun followers(context: List<Int>): List<Pair<Int, Float>> {
        var nodeIdx = 0
        for (wordId in context) {
            nodeIdx = findChild(nodeIdx, wordId)
            if (nodeIdx < 0) return emptyList()
        }
        return readFollowers(nodeIdx)
    }

    /**
     * Returns the support count (number of training occurrences) for the given context.
     * Walks the context-trie from root, binary-searching each node's sorted children.
     * Returns 0 if context path doesn't exist.
     */
    fun support(context: List<Int>): Int {
        var nodeIdx = 0
        for (wordId in context) {
            nodeIdx = findChild(nodeIdx, wordId)
            if (nodeIdx < 0) return 0
        }
        return readSupport(nodeIdx)
    }

    /**
     * Returns phrase terminals within maxExtension words from the context node.
     * Each result is (wordIdPathFromContext, decodedPhraseScore).
     * The path includes the context words + extension words.
     */
    fun phrases(context: List<Int>, maxExtension: Int = 3): List<Pair<List<Int>, Float>> {
        var nodeIdx = 0
        for (wordId in context) {
            nodeIdx = findChild(nodeIdx, wordId)
            if (nodeIdx < 0) return emptyList()
        }

        val results = mutableListOf<Pair<List<Int>, Float>>()
        val pathBuffer = IntArray(maxExtension) // scratch buffer for extension path
        collectPhrases(nodeIdx, context, pathBuffer, 0, maxExtension, results)
        return results
    }

    // ── Char-trie (WORD tier) public API ───────────────────────────────────────

    /**
     * Returns up to `max` word completions for the given prefix, ranked by unigram score (descending).
     * Each result is (word, decodedScore) where score is the unigram probability.
     * Returns empty list if prefix not found.
     */
    fun suggest(prefix: String, max: Int = 5): List<Pair<String, Float>> {
        if (prefix.isEmpty() || max <= 0) return emptyList()

        // Char-trie stores lowercase words; lowercase the prefix so capitalized
        // input (e.g. sentence-start auto-capitalization) still completes.
        val lower = prefix.lowercase()
        val nodeIdx = walkCharTrie(lower)
        if (nodeIdx < 0) return emptyList()

        val results = mutableListOf<Pair<String, Float>>()
        val charBuffer = IntArray(64) // scratch buffer for building words (max word length)
        val prefixChars = lower.toCharArray()
        // Copy prefix into buffer
        for (i in prefixChars.indices) {
            charBuffer[i] = prefixChars[i].toInt()
        }
        collectCharTrieWords(nodeIdx, charBuffer, prefixChars.size, results)

        // Sort by score descending and take top max
        results.sortByDescending { it.second }
        return results.take(max)
    }

    /**
     * Returns true if the exact word exists in the char-trie (is a terminal node).
     */
    fun has(word: String): Boolean {
        if (word.isEmpty()) return false
        val nodeIdx = walkCharTrie(word.lowercase())
        if (nodeIdx < 0) return false
        return isCharTrieTerminal(nodeIdx)
    }

    /**
     * Returns the unigram score for the given word-ID by looking it up in the char-trie.
     * Returns 0.0f if the word is not a terminal in the char-trie.
     */
    fun unigramScore(wordId: Int): Float {
        if (wordId !in 0 until vocabCount) return 0.0f
        val wordStr = word(wordId)
        val nodeIdx = walkCharTrie(wordStr)
        if (nodeIdx < 0) return 0.0f
        return readCharTrieFreq(nodeIdx)
    }

    // ── Char-trie accessors for WordDictionary / TrieAdapter ────────────────────

    /** Root node index of the char-trie (always 0). */
    fun charTrieRoot(): Int = 0

    /** Returns true if the char-trie node is a terminal (has terminal flag set). */
    fun charTrieIsTerminal(nodeIdx: Int): Boolean = isCharTrieTerminal(nodeIdx)

    /** Returns the raw u8 freq byte from the char-trie node (0 for non-terminals or below decode floor). */
    fun charTrieFreqByte(nodeIdx: Int): Int {
        val nodeOffset = charTrieBase + nodeIdx * CHAR_TRIE_NODE_SIZE
        return buf.get(nodeOffset + 9).toInt() and 0xFF
    }

    /**
     * Iterates over children of a char-trie node.
     * For each child, calls `block(codePoint, childNodeIdx)`.
     * Mirrors Trie.iterateChildren for TrieAdapter compatibility.
     */
    fun charTrieIterateChildren(nodeIdx: Int, block: (Int, Int) -> Unit) {
        val nodeOffset = charTrieBase + nodeIdx * CHAR_TRIE_NODE_SIZE
        val flags = buf.get(nodeOffset + 4).toInt() and 0xFF
        val hasChildren = (flags and 0x02) != 0
        if (!hasChildren) return

        val childrenOffset = buf.getInt(nodeOffset + 5)
        val childCount = buf.get(charTrieBase + childrenOffset).toInt() and 0xFF
        if (childCount == 0) return

        val childrenDataBase = charTrieBase + childrenOffset + 1
        for (i in 0 until childCount) {
            val entryOffset = childrenDataBase + i * CHAR_TRIE_CHILD_ENTRY_SIZE
            val childChar = buf.getInt(entryOffset)
            val childNodeIdx = buf.getInt(entryOffset + 4)
            block(childChar, childNodeIdx)
        }
    }

    // ── Internal helpers: Context-trie ─────────────────────────────────────────

    /** Binary search for child with given word_id in node's children array. Returns child node index or -1. */
    private fun findChild(nodeIdx: Int, wordId: Int): Int {
        val nodeOffset = nodesBase + nodeIdx * CONTEXT_TRIE_NODE_SIZE
        val childrenOffset = buf.getInt(nodeOffset)
        val childCount = (buf.getShort(nodeOffset + 4).toInt() and 0xFFFF)

        if (childCount == 0) return -1

        var lo = 0
        var hi = childCount - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val childOffset = childrenBase + childrenOffset + mid * 6
            val midWordId = (buf.getShort(childOffset).toInt() and 0xFFFF)
            if (midWordId == wordId) {
                return buf.getInt(childOffset + 2)
            }
            if (midWordId < wordId) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    /** Reads all followers for a context-trie node, decoding scores. */
    private fun readFollowers(nodeIdx: Int): List<Pair<Int, Float>> {
        val nodeOffset = nodesBase + nodeIdx * CONTEXT_TRIE_NODE_SIZE
        val followersOffset = buf.getInt(nodeOffset + 6)
        val followerCount = (buf.get(nodeOffset + 10).toInt() and 0xFF)

        if (followerCount == 0) return emptyList()

        val result = ArrayList<Pair<Int, Float>>(followerCount)
        var pos = followersBase + followersOffset
        repeat(followerCount) {
            val wordId = (buf.getShort(pos).toInt() and 0xFFFF)
            val scoreByte = buf.get(pos + 2).toInt() and 0xFF
            val score = decodeScore(scoreByte)
            result.add(wordId to score)
            pos += 3
        }
        return result
    }

    /** Reads the support count for a context-trie node. */
    private fun readSupport(nodeIdx: Int): Int {
        val nodeOffset = nodesBase + nodeIdx * CONTEXT_TRIE_NODE_SIZE
        // support u32 at offset 13 from node start
        return buf.getInt(nodeOffset + 13)
    }

    /** Bounded DFS from context node to collect phrase terminals. */
    private fun collectPhrases(
        nodeIdx: Int,
        context: List<Int>,
        pathBuffer: IntArray,
        depth: Int,
        maxExtension: Int,
        results: MutableList<Pair<List<Int>, Float>>
    ) {
        val nodeOffset = nodesBase + nodeIdx * CONTEXT_TRIE_NODE_SIZE
        val phraseScoreByte = buf.get(nodeOffset + 11).toInt() and 0xFF
        if (phraseScoreByte > 0) {
            val phraseScore = decodeScore(phraseScoreByte)
            val extension = pathBuffer.copyOfRange(0, depth).toList()
            val path = context + extension
            results.add(path to phraseScore)
        }

        if (depth >= maxExtension) return

        val childrenOffset = buf.getInt(nodeOffset)
        val childCount = (buf.getShort(nodeOffset + 4).toInt() and 0xFFFF)
        if (childCount == 0) return

        var childPos = childrenBase + childrenOffset
        repeat(childCount) {
            val childWordId = (buf.getShort(childPos).toInt() and 0xFFFF)
            val childNodeIdx = buf.getInt(childPos + 2)
            pathBuffer[depth] = childWordId
            collectPhrases(childNodeIdx, context, pathBuffer, depth + 1, maxExtension, results)
            childPos += 6
        }
    }

    // ── Internal helpers: Char-trie ────────────────────────────────────────────

    /** Walks the char-trie following the given prefix string. Returns node index or -1 if not found. */
    private fun walkCharTrie(prefix: String): Int {
        var nodeIdx = 0
        for (i in 0 until prefix.length) {
            val cp = prefix.codePointAt(i)
            nodeIdx = findCharChild(nodeIdx, cp)
            if (nodeIdx < 0) return -1
        }
        return nodeIdx
    }

    /** Binary search for child with given code point in char-trie node's children array. */
    private fun findCharChild(nodeIdx: Int, codePoint: Int): Int {
        val nodeOffset = charTrieBase + nodeIdx * CHAR_TRIE_NODE_SIZE
        val flags = buf.get(nodeOffset + 4).toInt() and 0xFF
        val hasChildren = (flags and 0x02) != 0
        if (!hasChildren) return -1

        val childrenOffset = buf.getInt(nodeOffset + 5)
        val childCount = buf.get(charTrieBase + childrenOffset).toInt() and 0xFF
        if (childCount == 0) return -1

        var lo = 0
        var hi = childCount - 1
        val childrenDataBase = charTrieBase + childrenOffset + 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val entryOffset = childrenDataBase + mid * CHAR_TRIE_CHILD_ENTRY_SIZE
            val midChar = buf.getInt(entryOffset)
            if (midChar == codePoint) {
                return buf.getInt(entryOffset + 4)
            }
            if (midChar < codePoint) lo = mid + 1 else hi = mid - 1
        }
        return -1
    }

    /** Returns true if the char-trie node is a terminal (has terminal flag set). */
    private fun isCharTrieTerminal(nodeIdx: Int): Boolean {
        val nodeOffset = charTrieBase + nodeIdx * CHAR_TRIE_NODE_SIZE
        val flags = buf.get(nodeOffset + 4).toInt() and 0xFF
        return (flags and 0x01) != 0
    }

    /** Reads and decodes the freq (unigram score) from a char-trie terminal node. */
    private fun readCharTrieFreq(nodeIdx: Int): Float {
        val nodeOffset = charTrieBase + nodeIdx * CHAR_TRIE_NODE_SIZE
        val freqByte = buf.get(nodeOffset + 9).toInt() and 0xFF
        if (freqByte == 0) return 0.0f
        return decodeScore(freqByte)
    }

    /** DFS collects all terminal words under the given char-trie node. */
    private fun collectCharTrieWords(
        nodeIdx: Int,
        charBuffer: IntArray,
        depth: Int,
        results: MutableList<Pair<String, Float>>
    ) {
        // Check if current node is a terminal (by flag, not freq — a terminal with
        // freq byte 0 is still a valid word; it just ranks last in suggest).
        if (isCharTrieTerminal(nodeIdx)) {
            val freq = readCharTrieFreq(nodeIdx) // 0.0f if freq byte 0 (below decode floor)
            // Build word from charBuffer[0..depth) - convert IntArray to CharArray
            val charArray = CharArray(depth) { charBuffer[it].toChar() }
            val word = String(charArray)
            results.add(word to freq)
        }

        val nodeOffset = charTrieBase + nodeIdx * CHAR_TRIE_NODE_SIZE
        val flags = buf.get(nodeOffset + 4).toInt() and 0xFF
        val hasChildren = (flags and 0x02) != 0
        if (!hasChildren) return

        val childrenOffset = buf.getInt(nodeOffset + 5)
        val childCount = buf.get(charTrieBase + childrenOffset).toInt() and 0xFF
        if (childCount == 0) return

        val childrenDataBase = charTrieBase + childrenOffset + 1
        for (i in 0 until childCount) {
            val entryOffset = childrenDataBase + i * CHAR_TRIE_CHILD_ENTRY_SIZE
            val childChar = buf.getInt(entryOffset)
            val childNodeIdx = buf.getInt(entryOffset + 4)
            charBuffer[depth] = childChar
            collectCharTrieWords(childNodeIdx, charBuffer, depth + 1, results)
        }
    }

    /** Decodes a u8 score byte to float using log10 mapping. */
    private fun decodeScore(byte: Int): Float {
        if (scoreMax == scoreMin) return 1.0f
        val normalized = byte / 255.0
        val log10Score = scoreMin + normalized * (scoreMax - scoreMin)
        return (10.0.pow(log10Score.toDouble())).toFloat()
    }
}