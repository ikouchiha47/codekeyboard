package com.codekeyboard

import org.junit.Assert.*
import org.junit.Test

/**
 * Reproduces the bug: holoj<space><bksp><bksp>g starts composing "g" instead of "holog".
 *
 * We can't instantiate CodeKeyboardIME (Android framework), but ComposingBuffer is
 * pure Kotlin. We simulate exactly what each handler does to the buffer, including
 * the onUpdateSelection clear that fires after recomposeWordAtCursor.
 */
class BackspaceRecomposeTest {

    // Simulates what recomposeWordAtCursor does to the buffer:
    //   reads fragment before cursor, calls finishComposingText + setComposingRegion,
    //   then composing.setText(fragment).
    // onUpdateSelection fires AFTER setComposingRegion — at that point candidatesStart
    // may be -1 (not yet reflecting new region), so the guard clears the buffer.
    private fun recomposeWordAtCursor(composing: ComposingBuffer, textBeforeCursor: String) {
        val fragment = textBeforeCursor.takeLastWhile { it.isLetterOrDigit() || it == '\'' }
        println("  recomposeWordAtCursor: fragment='$fragment'")
        if (fragment.isEmpty()) return
        // finishComposingText clears composing region → triggers onUpdateSelection
        // with candidatesStart==-1 → our guard clears the buffer
        simulateOnUpdateSelectionAfterFinish(composing)
        // then setComposingRegion is called → setText
        composing.setText(fragment)
        println("  after recompose: composing='${composing.text}'")
    }

    // Simulates onUpdateSelection firing right after finishComposingText(),
    // before setComposingRegion is established. candidatesStart == -1 at this point.
    private fun simulateOnUpdateSelectionAfterFinish(composing: ComposingBuffer) {
        val candidatesStart = -1  // finishComposingText clears the composing region
        val cursorOutsideComposing = candidatesStart == -1
        if (composing.text.isNotEmpty() && cursorOutsideComposing) {
            println("  onUpdateSelection fired: candidatesStart=$candidatesStart → clearing composing buffer!")
            composing.clear()
        }
    }

    @Test fun `holoj space bksp bksp g — composing should be holog but is g`() {
        val composing = ComposingBuffer()
        var textBuffer = ""  // simulated editor content

        println("=== type 'holoj' ===")
        for (ch in "holoj") {
            composing.append(ch.toString())
            println("  append '$ch' → composing='${composing.text}'")
        }
        assertEquals("holoj", composing.text)

        println("\n=== space → flushComposing ===")
        val flushed = composing.flush()
        textBuffer += "$flushed "
        println("  flushed='$flushed', textBuffer='$textBuffer'")
        assertEquals("", composing.text)

        println("\n=== backspace #1 (space is committed, composing empty) ===")
        val backspace1InComposing = composing.backspace()
        println("  composing.backspace() = $backspace1InComposing (false = goes to else branch)")
        assertFalse("composing should be empty, backspace returns false", backspace1InComposing)
        // else branch: deleteSurroundingText removes the space, then recomposeWordAtCursor
        textBuffer = textBuffer.dropLast(1)  // delete the space
        println("  textBuffer after delete space='$textBuffer'")
        recomposeWordAtCursor(composing, textBuffer)

        println("\n=== composing state after bksp #1 ===")
        println("  composing='${composing.text}'")
        // BUG: onUpdateSelection fires between finishComposingText and setComposingRegion.
        // Depending on timing, composing may be cleared. The setText after should recover it.
        assertEquals("after bksp #1, composing should be 'holoj'", "holoj", composing.text)

        println("\n=== backspace #2 (composing='holoj', removes j) ===")
        val backspace2InComposing = composing.backspace()
        println("  composing.backspace() = $backspace2InComposing")
        assertTrue("composing non-empty, backspace returns true", backspace2InComposing)
        println("  composing after bksp #2='${composing.text}'")
        assertEquals("holo", composing.text)

        println("\n=== type 'g' ===")
        composing.append("g")
        println("  composing='${composing.text}'")

        println("\n=== EXPECTED: holog, ACTUAL: ${composing.text} ===")
        assertEquals("should be composing 'holog' for hologram suggestions", "holog", composing.text)
    }

    @Test fun `demonstrate the bug — onUpdateSelection clears buffer between finish and setComposingRegion`() {
        val composing = ComposingBuffer()

        // After space, composing is empty. First backspace goes to else branch.
        // recomposeWordAtCursor does:
        //   1. finishComposingText()       → onUpdateSelection(candidatesStart=-1) → composing.clear()
        //   2. setComposingRegion(s, e)    → onUpdateSelection(candidatesStart=s)  → no clear (composing empty)
        //   3. composing.setText("holoj")

        // Simulate step 1: finishComposingText while buffer is non-empty
        composing.setText("holoj")  // pretend we had state
        println("before finishComposingText: composing='${composing.text}'")

        // onUpdateSelection fires with candidatesStart=-1 (composing region gone)
        val candidatesStartAfterFinish = -1
        if (composing.text.isNotEmpty() && candidatesStartAfterFinish == -1) {
            composing.clear()
            println("onUpdateSelection(candidatesStart=-1): buffer CLEARED")
        }
        println("after onUpdateSelection: composing='${composing.text}'")

        // Step 2: setComposingRegion fires onUpdateSelection again, but composing is now empty → no-op guard
        // Step 3: setText restores it
        composing.setText("holoj")
        println("after setText('holoj'): composing='${composing.text}'")

        // So recomposeWordAtCursor itself is fine — buffer ends up correct.
        // The real question: does onUpdateSelection fire BETWEEN steps 1 and 3?
        // If yes: clear happens, then setText restores. Net effect: correct.
        // If the framework batches and fires AFTER step 3: clear wipes the restored value.
        assertEquals("holoj", composing.text)
        println()
        println("Conclusion: onUpdateSelection timing relative to setText determines the bug.")
        println("If framework fires onUpdateSelection AFTER composing.setText(), buffer gets wiped.")
    }
}
