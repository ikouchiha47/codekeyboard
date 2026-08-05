# Phase 3: SuggestionBarView — Detailed Breakdown

## What this phase delivers

A native Android `LinearLayout` inserted above the keyboard in `onCreateInputView()`
that shows up to 3 word completions from the `Trie`. Tapping a slot replaces the
current composing word. Updates synchronously after every character typed.

**This phase is 100% Kotlin / Android.** No RN code changes. The `SuggestionBarView`
is part of the IME surface — it lives inside `CodeKeyboardIME.onCreateInputView()`.
When the keyboard appears in WhatsApp, Chrome, or any other app, the entire keyboard
UI is the native view returned by `onCreateInputView()`. RN is not involved in that
surface at all.

The `SuggestionBar.tsx` component belongs to the CodeKeyboard **app's own preview
screen** — a completely separate surface. It is not touched in this phase.

---

## Two surfaces — why they don't interact

```
┌─────────────────────────────────────────────────────────┐
│  CodeKeyboard app (RN surface — in-app preview)         │
│                                                         │
│  App.tsx → Keyboard.tsx → SuggestionBar.tsx             │
│  Driven by local RN state (text, suggestions[])         │
│  Shown only inside the CodeKeyboard app itself          │
└─────────────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────────────┐
│  IME surface (Kotlin — system keyboard in other apps)   │
│                                                         │
│  CodeKeyboardIME.onCreateInputView()                    │
│    └── LinearLayout wrapper                             │
│          ├── SuggestionBarView   ← built in this phase  │
│          └── NativeKeyboardView                         │
│  Driven by composing buffer + Trie                      │
│  Shown in WhatsApp, Chrome, Termux, any other app       │
└─────────────────────────────────────────────────────────┘
```

---

## Current state of the IME surface

`CodeKeyboardIME.onCreateInputView()` currently builds:

```
LinearLayout (wrapper, vertical, #111111)
  └── NativeKeyboardView
```

No suggestion bar exists. Adding `SuggestionBarView` above `NativeKeyboardView` is
the entirety of the UI change.

---

## View hierarchy after this phase

```
LinearLayout (wrapper, vertical, #111111, MATCH_PARENT × WRAP_CONTENT)
  ├── SuggestionBarView  (MATCH_PARENT × 40dp)
  └── NativeKeyboardView (MATCH_PARENT × WRAP_CONTENT)
```

### `SuggestionBarView` internal layout

```
LinearLayout (horizontal, MATCH_PARENT × 40dp, #1e1e1e)
  ├── TextView slot0  (weight=1, centered, #4a9eff text, #1a3a5c bg, 4dp radius)
  ├── View divider    (1dp wide, #333333, 28dp tall, gravity=center_vertical)
  ├── TextView slot1  (weight=1, centered, #999999 text, transparent bg)
  ├── View divider    (1dp wide, #333333, 28dp tall, gravity=center_vertical)
  └── TextView slot2  (weight=1, centered, #999999 text, transparent bg)
```

All three slots always present and always the same height. Empty slots show `""`.

### Dimensions

Derived from `SuggestionBar.tsx` styles (minHeight 34 + paddingVertical 4+4 = 42;
using 40dp flat for the native bar — cleaner integer, close enough):

| Property | Value | Source |
|---|---|---|
| Bar height | 40dp | matches RN minHeight≈42 |
| Horizontal padding | 8dp | matches RN paddingHorizontal 8 |
| Vertical padding (slot text) | 6dp | centers in 40dp |
| Font size | 14sp, `Typeface.MONOSPACE` | matches RN fontSize 14, fontFamily monospace |
| Slot0 background | `#1a3a5c`, corner 4dp | matches RN `best` style |
| Slot0 text color | `#4a9eff` | matches RN bestText |
| Slot1/2 text color | `#999999` | matches RN pillText |
| Bar background | `#1e1e1e` | matches RN bar bg |
| Bottom border | 1dp `#333333` drawn in `onDraw` | matches RN borderBottomColor |
| Dividers | 1dp × 28dp, `#333333` | not in RN (native addition for clarity) |

---

## Slot assignment logic

```
suggestions = trie.suggest(composing.text, 3)   // up to 3

slot0 = suggestions[0]  if suggestions non-empty
        composing.text  if suggestions empty and composing non-empty
        ""              if composing empty

slot1 = suggestions[1]  if present, else ""
slot2 = suggestions[2]  if present, else ""

slot0 blue highlight = suggestions.isNotEmpty() && composing.isNotEmpty()
```

Slot0 shows the best trie completion. If the trie has no completions for the current
prefix (rare — short/unusual prefix), slot0 falls back to showing the composing word
as-is so the user can still tap to confirm it. Space/punctuation/enter already commit
the composing word without tapping; the slot0 fallback exists purely for edge cases.

---

## Empty state

When composing buffer is empty (no word in flight):
- All three slots: `text = ""`
- Slot0 background: `null` (transparent — no blue pill)
- Bar height: stays 40dp — layout does NOT collapse

`!supportsComposing` fields (Termux, vim, password, numeric): bar stays visible at
full 40dp, always empty. This is intentional — hiding the bar would cause a layout
jump when the user switches between a text field and a terminal. The bar being empty
in those fields is the correct pass-through behaviour.

---

## Tap handling

### Any slot tap

```kotlin
fun handleSuggestionTap(word: String) {
    val ic = currentInputConnection ?: return
    ic.finishComposingText()          // clear the underlined composing region
    ic.commitText("$word ", 1)        // commit chosen word + trailing space
    composing.clear()
    kbState.onCharCommitted()
    keyboardView.notifyStateChanged(kbState)
    suggestionBar.clear()
}
```

Trailing space is always committed — matches standard Android IME convention.
If the user needs no space (end of sentence) they tap backspace once.

Empty slots (`text == ""`) do nothing — the `onSlotTapped` callback is not invoked
when the slot text is empty.

---

## Update triggers in `CodeKeyboardIME`

### After letter typed (in character `else` branch, after `setComposingText`)

```kotlin
if (supportsComposing && text.length == 1 && !isPunctuation(text[0])) {
    val word = composing.append(text)
    ic?.setComposingText(word, 1)
    suggestionBar.update(composing.text, trie.suggest(composing.text, 3))
}
```

### After backspace inside composing word

```kotlin
composing.backspace() -> {
    ic?.setComposingText(composing.text, 1)
    suggestionBar.update(composing.text, trie.suggest(composing.text, 3))
}
```

### After flush (space / enter / tab / punctuation / modifier+key)

`flushComposing()` already calls `composing.flush()`. After the flush:

```kotlin
suggestionBar.clear()
```

### On field switch (`onStartInput`) and field loss (`onFinishInput`)

```kotlin
composing.clear()
currentInputConnection?.finishComposingText()
suggestionBar.clear()
```

### No threading

`trie.suggest()` is ~2–4µs. All calls happen on the main thread inline with
`handleKey`. No `Handler.post`, no coroutine.

---

## Files that change

### 1. New: `SuggestionBarView.kt`

```kotlin
class SuggestionBarView(context: Context) : LinearLayout(context) {

    private val slots: Array<TextView>
    var onSlotTapped: ((String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.parseColor("#1e1e1e"))
        val dp = context.resources.displayMetrics.density

        slots = Array(3) { i ->
            val tv = TextView(context).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                gravity = android.view.Gravity.CENTER
                setTextColor(if (i == 0) Color.parseColor("#4a9eff") else Color.parseColor("#999999"))
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                setOnClickListener { dispatchTap(i) }
            }
            addView(tv)
            if (i < 2) addView(makeDivider(context, dp))
            tv
        }
    }

    fun update(word: String, suggestions: List<String>) {
        val dp = context.resources.displayMetrics.density
        slots[0].text = if (word.isNotEmpty()) suggestions.getOrNull(0) ?: word else ""
        slots[1].text = suggestions.getOrNull(1) ?: ""
        slots[2].text = suggestions.getOrNull(2) ?: ""
        slots[0].background = if (suggestions.isNotEmpty() && word.isNotEmpty())
            makeRounded(4 * dp, Color.parseColor("#1a3a5c")) else null
    }

    fun clear() {
        slots.forEach { it.text = ""; it.background = null }
        // Reapply null individually so slot1/2 (which never had a background) are clean
        slots[0].background = null
    }

    private fun dispatchTap(index: Int) {
        val word = slots[index].text.toString()
        if (word.isNotEmpty()) onSlotTapped?.invoke(word)
    }

    private fun makeRounded(radiusPx: Float, color: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            setColor(color); cornerRadius = radiusPx
        }

    private fun makeDivider(context: Context, dp: Float) = View(context).apply {
        layoutParams = LayoutParams(
            (1 * dp).toInt().coerceAtLeast(1),
            (28 * dp).toInt()
        ).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        setBackgroundColor(Color.parseColor("#333333"))
    }
}
```

### 2. `CodeKeyboardIME.kt`

Add field:
```kotlin
private lateinit var suggestionBar: SuggestionBarView
```

In `onCreateInputView()`, add `suggestionBar` before `keyboardView`:
```kotlin
suggestionBar = SuggestionBarView(this).apply {
    layoutParams = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        (40 * resources.displayMetrics.density).toInt()
    )
    onSlotTapped = { word -> handleSuggestionTap(word) }
}
wrapper.addView(suggestionBar)   // first child → above keys
wrapper.addView(keyboardView)
```

Add handler:
```kotlin
private fun handleSuggestionTap(word: String) {
    val ic = currentInputConnection ?: return
    ic.finishComposingText()
    ic.commitText("$word ", 1)
    composing.clear()
    kbState.onCharCommitted()
    keyboardView.notifyStateChanged(kbState)
    suggestionBar.clear()
}
```

Wire `suggestionBar.update()` and `suggestionBar.clear()` at the call sites described
in the Update triggers section above.

---

## Test cases

### New: `SuggestionSlotLogicTest.kt` (pure JVM, no Android)

Extract the slot assignment into a standalone pure function so it is testable without
`Context` or Robolectric:

```kotlin
fun buildSlots(word: String, suggestions: List<String>): Triple<String, String, String> {
    val s0 = if (word.isNotEmpty()) suggestions.getOrNull(0) ?: word else ""
    val s1 = suggestions.getOrNull(1) ?: ""
    val s2 = suggestions.getOrNull(2) ?: ""
    return Triple(s0, s1, s2)
}
```

| Test | `word` | `suggestions` | Expected triple |
|------|--------|---------------|-----------------|
| Empty word | `""` | `[]` | `("", "", "")` |
| Word, no suggestions | `"xyz"` | `[]` | `("xyz", "", "")` |
| Word, 1 suggestion | `"hel"` | `["help"]` | `("help", "", "")` |
| Word, 2 suggestions | `"hel"` | `["help","helps"]` | `("help", "helps", "")` |
| Word, 3 suggestions | `"hel"` | `["help","helps","helper"]` | `("help", "helps", "helper")` |
| Word equals suggestion | `"help"` | `["help"]` | `("help", "", "")` |
| Empty word non-empty suggestions | `""` | `["help"]` | `("", "", "")` |

### Existing tests — no changes expected

- `ComposingBufferTest` — 10 cases
- `TrieTest` — 31 cases
- `TrieBenchmark` — 18 benchmarks
- `KeyboardStateTest` — all existing

---

## Definition of done

- [ ] `SuggestionBarView.kt` compiles
- [ ] Bar appears above keys in IME mode on device or emulator
- [ ] Typing letters updates suggestion slots from trie
- [ ] Tapping a slot commits that word + space, clears composing, clears bar
- [ ] Empty slot tap is a no-op
- [ ] Empty state: 40dp bar, no text, no blue highlight
- [ ] Backspace inside composing word updates bar
- [ ] `onStartInput` and `onFinishInput` clear bar
- [ ] `!supportsComposing` fields: bar visible, always empty, no crash
- [ ] `SuggestionSlotLogicTest` all cases pass
- [ ] All existing tests pass
