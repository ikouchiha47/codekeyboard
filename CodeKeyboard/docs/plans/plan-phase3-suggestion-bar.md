# Phase 3: SuggestionBarView — Detailed Breakdown

## What this phase delivers

A native Android `LinearLayout` inserted above the keyboard that shows up to 3 word
completions from the `Trie`. Tapping a slot replaces the current composing word.
Updates synchronously after every character typed — no bridge, no async.

The existing `SuggestionBar.tsx` is **not deleted** in this phase. It stays active
for the RN preview text field (in-app mode). It is silenced in IME mode by
removing the `<SuggestionBar>` render from `Keyboard.tsx` only when `isIME` is
true. The component file itself is not touched.

---

## Current state (what exists)

### RN `SuggestionBar.tsx`

- A `flexDirection: row` bar, `minHeight: 34`, `backgroundColor: #1e1e1e`
- Three dynamic slots rendered from a `suggestions: string[]` prop
- Slot 0 styled differently ("best" pill): `backgroundColor: #1a3a5c`, text `#4a9eff`
- Other slots: no background, text `#999`
- Empty state: renders a `<View style={styles.placeholder} />` (invisible spacer)
- Wired via `applySuggestion` in `Keyboard.tsx` which replaces the word in RN state

### `Keyboard.tsx` rendering (IME mode)

```tsx
// Currently always renders the bar, even in IME mode where it does nothing:
<SuggestionBar suggestions={suggestions} onSelect={applySuggestion} />
```

In IME mode (`isIME === true`), `suggestions` is always `[]` because
`updateSuggestions` is never called — `commitText` in the native layer bypasses
RN state entirely. So the bar shows empty in IME mode. It takes up 34px for nothing.

### `CodeKeyboardIME.onCreateInputView()`

Currently builds:
```
LinearLayout (wrapper, vertical, #111111)
  └── NativeKeyboardView
```

No suggestion bar exists in the native layer at all.

---

## View hierarchy after this phase

```
LinearLayout (wrapper, vertical, #111111, MATCH_PARENT × WRAP_CONTENT)
  ├── SuggestionBarView  (MATCH_PARENT × 40dp)
  └── NativeKeyboardView (MATCH_PARENT × WRAP_CONTENT)
```

### `SuggestionBarView` internal layout

```
LinearLayout (horizontal, MATCH_PARENT × 40dp, #1e1e1e, borderBottom 1dp #333)
  ├── TextView slot0  (weight=1, #4a9eff text, #1a3a5c bg, 4dp corner radius)
  ├── View divider    (1dp wide, #333, 28dp tall, vertically centered)
  ├── TextView slot1  (weight=1, #999 text, transparent bg)
  ├── View divider    (1dp wide, #333, 28dp tall, vertically centered)
  └── TextView slot2  (weight=1, #999 text, transparent bg)
```

All three slots always present. Empty slots show empty string (no text), no visual
change except the slot0 highlight disappears.

### Dimensions (match the RN bar exactly)

| Property | Value |
|---|---|
| Bar height | 40dp (RN has minHeight 34 + paddingVertical 4 each side = ~42; use 40dp flat) |
| Horizontal padding | 8dp each side |
| Vertical padding | 6dp each side (centers text in 40dp) |
| Slot font size | 14sp, monospace (`Typeface.MONOSPACE`) |
| Slot0 bg | `#1a3a5c`, corner radius 4dp |
| Slot0 text color | `#4a9eff` |
| Slot1/2 text color | `#999999` |
| Bar background | `#1e1e1e` |
| Bottom border | 1dp, `#333333` (drawn in `onDraw`, not a child View) |
| Dividers | 1dp wide, `#333333`, height 28dp, `gravity: center_vertical` |

---

## Empty state

When `composing.text` is empty (no word in flight):
- All three `TextView` slots get `text = ""`
- Slot0 background set to transparent (no blue highlight when empty)
- Bar stays at full 40dp height — layout does NOT collapse
- This matches the RN placeholder behaviour (empty spacer, same height)

When composing starts:
- Slot0 shows the current composing word (what is being typed, even without a
  completion — this acts as a "confirm as-is" button)
- Slots 1 and 2 show `trie.suggest(word, 3)` results 0 and 1 (i.e., the top
  2 completions after the current word itself)
- If suggest returns fewer than 2 results, remaining slots stay empty

Wait — should slot0 be the current composing word or the top suggestion?

**Decision: slot0 = top trie completion (or composing word if no suggestions).**

Rationale: the most common user intent on a suggestion bar is to pick the top
completion. Showing the composing word in slot0 only makes sense if the user
wants to confirm what they typed exactly, but that happens automatically on
space/punctuation. Mimicking standard Android IME behaviour: slot0 = best guess.

Revised slot assignment:
- Get `suggestions = trie.suggest(word, 3)` (up to 3 completions)
- Slot0 = `suggestions[0]` if non-empty, else `word` (composing as-is)
- Slot1 = `suggestions[1]` if present, else `""`
- Slot2 = `suggestions[2]` if present, else `""`
- Slot0 gets blue highlight only when `suggestions.isNotEmpty()`

---

## onConfirm and onSelect — tap handling

### User taps slot0

```
if suggestions[0] exists:
    clearComposingText()       // discard the underlined composing region
    commitText(suggestions[0] + " ", 1)  // commit top suggestion + space
    composing.clear()
    kbState.onCharCommitted()
else if composing.text.isNotEmpty():
    flushComposing()           // commit what is typed as-is
    commitText(" ", 1)         // then commit space
```

This matches the behaviour of tapping the top suggestion on the Gboard suggestion
bar (commit word + advance cursor past space).

### User taps slot1 or slot2

Same as slot0 but with `suggestions[1]` or `suggestions[2]`:
```
clearComposingText()
commitText(suggestions[slotIndex] + " ", 1)
composing.clear()
kbState.onCharCommitted()
updateSuggestionBar()  // now all slots empty
```

### Update trigger

Called from two places in `handleKey`:

1. After `appendComposing` (letter typed):
```kotlin
val word = composing.text
val suggestions = trie.suggest(word, 3)
suggestionBar.update(word, suggestions)
```

2. After `flushComposing` (space/enter/punctuation typed — bar clears):
```kotlin
suggestionBar.clear()
```

3. After backspace modifies composing:
```kotlin
suggestionBar.update(composing.text, trie.suggest(composing.text, 3))
```

### No threading

`trie.suggest()` is ~2–4µs. Called on the main thread inline with `handleKey`.
No coroutine, no Handler.post, no async. Direct call, immediate update.

---

## What changes in `Keyboard.tsx` (RN)

One targeted change: suppress the `<SuggestionBar>` render in IME mode.

```tsx
// Before:
<SuggestionBar suggestions={suggestions} onSelect={applySuggestion} />

// After:
{!isIME && (
  <SuggestionBar suggestions={suggestions} onSelect={applySuggestion} />
)}
```

This is the **only** change to `Keyboard.tsx`. The `SuggestionBar.tsx` component
file is untouched. The `applySuggestion` and `updateSuggestions` functions stay
in `Keyboard.tsx` unchanged — they still drive the in-app preview mode.

The `{!isIME}` guard also covers the existing `<View style={styles.outputArea}>` /
`TextInput` block — that block already has its own `!isIME` guard, so this is
consistent.

---

## Files that change

### 1. New: `SuggestionBarView.kt`

Location: `android/app/src/main/java/com/codekeyboard/SuggestionBarView.kt`

```kotlin
class SuggestionBarView(context: Context) : LinearLayout(context) {

    private val slots: Array<TextView>
    private var currentSuggestions: List<String> = emptyList()
    var onSlotTapped: ((Int, String) -> Unit)? = null

    init {
        orientation = HORIZONTAL
        setBackgroundColor(Color.parseColor("#1e1e1e"))
        val dp = context.resources.displayMetrics.density

        val slotColors = intArrayOf(
            Color.parseColor("#4a9eff"),
            Color.parseColor("#999999"),
            Color.parseColor("#999999"),
        )
        val slotBg = intArrayOf(
            Color.parseColor("#1a3a5c"),
            Color.TRANSPARENT,
            Color.TRANSPARENT,
        )

        slots = Array(3) { i ->
            val tv = TextView(context).apply {
                layoutParams = LayoutParams(0, LayoutParams.MATCH_PARENT, 1f)
                gravity = android.view.Gravity.CENTER
                setTextColor(slotColors[i])
                typeface = android.graphics.Typeface.MONOSPACE
                textSize = 14f
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                // Corner radius for slot0 via background drawable.
                if (i == 0) setBackground(roundedDrawable((4 * dp), slotBg[0]))
                setOnClickListener { onSlotTap(i) }
            }
            addView(tv)
            if (i < 2) addView(divider(context, dp))
            tv
        }
    }

    fun update(word: String, suggestions: List<String>) {
        currentSuggestions = suggestions
        val slot0word = suggestions.getOrNull(0) ?: word
        slots[0].text = if (word.isNotEmpty()) slot0word else ""
        slots[1].text = suggestions.getOrNull(1) ?: ""
        slots[2].text = suggestions.getOrNull(2) ?: ""
        // Highlight slot0 only when a real suggestion exists.
        slots[0].background = if (suggestions.isNotEmpty() && word.isNotEmpty()) {
            val dp = context.resources.displayMetrics.density
            roundedDrawable(4 * dp, Color.parseColor("#1a3a5c"))
        } else {
            null
        }
    }

    fun clear() {
        currentSuggestions = emptyList()
        slots.forEach { it.text = "" }
        slots[0].background = null
    }

    private fun onSlotTap(index: Int) {
        val word = slots[index].text.toString()
        if (word.isNotEmpty()) onSlotTapped?.invoke(index, word)
    }

    private fun roundedDrawable(radiusPx: Float, color: Int): android.graphics.drawable.Drawable {
        return android.graphics.drawable.GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
        }
    }

    private fun divider(context: Context, dp: Float): View {
        return View(context).apply {
            val widthPx = (1 * dp).toInt().coerceAtLeast(1)
            val heightPx = (28 * dp).toInt()
            layoutParams = LayoutParams(widthPx, heightPx).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
            }
            setBackgroundColor(Color.parseColor("#333333"))
        }
    }
}
```

### 2. `CodeKeyboardIME.kt`

**In `onCreateInputView()`** — add `SuggestionBarView` above `NativeKeyboardView`:

```kotlin
private lateinit var suggestionBar: SuggestionBarView

override fun onCreateInputView(): View {
    // ... existing keyboardView setup ...

    suggestionBar = SuggestionBarView(this).apply {
        val dp = resources.displayMetrics.density
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            (40 * dp).toInt()
        )
        onSlotTapped = { index, word -> handleSuggestionTap(index, word) }
    }

    wrapper.addView(suggestionBar, 0)  // insert before keyboardView (index 0)
    wrapper.addView(keyboardView)
    // ... rest unchanged ...
}
```

**New `handleSuggestionTap`:**

```kotlin
private fun handleSuggestionTap(index: Int, word: String) {
    val ic = currentInputConnection ?: return
    ic.finishComposingText()
    ic.commitText("$word ", 1)
    composing.clear()
    kbState.onCharCommitted()
    keyboardView.notifyStateChanged(kbState)
    suggestionBar.clear()
}
```

**After `appendComposing` in the character else branch** (after `ic.setComposingText`):

```kotlin
val suggestions = if (supportsComposing) trie.suggest(composing.text, 3) else emptyList()
suggestionBar.update(composing.text, suggestions)
```

**After `flushComposing()`** calls (space, enter, tab, modifier+key):

```kotlin
suggestionBar.clear()
```

**After backspace modifies composing buffer:**

```kotlin
composing.backspace() -> {
    ic?.setComposingText(composing.text, 1)
    suggestionBar.update(composing.text, trie.suggest(composing.text, 3))
}
```

**In `onFinishInput()`** — clear bar when field loses focus:

```kotlin
override fun onFinishInput() {
    super.onFinishInput()
    currentInputConnection?.finishComposingText()
    composing.clear()
    suggestionBar.clear()      // add this
}
```

**In `onStartInput()`** — clear on field switch:

```kotlin
composing.clear()
currentInputConnection?.finishComposingText()
suggestionBar.clear()          // add this
```

### 3. `Keyboard.tsx`

Single change — suppress `SuggestionBar` in IME mode:

```diff
-      <SuggestionBar suggestions={suggestions} onSelect={applySuggestion} />
+      {!isIME && (
+        <SuggestionBar suggestions={suggestions} onSelect={applySuggestion} />
+      )}
```

---

## Test cases

### New: `SuggestionBarViewTest.kt`

Pure JVM. `SuggestionBarView` depends on Android `Context` so these must be
**Robolectric** tests, or the logic is extracted.

Preferred approach: extract slot assignment logic into a pure function and test that.

```kotlin
// Pure function, no Android — test with JUnit 4
fun buildSlots(word: String, suggestions: List<String>): Triple<String, String, String> {
    val slot0 = if (word.isEmpty()) "" else (suggestions.getOrNull(0) ?: word)
    val slot1 = suggestions.getOrNull(1) ?: ""
    val slot2 = suggestions.getOrNull(2) ?: ""
    return Triple(slot0, slot1, slot2)
}
```

| Test | Input | Expected |
|------|-------|----------|
| Empty word | word="", suggestions=[] | ("", "", "") |
| Word, no suggestions | word="xyz", suggestions=[] | ("xyz", "", "") |
| Word, 1 suggestion | word="hel", suggestions=["help"] | ("help", "", "") |
| Word, 3 suggestions | word="hel", suggestions=["help","helps","helper"] | ("help","helps","helper") |
| Tap slot0 with suggestion | word in slot0 != composing word | commit suggestion + space |
| Tap slot0 no suggestion | slot0 == composing word | flush + space |
| Tap empty slot | slot text is "" | no-op |

### Existing tests to re-run (no changes expected)

- `ComposingBufferTest` — all 10 cases
- `TrieTest` — all 31 cases
- `KeyboardStateTest` — all existing cases

---

## What stays in RN (after this phase)

| Component | Status |
|---|---|
| `SuggestionBar.tsx` | Kept — drives in-app preview mode |
| `applySuggestion` in Keyboard.tsx | Kept — drives in-app preview mode |
| `updateSuggestions` in Keyboard.tsx | Kept |
| `Dictionary.ts`, `Trie.ts` | Kept — used by in-app preview mode |

The only RN change is the `{!isIME}` guard. Nothing is deleted.

---

## Open questions

**Q: Should the bar height shrink when `supportsComposing` is false (terminal/vim)?**

When `supportsComposing` is false, no composing happens, so suggestions are never
updated. The bar will always show empty. Options:
1. Hide bar entirely when `!supportsComposing` — set visibility GONE in `onStartInput`
2. Keep bar visible at full height but always empty — simple, no layout jump

**Decision: keep bar visible, always 40dp.** Hiding it would cause the keyboard
height to change between fields (a layout jump visible to the user). Empty bar is
stable. Revisit if users complain about wasted space in terminal mode.

**Q: Does tapping a suggestion commit a trailing space always?**

Yes — matches standard Android IME convention. The space saves one keystroke.
If the user is at end of sentence they can immediately backspace it.

---

## Definition of done

- [ ] `SuggestionBarView.kt` compiles, renders 3 slots above `NativeKeyboardView`
- [ ] Bar appears in IME mode (verified on device or emulator)
- [ ] Typing letters populates suggestions from trie
- [ ] Tapping a slot replaces the composing word and commits with trailing space
- [ ] Empty state renders correctly (no highlight, no text, same height)
- [ ] Backspace inside a composing word updates suggestions correctly
- [ ] Field switch (onStartInput) clears bar
- [ ] `Keyboard.tsx` no longer renders `SuggestionBar` in IME mode
- [ ] Slot logic unit tests pass
- [ ] All existing tests pass
