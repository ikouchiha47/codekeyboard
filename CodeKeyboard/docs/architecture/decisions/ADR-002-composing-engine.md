# ADR-002: ComposingEngine — Composing Buffer and Field Detection

## Status
Accepted.

## Context
Android IMEs can show in-progress text underlined in the target field via
`setComposingText`, committing the full word on space/punctuation. This enables
real-time suggestion updates and a better typing experience.

However not all fields support composing:
- Terminal emulators (Termux): `inputType == TYPE_NULL` — raw key events only
- Password fields: composing leaks visual password state
- Numeric/phone/datetime fields: composing doesn't make sense

The composing buffer and the field-type detection must not live inside
`CodeKeyboardIME.handleKey` — that function is already large and the logic
belongs in a dedicated, testable class.

## Decision

### ComposingEngine
Owns the composing `StringBuilder`. Talks to `TextInputConnection` (ADR-001).
`CodeKeyboardIME` calls `engine.onChar()`, `engine.onSpace()`, etc.

```
ComposingEngine
├── onChar(char: String)         — append to buffer, setComposingText or commitText
├── onSpace()                    — flush buffer + commit space
├── onBackspace(): Boolean       — delete from buffer; returns false if buffer was empty
├── onPunctuation(char: String)  — flush buffer, commit punctuation
├── onFlush()                    — flush buffer without appending anything
├── onClear()                    — discard buffer (field switch, escape)
└── currentWord: String          — read-only view of buffer (for suggestion updates)
```

### supportsComposing flag
Set once per field focus in `CodeKeyboardIME.onStartInput`. Passed to engine.
Engine behaviour when false: skip `appendComposing`, call `commitText(char)` directly,
keep buffer empty always.

### Field detection logic (in CodeKeyboardIME.onStartInput)
```
supportsComposing = when {
    editorInfo == null                          -> false
    editorInfo.inputType == TYPE_NULL           -> false  // terminals, vim
    variation is PASSWORD or WEB_PASSWORD       -> false  // password fields
    class is NUMBER, PHONE, or DATETIME         -> false  // numeric fields
    else                                        -> true
}
```

### onCharCommitted placement
`kbState.onCharCommitted()` (clears latched modifiers/layers) fires in
`engine.onFlush()` — i.e. when a word is actually committed, not on every
character append. Shift latch stays active mid-word, clears on space/punctuation.

### Modifier interaction
When a modifier (ctrl/alt/meta) is active, `CodeKeyboardIME` flushes composing
before sending the key event. The engine is not aware of modifiers — the IME
handles this ordering.

## Platform behaviour

| Platform | supportsComposing | appendComposing behaviour |
|---|---|---|
| Android standard text field | true | setComposingText — underline shown |
| Android TYPE_NULL (terminal) | false | no-op, commitText per char |
| Android password field | false | no-op, commitText per char |
| iOS (all fields) | false | no-op, UITextDocumentProxy.insertText per char |

## Consequences
- `ComposingEngine` is pure Kotlin — testable with `FakeTextInputConnection`, no Robolectric
- `CodeKeyboardIME` shrinks: no composing logic inline, just delegates to engine
- Shift latch behaviour is correct: stays active while composing, clears on commit
- Terminal/vim experience unchanged: per-character commitText as before
