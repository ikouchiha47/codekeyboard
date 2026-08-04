# Plan: Suggestions + Snippet Hotkeys

## Goal
Make word suggestions appear in IME mode, and add a snippet system for hotkeys
(email, address, links, form fill). Everything in Kotlin — no RN bridge involvement.

## Scope
- `setComposingText` plumbing — composing buffer per character, commit on word boundary
- Kotlin trie reader for `assets/en.trie` (port of `src/keyboard/Trie.ts`)
- `SuggestionBarView` — native Android view above the keyboard
- Snippet system — stored key/value pairs, `;` prefix triggers lookup

---

## Phase 1: setComposingText plumbing

### What it is
Currently `handleKey` calls `commitText(char, 1)` for every letter. Instead:
- Letters go into a composing buffer via `setComposingText(buffer, 1)`
- Space / punctuation / enter flushes: `commitText(buffer + trigger, 1)`
- Backspace deletes from composing buffer first, then committed text

### Files to change
- `CodeKeyboardIME.kt`
  - Add `private var composing = StringBuilder()`
  - In `handleKey`, default branch (letter keys): append to `composing`, call `setComposingText`
  - `"space"` branch: flush composing + space via `commitText`, clear `composing`
  - `"backspace"` branch: if `composing.isNotEmpty()` delete last char from buffer, else `deleteSurroundingText`
  - `"enter"` branch: flush composing first, then existing enter logic
  - Punctuation (`,.!?;:`): flush composing, then commit punctuation
  - `onFinishInput`: call `finishComposing()`, clear `composing`

### What to check
- Composing text shows underlined in the target app
- Backspace inside composing buffer works character by character
- Switching apps/fields clears composing correctly

---

## Phase 2: Kotlin Trie reader

### What it is
Port `src/keyboard/Trie.ts` to Kotlin. Read `assets/en.trie` (TRIE2 binary format).
Same binary format — 12-byte header, 8-byte nodes. No data changes needed.

### Files to create
- `android/app/src/main/java/com/codekeyboard/Trie.kt`
  - `class Trie(private val buf: ByteBuffer)`
  - `fun suggest(prefix: String, max: Int = 3): List<String>`
  - `fun has(word: String): Boolean`
  - Internal: `walk()`, `collect()`, `findChild()`, `isEnd()`, `hasChildren()`, `childrenOffset()`
  - Load from assets: `context.assets.open("en.trie").use { MappedByteBuffer or ByteArray }`

### Files to change
- `CodeKeyboardIME.kt`
  - Add `private lateinit var trie: Trie`
  - Load in `onCreate()`: `trie = Trie(assets.open("en.trie").readBytes().let { ByteBuffer.wrap(it) })`

### What to check
- `trie.suggest("hel")` returns `["hello", "help", "held"]` or similar
- Load time acceptable (measure in `onCreate`)
- Unit test: `Trie.kt` loaded from test assets, verify known words

---

## Phase 3: SuggestionBarView

### What it is
A native `LinearLayout` that sits above the keyboard in `onCreateInputView`.
Three slots:
- Slot 1: current composing word (tap = commit as-is, dismiss composing)
- Slot 2 & 3: trie suggestions (tap = replace composing with suggestion + space)

Updates on every `setComposingText` call.

### Files to create
- `android/app/src/main/java/com/codekeyboard/SuggestionBarView.kt`
  - Extends `LinearLayout`
  - `fun update(composing: String, suggestions: List<String>)`
  - Three `TextView` children, weighted equally
  - Tap on slot 1: `onConfirm(composing)` callback
  - Tap on slot 2/3: `onSelect(suggestion)` callback
  - Empty state: all slots blank (bar still occupies space, no layout jump)

### Files to change
- `CodeKeyboardIME.kt`
  - `onCreateInputView`: add `SuggestionBarView` above keyboard in the wrapper `LinearLayout`
  - Add `private lateinit var suggestionBar: SuggestionBarView`
  - Wire callbacks: confirm = `commitText(composing, 1); clearComposing()`
  - Wire callbacks: select = `commitText(suggestion + " ", 1); clearComposing()`
  - Call `suggestionBar.update(composing.toString(), trie.suggest(composing.toString()))` after every composing change

### What to check
- Bar appears above keyboard
- Updates in real time as user types
- Tap on suggestion replaces partial word correctly
- Bar height doesn't cause keyboard to jump

---

## Phase 4: Snippet system

### What it is
User-defined key/value pairs. Trigger: type `;` followed by a shortcode.
The suggestion bar shows matching snippets instead of word suggestions while composing
starts with `;`.

On tap: delete `;shortcode` from composing, `commitText(fullValue, 1)`.

### Storage
`SharedPreferences` — simple key/value. Key = shortcode (e.g. `em`), value = expansion
(e.g. `user@email.com`). Managed via a settings screen (later) or adb/file for now.

### Default snippets (seeded on first run)
```
em  → <user email>         (placeholder until user sets it)
ph  → <phone number>
addr → <address>
li  → <linkedin url>
gh  → <github url>
me  → <full name>
```

### Files to create
- `android/app/src/main/java/com/codekeyboard/SnippetStore.kt`
  - `object SnippetStore`
  - `fun init(context: Context)`
  - `fun get(shortcode: String): String?`
  - `fun set(shortcode: String, value: String)`
  - `fun matching(prefix: String): List<Pair<String, String>>` — returns [(shortcode, value)]
  - Backed by `SharedPreferences("snippets", MODE_PRIVATE)`

### Files to change
- `CodeKeyboardIME.kt`
  - `onCreate`: `SnippetStore.init(this)`
  - In composing update logic: if `composing.startsWith(";")`:
    - Extract prefix after `;`
    - Call `SnippetStore.matching(prefix)`
    - Show in suggestion bar instead of trie suggestions
    - Bar slot format: shortcode label + truncated value preview
  - On snippet tap: `commitText(fullValue, 1)`, clear composing

### What to check
- Typing `;em` shows email snippet in bar
- Tapping snippet expands correctly, no `;em` left in field
- Trie suggestions don't show while in snippet mode
- Empty `;` shows all snippets (up to 2 in bar)

---

## Out of scope for this plan
- Coding mode toggle (passthrough flag, trivial addition later)
- Undo/redo stack
- Next-word / bigram prediction
- Snippet settings UI (manage snippets from within the app)
- Frequency-ranked suggestions
