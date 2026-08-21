package com.codekeyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Eval harness for the autocomplete/suggestion pipeline — see
 * docs/architecture/decisions/ADR-007-autocomplete-eval.md for the full
 * methodology (checkpoint → tune → re-run loop).
 *
 * Loads the real production dictionaries (en.trie, bigrams.json) straight
 * off disk via the Android-free File loaders on Trie/BigramModel, and
 * exercises the exact same BigramAwareSuggestionStrategy(MergedSuggestionStrategy(...))
 * pipeline CodeKeyboardIME wires up — with an empty UserTrie, simulating a
 * fresh install (no learned words yet).
 *
 * Two fixtures, two different guarantees:
 *   - autocomplete_eval_cases.tsv            — hand-curated, each case lists
 *     every acceptable completion (pipe-separated). Precise but small and
 *     author-biased (I picked both the sentence and what counts as correct).
 *   - autocomplete_eval_cases_generated.tsv  — sampled from real public-domain
 *     / permissively-licensed text (see scripts/gen_autocomplete_corpus.py),
 *     ground truth is the single word that actually followed in the source
 *     text. Bigger and unbiased, but a miss can be a legitimate synonym
 *     rather than a real failure — so this one is report-only, no baseline gate.
 */
class AutocompleteEvalTest {

    companion object {
        // Last checkpointed pass rate for the curated fixture (see ADR-007
        // checkpoint log). Update this only when deliberately improving the
        // algorithm and re-checkpointing — a drop without an explanation is
        // a regression.
        // Checkpoint #1 (initial harness, no tuning yet): 43/54 = 79.6% overall
        // (next-word 100%, prefix 100%, prefix+context 100%, next-word-phrase
        // 42.1% — the 1-word bigram context gap). Small margin below actual so
        // unrelated noise doesn't flake the build; bump deliberately on real gains.
        private const val CURATED_BASELINE_PASS_RATE = 0.75

        // A case "passes" if the expected word appears anywhere in the top-K
        // suggestions returned for its prefix+context.
        private const val TOP_K = 5
    }

    // [expected] can hold multiple acceptable completions (pipe-separated in the
    // fixture, e.g. "forward|towards|at") — real language often has more than
    // one reasonable next word, and a strict single-answer check would report
    // false failures for cases the suggester is actually handling fine.
    private data class Case(val sentence: String, val expected: List<String>, val category: String)
    private data class Result(val case: Case, val suggestions: List<String>, val passed: Boolean)

    private fun loadCases(resourceName: String): List<Case> {
        val file = File("src/test/resources/$resourceName")
        return file.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .map { line ->
                val parts = line.split("\t")
                Case(sentence = parts[0], expected = parts[1].split("|"), category = parts[2])
            }
    }

    // Mirrors how CodeKeyboardIME derives (context, prefix) from typed text:
    // context = the last *completed* word, prefix = the partial word in progress.
    private fun splitSentence(sentence: String): Pair<String, String> {
        val lastSpace = sentence.lastIndexOf(' ')
        if (lastSpace < 0) return "" to sentence
        val prefix = sentence.substring(lastSpace + 1)
        val rest = sentence.substring(0, lastSpace)
        val context = rest.substringAfterLast(' ')
        return context to prefix
    }

    /**
     * Pack-backed strategy — the CKLM path that CodeKeyboardIME now wires up
     * (ADR-010 task I). Loads en.cklm from the real asset path, builds
     * WordDictionary + PackNgramModel(order=2) + PackBackedBigramModel, and
     * exercises the same suggestion pipeline. This is the production path;
     * the legacy en.trie/bigrams.json path is being removed (tasks L/M).
     *
     * This is the integration gate that catches wiring bugs the component
     * tests miss: a missing/misplaced en.cklm asset, or a score-blend
     * regression in PackBackedBigramModel, both surface as a pass-rate drop.
     */
    private fun buildPackStrategy(): SuggestionStrategy {
        val pack = LanguagePack.open(File("src/main/assets/en.cklm"))
        val wordDict = WordDictionary(pack)
        val userTrie = UserTrie() // fresh install — no learned words
        val userAdapter = UserTrieAdapter(userTrie)
        val baseAdapter = wordDict.adapter
        // Empty user layer — fresh install. Seed comes from the pack (BigramModel's
        // seed path was removed, ADR-010 tasks L/M).
        val userBigram = BigramModel(
            File.createTempFile("eval_user_bigrams", ".json").apply { delete() },
        )
        userBigram.loadUserLayer()
        val packBigram = PackBackedBigramModel(PackNgramModel(pack, order = 2), userBigram)
        return BigramAwareSuggestionStrategy(MergedSuggestionStrategy(userAdapter, baseAdapter, wordDict), packBigram)
    }

    private fun runEval(strategy: SuggestionStrategy, cases: List<Case>): List<Result> =
        cases.map { case ->
            val (context, prefix) = splitSentence(case.sentence)
            val suggestions = strategy.suggest(prefix, TOP_K, context = context)
            Result(case, suggestions, case.expected.any { it in suggestions })
        }

    private fun printReport(title: String, results: List<Result>, baseline: Double?) {
        val overallPassRate = results.count { it.passed }.toDouble() / results.size

        println("=== $title ===")
        val baselineNote = if (baseline != null) ", baseline=${baseline * 100}%" else " (report-only, no baseline gate)"
        println("Overall: ${results.count { it.passed }}/${results.size} " +
            "(${"%.1f".format(overallPassRate * 100)}%)$baselineNote")

        results.groupBy { it.case.category }.forEach { (category, group) ->
            val passed = group.count { it.passed }
            val rate = passed.toDouble() / group.size
            println("  [$category] $passed/${group.size} (${"%.1f".format(rate * 100)}%)")
        }

        // Diagnostic split: "next-word" cases (nothing typed, pure context
        // prediction — the bigram model) vs "prefix" cases (partial word
        // typed, mostly a trie prefix search problem). These stress
        // different parts of the pipeline, so a low overall rate can hide
        // one being fine and the other being the real bottleneck.
        results.groupBy { if (splitSentence(it.case.sentence).second.isEmpty()) "next-word" else "prefix" }
            .forEach { (type, group) ->
                val passed = group.count { it.passed }
                val rate = passed.toDouble() / group.size
                println("  (by-type) [$type] $passed/${group.size} (${"%.1f".format(rate * 100)}%)")
            }

        println("--- Failures ---")
        results.filterNot { it.passed }.forEach { r ->
            println("  \"${r.case.sentence}\" -> expected any of ${r.case.expected}, " +
                "got ${r.suggestions} [${r.case.category}]")
        }
    }

    @Test fun `report autocomplete accuracy against checkpointed baseline`() {
        val strategy = buildPackStrategy()
        val cases = loadCases("autocomplete_eval_cases.tsv")
        val results = runEval(strategy, cases)
        printReport("Curated eval report", results, CURATED_BASELINE_PASS_RATE)

        val overallPassRate = results.count { it.passed }.toDouble() / results.size
        assertTrue(
            "Autocomplete pass rate $overallPassRate dropped below checkpointed baseline " +
                "$CURATED_BASELINE_PASS_RATE — see printed report above for regressions",
            overallPassRate >= CURATED_BASELINE_PASS_RATE
        )
    }

    @Test fun `report autocomplete accuracy against real-text generated corpus`() {
        val strategy = buildPackStrategy()
        val cases = loadCases("autocomplete_eval_cases_generated.tsv")
        val results = runEval(strategy, cases)
        printReport("Generated-corpus eval report (single-ground-truth, no gate)", results, null)
        // No assertion here on purpose — see class kdoc: a miss can be a
        // legitimate synonym of the real source-text word, not a real defect.
    }

    /**
     * Pack-backed eval (ADR-010 task J gate): run the same curated fixture
     * through the CKLM pack path and assert it meets the same checkpointed
     * baseline as the legacy path. This is the regression gate that must be
     * green before removing the legacy assets (tasks L/M).
     */
    @Test fun `pack-backed autocomplete accuracy meets checkpointed baseline`() {
        val strategy = buildPackStrategy()
        val cases = loadCases("autocomplete_eval_cases.tsv")
        val results = runEval(strategy, cases)
        printReport("Pack-backed curated eval report", results, CURATED_BASELINE_PASS_RATE)

        val overallPassRate = results.count { it.passed }.toDouble() / results.size
        assertTrue(
            "Pack-backed autocomplete pass rate $overallPassRate dropped below checkpointed baseline " +
                "$CURATED_BASELINE_PASS_RATE — see printed report above for regressions",
            overallPassRate >= CURATED_BASELINE_PASS_RATE
        )
    }
}
