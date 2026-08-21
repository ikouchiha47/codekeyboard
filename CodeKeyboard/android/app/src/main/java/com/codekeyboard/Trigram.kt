package com.codekeyboard

/**
 * Static (2-word-context) seed model — [NgramModel.order] = 3.
 *
 * Lookup logic is identical across every n-gram order (load pre-scored
 * candidates for a context key, filter by prefix, take top-N) — only the
 * context-key length and backing data differ per order. [AbstractNgram]
 * holds that shared logic so Trigram/Bigram/Pentagram stay one-line
 * classes instead of duplicating it.
 *
 * NOT wired into the production suggestion pipeline yet — see ADR-001's
 * smoothing addendum / ADR-007 checkpoint log for the data investigation
 * this came out of. Requires `trigrams.json` to exist as a real asset
 * (built from a corpus large enough for dense 2-word-context coverage —
 * an offline 15.6M-word OpenSubtitles sample gave only a marginal/mixed
 * result vs. plain bigram, so this needs a bigger corpus pull before it's
 * worth wiring up for real).
 */
abstract class AbstractNgram(
    final override val order: Int,
    private val dataSource: NgramDataSource,
) : NgramModel {

    private val seed: Map<String, NgramEntry> by lazy {
        try {
            dataSource.load()
        } catch (e: Exception) {
            // Defensive degradation (ADR-008): a missing or malformed asset
            // (e.g. trigrams.json not built yet) makes this tier "unavailable"
            // instead of crashing the IME — the cascade falls through to the
            // next model. Covered by NgramSanityTest.
            emptyMap()
        }
    }

    private fun key(context: Array<out String>): String? {
        val needed = order - 1
        if (context.size < needed) return null
        return context.toList().takeLast(needed).joinToString(" ") { it.lowercase() }
    }

    override fun nextWords(vararg context: String, prefix: String, n: Int): List<String> {
        val key = key(context) ?: return emptyList()
        val pfx = prefix.lowercase()

        return seed[key]?.followers
            ?.filter { (word, _) -> pfx.isEmpty() || word.startsWith(pfx) }
            ?.sortedByDescending { (_, score) -> score }
            ?.take(n)
            ?.map { (word, _) -> word }
            ?: emptyList()
    }

    override fun support(vararg context: String): Int {
        val key = key(context) ?: return 0
        return seed[key]?.support ?: 0
    }
}

class Trigram(dataSource: NgramDataSource) : AbstractNgram(order = 3, dataSource = dataSource)
