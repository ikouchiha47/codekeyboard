# ADR-003: Suggestions and Snippet System — Kotlin Native, No RN Bridge

## Status
Accepted.

## Context
Word suggestions and snippet expansion are currently either absent (suggestions
never update in IME mode) or implemented in React Native JS (`Dictionary.ts`,
`SuggestionBar.tsx`, `Keyboard.tsx`).

The RN bridge path has fundamental problems for IME use:
- JS runs on a separate thread; suggestion updates lag behind typing
- `commitText` in IME mode bypasses RN state entirely — `updateSuggestions`
  is never called because the JS text state is not updated
- `applySuggestion` manipulates local RN `text` state, not the real input field
- The RN layer will be removed eventually — building more features on it is wasteful

## Decision

All suggestion and snippet logic moves to Kotlin. No RN bridge involvement.

### SuggestionBarView
Native `LinearLayout` inserted above the keyboard in `onCreateInputView`.
Three fixed slots:
- Slot 1: current composing word — tap confirms as-is
- Slot 2 & 3: trie completions or snippet matches — tap replaces composing word

Updates synchronously after every `ComposingEngine.onChar()` call.
No async, no bridge, no lag.

### Kotlin Trie
Reads `assets/en.trie` — same TRIE2 binary format as `src/keyboard/Trie.ts`.
`ByteBuffer` replaces `DataView`. Loaded once in `onCreate()`.
API: `suggest(prefix: String, max: Int): List<String>`, `has(word: String): Boolean`.

The JS trie (`Trie.ts`, `Dictionary.ts`, `DictionaryTrieData.ts`) remains for the
RN preview text field until RN is removed. Do not delete yet.

### SnippetStore
`SharedPreferences`-backed key/value store. Shortcode → expansion.
Trigger: composing buffer starts with `;`.
While in snippet mode, `SuggestionBarView` shows snippet matches instead of trie results.
Tap expands: clear composing, `commitText(expansion)`.

Default snippets seeded on first run:
```
em   → (empty, user sets via settings)
ph   → (empty)
addr → (empty)
li   → (empty — LinkedIn URL)
gh   → (empty — GitHub URL)
me   → (empty — full name)
```

### Suggestion bar update flow
```
User types char
  → ComposingEngine.onChar()
  → SuggestionBarView.update(word, suggestions)
      if word.startsWith(";"):
          SnippetStore.matching(word.drop(1)) → show snippet slots
      else:
          trie.suggest(word) → show word completion slots
```

### Coding mode (passthrough)
A single boolean flag `predictionsEnabled` (default true).
When false: `SuggestionBarView` is hidden, composing is disabled (`supportsComposing`
forced false regardless of field type). All keys pass through as raw commits/events.
Toggle via a dedicated key on the keyboard. No structural change to the engine.

## What stays in RN (for now)
- `SuggestionBar.tsx` — only used in preview text field, not in IME mode
- `Dictionary.ts`, `Trie.ts`, `DictionaryTrieData.ts` — used by preview field

## Consequences
- Suggestions work correctly in IME mode — no bridge lag, no missed updates
- Kotlin trie loads ~once at startup, sub-millisecond lookup per keystroke
- Snippet expansion is instant and offline — no network, no model needed
- Removing RN later: delete `src/keyboard/SuggestionBar.tsx`, `Dictionary.ts`,
  `Trie.ts`, `DictionaryTrieData.ts` — nothing else changes
