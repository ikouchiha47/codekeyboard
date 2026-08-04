# Phase 1: setComposingText / commitText — Detailed Breakdown

## What changes and why

Currently `handleKey` calls `ic?.commitText(char, 1)` for every letter keystroke.
This means:
- Suggestions can never update mid-word (nothing to read back until space)
- Backspace always hits the committed text (no composing buffer to modify)
- The target app sees each character as a final committed edit

After this change:
- Letters accumulate in a local `StringBuilder composing`
- `setComposingText(composing, 1)` is called after each letter — target app shows
  the in-progress word underlined, not committed
- Space / punctuation / enter flushes the buffer via `commitText`, clears composing
- Backspace deletes from composing first; falls through to committed text only when
  composing is empty

---

## Files that change

### 1. `CodeKeyboardIME.kt`

**Current relevant sections:**

| Location | Current behaviour | New behaviour |
|---|---|---|
| Class fields | none | add `private val composing = StringBuilder()` |
| `handleKey` default branch | `ic?.commitText(char, 1)` | `appendComposing(ic, char)` |
| `handleKey "backspace"` | `deleteSurroundingText(1,0)` | delete from composing first |
| `handleKey "space"` | `ic?.commitText(" ", 1)` | flush composing + space |
| `handleKey "enter"` | sends enter directly | flush composing first, then enter |
| `handleKey "tab"` | sends tab directly | flush composing first, then tab |
| Punctuation (`,./!?:;'"-`) | `commitText(char, 1)` | flush composing, then commit punctuation |
| `onFinishInput` | not overridden | override, call `finishComposing()` + clear buffer |
| `onStartInput` | sets inputConnection | also clear composing buffer |

**New private helpers to add:**

```kotlin
private fun appendComposing(ic: InputConnection?, char: String) {
    composing.append(char)
    ic?.setComposingText(composing, 1)
    kbState.onCharCommitted()
    // Phase 3 hook: suggestionBar.update(composing.toString(), trie.suggest(...))
}

private fun flushComposing(ic: InputConnection?) {
    if (composing.isNotEmpty()) {
        ic?.commitText(composing.toString(), 1)
        composing.clear()
        ic?.finishComposingText()
    }
}

private fun clearComposing(ic: InputConnection?) {
    composing.clear()
    ic?.finishComposingText()
}
```

**Punctuation that should flush (exhaustive list):**
`. , ! ? : ; ' " ( ) [ ] { } / \ - _ = + * & ^ % $ # @ ~ ` |`
These are in the default key data — when action is null and label is one of these,
flush composing first then commit the punctuation character.

**Modifier + character behaviour:**
When a modifier (ctrl/alt/meta) is active, the key goes via `sendKeyEvent` not
`commitText`. In this case: flush composing first, then send the key event.
Shift is NOT a modifier for this purpose — shift just changes the character committed.

---

### 2. `KeyboardState.kt`

No structural changes. `onCharCommitted()` is already called correctly for
latch/layer cycling. Verify it is still called in `appendComposing`.

---

### 3. `SofleKeyData.kt`

No changes. Key definitions (action, label, shift) are unchanged.

---

## Test cases needed

### New test file: `ComposingBufferTest.kt`

These are **pure unit tests** — no Android framework needed. Extract composing
buffer logic into a testable class `ComposingBuffer` so it can be tested without
`InputConnection` or Android context.

```kotlin
class ComposingBuffer {
    private val buf = StringBuilder()
    val text: String get() = buf.toString()
    val isEmpty: Boolean get() = buf.isEmpty()

    fun append(char: String): String { buf.append(char); return buf.toString() }
    fun backspace(): Boolean {        // returns true if buffer handled it
        if (buf.isEmpty()) return false
        buf.deleteCharAt(buf.length - 1)
        return true
    }
    fun flush(): String { val t = buf.toString(); buf.clear(); return t }
    fun clear() { buf.clear() }
}
```

**Test cases:**

| Test | Input sequence | Expected |
|---|---|---|
| append builds word | append("h","e","l","l","o") | text == "hello" |
| backspace removes last char | append("hel"), backspace | text == "he" |
| backspace on empty returns false | backspace() | returns false, text == "" |
| backspace to empty | append("a"), backspace | text == "", isEmpty == true |
| flush returns text and clears | append("hello"), flush | returns "hello", isEmpty == true |
| flush on empty returns empty string | flush() | returns "" |
| clear empties buffer | append("hello"), clear | isEmpty == true |
| append after flush starts fresh | append("hi"), flush, append("bye") | text == "bye" |
| backspace does not go below empty | backspace x3 on empty | no crash, returns false each time |
| append punctuation | append("!") | text == "!" (punctuation treated as char) |

### Existing tests to re-verify (not modify, just re-run)

| Test file | Why it may be affected |
|---|---|
| `KeyboardStateTest.kt` — `latched layer returns to base after char committed` | `onCharCommitted()` must still be called in `appendComposing` |
| `KeyboardStateTest.kt` — `latched shift clears after char committed` | Same — shift latch must clear on char commit |
| `KeyboardStateTest.kt` — `latched ctrl clears after char committed` | Same |
| `KeyboardStateTest.kt` — `metaState clears latched ctrl after char committed` | Same |

These tests don't test `CodeKeyboardIME` directly (no Android context) so they
won't break — but they prove the invariant that `onCharCommitted()` must be called.
If `appendComposing` forgets to call it, these tests still pass but the keyboard
behaviour breaks. Add a note in the test file to this effect.

### Integration behaviour to manually verify on device

| Scenario | Expected |
|---|---|
| Type "hello" in Termux | Each char appears underlined, committed on space |
| Type "hello " | "hello " committed, composing cleared |
| Type "helo" + backspace | "hel" in composing (underlined) |
| Type "helo" + backspace + "lo" | "hello" in composing |
| Backspace past start of word | Deletes from committed text (one char) |
| Ctrl+S while composing | Flush "hello", then send Ctrl+S |
| Switch apps mid-compose | Composing cleared, no ghost text |
| Type in vim (normal mode) | Each char commits immediately — composing must not interfere with key events sent to vim |

---

## Open question: terminals and vim

`setComposingText` shows underlined text in the target app. Some apps (Termux,
vim, code editors via terminal) do NOT support `InputConnection` and instead
receive raw key events. In these apps, `setComposingText` may do nothing or
behave oddly.

**Detection:** `EditorInfo.inputType == InputType.TYPE_NULL` indicates the field
does not support composing. In this case, fall back to `commitText` per character
(current behaviour).

Add to `onStartInput`:
```kotlin
val noComposing = editorInfo?.inputType == InputType.TYPE_NULL
```

Pass this flag to `appendComposing` — if `noComposing`, skip `setComposingText`
and call `commitText(char, 1)` directly. This is effectively the coding mode
passthrough, implemented cleanly without a separate mode toggle.

---

## Definition of done

- [ ] `ComposingBuffer` class extracted and unit tested (all 10 cases pass)
- [ ] `CodeKeyboardIME` uses composing buffer for letter keys
- [ ] Existing `KeyboardStateTest` all pass unchanged
- [ ] `TYPE_NULL` detection skips composing (terminal/vim passthrough)
- [ ] Manual verify: composing underline visible in Chrome/WhatsApp
- [ ] Manual verify: backspace inside composing word works
- [ ] Manual verify: modifier + key flushes composing first
- [ ] Manual verify: switching fields clears composing
