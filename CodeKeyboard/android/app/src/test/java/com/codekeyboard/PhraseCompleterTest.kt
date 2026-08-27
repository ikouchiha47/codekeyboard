package com.codekeyboard

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PhraseCompleterTest {
    private val pack = LanguagePack.open(File("../../tmp/adr012/en_adr012.cklm"))
    private val completer = PhraseCompleter(pack)

    @Test fun `phrases extend a known context`() {
        val result = completer.complete(listOf("i", "like"), maxExtension = 2, n = 5)
        println("complete([i,like]) = $result")
        assertTrue("should find at least one phrase from 'i like'", result.isNotEmpty())
        // extension words should be non-empty and build the full phrase
        result.forEach { (ext, full, score) ->
            assertTrue("extension '$ext' not empty", ext.isNotBlank())
            assertTrue("full '$full' starts with 'i like'", full.startsWith("i like"))
            assertTrue("score $score > 0", score > 0f)
        }
    }

    @Test fun `unknown context returns empty`() {
        assertTrue(completer.complete(listOf("zzz", "qqq")).isEmpty())
    }

    @Test fun `empty context returns empty`() {
        assertTrue(completer.complete(emptyList()).isEmpty())
    }
}