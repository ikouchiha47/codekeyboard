# Phase 4: Snippet System — Detailed Breakdown

## What this phase delivers

Two parts, one phase:

1. **Kotlin engine** — `SnippetStore.kt` reads from `KeyboardSettings` (SharedPreferences).
   When composing buffer starts with `;`, `SuggestionBarView` shows snippet matches
   instead of trie completions. Tapping a slot commits the expansion.

2. **RN settings UI** — snippet editor added to the existing `SettingsScreen` in
   `App.tsx`. User sets their email, name, GitHub URL, etc. Saved via `SettingsModule`
   into the same `SharedPreferences` file the Kotlin side reads from.

No new native modules. No new bridge calls. `KeyboardSettings` and `SettingsModule`
already exist and already bridge both sides.

---

## SharedPreferences schema

All snippet keys stored in `codekeyboard_prefs` (existing file, existing singleton).

Key format: `snippet_<shortcode>`
Value format: the expansion string, stored as-is.

Examples:
```
snippet_em   → "alex@example.com"
snippet_ph   → "+1 555 123 4567"
snippet_addr → "123 Main St, Springfield"
snippet_me   → "Alex Day"
snippet_gh   → "https://github.com/ikouchiha47"
snippet_li   → "https://linkedin.com/in/..."
```

Empty value means the shortcode is registered but the user hasn't filled it in yet.
The engine treats empty value as no-op — slot is shown greyed or hidden.

Default shortcodes seeded on first run (values all empty until user sets them):
`em`, `ph`, `addr`, `me`, `gh`, `li`

First-run detection: `KeyboardSettings.getBoolean("snippets_seeded", false)`.
On first run, write all six keys with empty string, set `snippets_seeded = true`.

### Why not a separate prefix for snippet keys

`codekeyboard_prefs` already holds other keys (`theme`, `layout`, etc. in future).
The `snippet_` prefix namespaces them cleanly without a second prefs file.
`SnippetStore` filters by prefix when listing all snippets.

---

## `;` detection and composing interaction

**Does `;` flush composing first?**

Yes. `;` is already in the `PUNCTUATION` set in `CodeKeyboardIME`. When a letter
word is in the composing buffer and the user types `;`, the existing `else` branch
calls `flushComposing(ic)` then `commitText(";", 1)`. The composing buffer is empty
after that.

Then `;` is committed to the field as a literal character. That is wrong for snippet
mode — we don't want `;` to appear in the text field at all.

**Correct behaviour:**

`;` starts snippet mode. It should NOT be committed to the field. Instead it goes
into the composing buffer as the first character, and the suggestion bar switches
to showing snippet matches.

**Change required in `handleKey` character else branch:**

```kotlin
if (supportsComposing && text.length == 1 && !isPunctuation(text[0])) {
    // existing letter composing path
} else if (text == ";") {
    // start snippet mode — put ; in composing, do NOT commit it
    val word = composing.append(";")
    ic?.setComposingText(word, 1)
    suggestionBar.update(word, emptyList())  // snippetStore.matching() called in update
} else {
    flushComposing(ic)
    ic?.commitText(text, 1)
}
```

Wait — `;` is currently in `PUNCTUATION`, so it hits the `else` branch and gets
flushed+committed. We need `;` removed from the `PUNCTUATION` set and handled
explicitly. A single-character composing buffer of `;` then continues to accumulate
characters as the user types the shortcode.

**Revised `isPunctuation`:** remove `;` from the set. `;` now enters the composing
path like a letter would. The rest of composing/flushing logic is unchanged.

**What `;` looks like in the target field:** the composing region shows `;em`
underlined as the user types. If they tap a suggestion, the whole underlined region
is replaced. If they hit space or punctuation, `flushComposing` commits `;em` as
literal text — snippet mode cancelled, user gets `;em` in the text. This is the
correct fallback when no snippet matches.

---

## Snippet mode in `SuggestionBarView.update()`

`SuggestionBarView` already calls `update(word, suggestions)`. The IME decides what
`suggestions` contains. No change to `SuggestionBarView` itself.

In `CodeKeyboardIME`, after every composing update:

```kotlin
val word = composing.text
val suggestions = if (word.startsWith(";")) {
    snippetStore.matching(word.drop(1))   // drop the ; prefix
} else if (supportsComposing) {
    trie.suggest(word, 3)
} else {
    emptyList()
}
suggestionBar.update(word, suggestions)
```

`matching(prefix)` returns expansion strings (not shortcodes) for slots.
Slot text = the expansion value, not the key. Tapping commits the expansion.

---

## `SnippetStore.kt`

```kotlin
object SnippetStore {

    private val DEFAULTS = listOf("em", "ph", "addr", "me", "gh", "li")

    fun init() {
        if (KeyboardSettings.getBoolean("snippets_seeded", false)) return
        DEFAULTS.forEach { key -> KeyboardSettings.setString("snippet_$key", "") }
        KeyboardSettings.setBoolean("snippets_seeded", true)
    }

    // Returns up to 3 expansion values whose shortcode starts with prefix.
    // Entries with empty values are excluded.
    fun matching(prefix: String): List<String> {
        if (prefix.isEmpty()) return allNonEmpty().take(3)
        return allNonEmpty()
            .filter { (key, _) -> key.startsWith(prefix) }
            .map { (_, value) -> value }
            .take(3)
    }

    fun set(shortcode: String, expansion: String) {
        KeyboardSettings.setString("snippet_$shortcode", expansion)
    }

    fun get(shortcode: String): String =
        KeyboardSettings.getString("snippet_$shortcode", "")

    fun all(): List<Pair<String, String>> =
        DEFAULTS.map { key -> key to KeyboardSettings.getString("snippet_$key", "") }

    private fun allNonEmpty(): List<Pair<String, String>> =
        all().filter { (_, v) -> v.isNotEmpty() }
}
```

`SnippetStore` is an `object` — same pattern as `KeyboardSettings`. No constructor,
no context needed (delegates to `KeyboardSettings` which is already initialised).

`init()` called once in `CodeKeyboardIME.onCreate()` after `KeyboardSettings.init()`.

---

## Tap handler for snippet suggestions

When a suggestion slot is tapped and the composing buffer starts with `;`, the
`handleSuggestionTap` path in `CodeKeyboardIME` already handles it correctly:

```kotlin
private fun handleSuggestionTap(word: String) {
    val ic = currentInputConnection ?: return
    ic.finishComposingText()       // clears the underlined ;em
    ic.commitText("$word ", 1)     // commits the expansion + space
    composing.clear()
    kbState.onCharCommitted()
    keyboardView.notifyStateChanged(kbState)
    suggestionBar.clear()
}
```

`word` here is the expansion value (e.g. `"alex@example.com"`), not the shortcode.
The `;em` underline disappears, the full email lands in the text field. No change
to `handleSuggestionTap` is needed.

---

## Edge cases

### Snippet value contains `;`

The expansion value is stored and committed as-is. `"https://example.com/a;b"` works
fine — `;` in a stored expansion string is never parsed as a new trigger. The trigger
only fires on the composing buffer starting with `;`.

### Empty expansion value

`matching()` filters out entries with empty values. Empty snippets are never shown
in suggestion slots. If ALL snippets are empty (user hasn't set any), `matching()`
returns empty list, slots show empty — no crash, no suggestion.

### Very long expansion

No length limit enforced. `commitText` handles arbitrarily long strings. The
composing region shows `;em` (short), the expansion appears in full on commit.
If the user somehow sets a 10,000-character expansion, it commits in one call — fine.

### Shortcode with no match

User types `;xyz`, no snippet matches. Slots show empty. If user presses space,
`flushComposing` commits `;xyz` literally. Correct — this is the cancellation path.

### Typing past a shortcode (e.g. `;email` when shortcode is `em`)

`matching("email")` returns nothing — `"em"` doesn't start with `"email"`. Slots
go empty. User can backspace to `em` and the match reappears. Or space to commit
`;email` literally.

### Snippet mode in `!supportsComposing` fields (terminal/vim)

`;` in Termux/vim goes through the `else` branch and gets committed as-is — no
snippet mode in those fields. This is correct: those fields receive raw key events,
not composing regions. No special handling needed.

---

## RN settings UI — `SnippetsScreen` in `App.tsx`

Replace `PlaceholderScreen` for the `settings` tab with the actual `SettingsScreen`,
and add a snippets section to it.

### New component: `SnippetsEditor`

```tsx
const SNIPPET_KEYS = [
  { key: 'em',   label: 'Email',    hint: 'your@email.com' },
  { key: 'ph',   label: 'Phone',    hint: '+1 555 ...' },
  { key: 'addr', label: 'Address',  hint: '123 Main St ...' },
  { key: 'me',   label: 'Name',     hint: 'Full name' },
  { key: 'gh',   label: 'GitHub',   hint: 'https://github.com/...' },
  { key: 'li',   label: 'LinkedIn', hint: 'https://linkedin.com/in/...' },
];
```

Each row: label on left, `TextInput` on right. On blur/submit, calls
`NativeModules.SettingsModule.setString("snippet_em", value)`.

On mount, calls `SettingsModule.getString("snippet_em", cb)` for each key to
populate current values.

### Placement

Added as a new section inside the existing `SettingsScreen`, below the
"Manage Keyboards" and "Switch Keyboard" buttons. No new tab. No navigation change.

---

## Files that change

| File | Change |
|---|---|
| New: `SnippetStore.kt` | Object with `init`, `matching`, `set`, `get`, `all` |
| `CodeKeyboardIME.kt` | Call `SnippetStore.init()` in `onCreate`; route `;` to composing; branch `update()` on `;` prefix |
| `CodeKeyboardIME.kt` | Remove `;` from `PUNCTUATION` set |
| `App.tsx` | Add `SnippetsEditor` component inside `SettingsScreen` |

No new native modules. No new bridge methods. No new gradle dependencies.

---

## Test cases

### New: `SnippetStoreTest.kt` (pure JVM via `FakeKeyboardSettings`)

`SnippetStore` delegates to `KeyboardSettings` which wraps `SharedPreferences`.
To test without Android context, introduce `FakeKeyboardSettings` that backs the
same interface with a `HashMap`. Alternatively test through a test-scoped
`KeyboardSettings` pointing to a temp prefs file.

| Test | Assertion |
|---|---|
| `init()` seeds defaults | `all()` returns 6 entries, all values empty |
| `init()` idempotent | second `init()` call doesn't reset existing values |
| `set("em", "a@b.com")` then `get("em")` | returns `"a@b.com"` |
| `matching("")` with 2 set | returns both values |
| `matching("e")` matches `em` | returns `["a@b.com"]` |
| `matching("x")` no match | returns `[]` |
| `matching("")` empty-value excluded | only returns set values |
| `matching("")` returns max 3 | even if 6 are set |
| value with `;` | stored and returned as-is |
| very long value | stored and returned as-is |

### New: `SnippetModeTest.kt` (pure JVM via `FakeTextInputConnection`)

Tests the `;` composing path using `ComposingBuffer` + `SnippetStore` + `FakeTextInputConnection` directly, without `CodeKeyboardIME`.

| Test | Assertion |
|---|---|
| `;` starts composing, not committed | `fake.committed` does not contain `;` |
| `;em` matches → slots show expansion | `SnippetStore.matching("em")` non-empty |
| tap expansion → committed with space | `fake.committed` ends with `"a@b.com "` |
| `;xyz` no match → space flushes literally | `fake.committed` contains `;xyz ` |
| `;` in `!supportsComposing` field | `;` committed directly, no composing |

### Existing tests — no changes expected

`ComposingBufferTest`, `TrieTest`, `SuggestionSlotLogicTest`, `KeyboardStateTest`.

---

## Definition of done

- [ ] `SnippetStore.init()` seeds 6 default shortcodes on first run
- [ ] Typing `;em` in a text field shows the stored email in slot0
- [ ] Tapping slot0 replaces `;em` with the email + space
- [ ] Typing `;xyz` with no match shows empty slots
- [ ] Space after `;xyz` commits `;xyz` literally
- [ ] Empty snippets (not yet configured) not shown in slots
- [ ] `;` in `!supportsComposing` fields commits directly, no snippet mode
- [ ] `SnippetsEditor` in Settings tab loads and saves values via bridge
- [ ] All `SnippetStoreTest` cases pass
- [ ] All existing tests pass
