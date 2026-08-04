# ADR-001: TextInputConnection — Platform Abstraction Interface

## Status
Accepted — hand-written interface now, AI-generated platform implementations from this document.

## Context
The keyboard IME needs to interact with text fields across platforms. On Android this
is `InputConnection`. On iOS this is `UITextDocumentProxy`. Future platforms (desktop,
web, new mobile OSes) will have their own equivalents.

If platform-specific APIs are called directly in core logic (`ComposingEngine`,
snippet expansion, suggestion handling), porting to a new platform means rewriting
core logic rather than implementing a thin adapter.

Additionally, direct use of `InputConnection` makes unit testing impossible without
Robolectric or Android instrumentation. A clean interface allows pure JVM/pure Swift
tests with a fake implementation.

## Decision
Define `TextInputConnection` as a canonical interface in this document. Each platform
implements it as a thin adapter over the native API. Core keyboard logic only depends
on this interface — never on platform types directly.

This document is the source of truth. AI generates platform implementations from it.
No protobuf, no build tooling. When the interface changes, update this doc and
regenerate affected platform files.

Future migration path: if multiple platforms exist and drift becomes a risk, convert
this document to a `.proto` file and use `protoc` to generate typed interfaces. The
hand-written implementations map 1:1 to what proto would generate — migration is
mechanical.

## Interface Definition

### Operations

```
TextInputConnection
│
├── appendComposing(text: String)
│     Show `text` as in-progress (underlined) in the target field.
│     Replaces any previous composing region.
│     On platforms without composing support: no-op (caller handles fallback).
│
├── commitText(text: String)
│     Finalize `text` into the field. Clears any composing region.
│     Cursor moves to end of inserted text.
│
├── backspace()
│     Delete one character before the cursor.
│     If a composing region exists, the caller is responsible for managing
│     the composing buffer — this deletes from committed text only.
│
├── clearComposing()
│     Discard the current composing region without committing it.
│     No text is inserted or deleted.
│
├── getTextBeforeCursor(maxChars: Int): String
│     Return up to `maxChars` characters before the cursor from committed text.
│     Does not include the composing region.
│     Returns empty string if unavailable.
│
└── getSelectedText(): String
      Return currently selected text, or empty string if no selection.
```

### Data flow

```
User types "helo":
  appendComposing("h")
  appendComposing("he")
  appendComposing("hel")
  appendComposing("helo")          ← underlined in field

User taps suggestion "hello":
  clearComposing()                 ← remove "helo" underline
  commitText("hello ")            ← commit corrected word + space

User taps backspace mid-word:
  appendComposing("hel")          ← buffer managed by ComposingEngine, not here
```

### What this interface does NOT own
- The composing buffer (`StringBuilder`) — owned by `ComposingEngine`
- Knowledge of whether the field supports composing — decided in `onStartInput`,
  passed to `ComposingEngine` as a flag
- Suggestion logic — separate layer above this interface
- Snippet expansion — separate layer above this interface

---

## Platform Implementations

### Android — `AndroidTextInputConnection`

Wraps `android.view.inputmethod.InputConnection`.

```kotlin
class AndroidTextInputConnection(
    private val ic: InputConnection
) : TextInputConnection {

    override fun appendComposing(text: String) {
        ic.setComposingText(text, 1)
    }

    override fun commitText(text: String) {
        ic.commitText(text, 1)
        // commitText implicitly clears the composing region — do NOT call
        // finishComposingText after commitText, it causes double-clear on some apps.
    }

    override fun backspace() {
        ic.deleteSurroundingText(1, 0)
    }

    override fun clearComposing() {
        ic.finishComposingText()
    }

    override fun getTextBeforeCursor(maxChars: Int): String {
        return ic.getTextBeforeCursor(maxChars, 0)?.toString() ?: ""
    }

    override fun getSelectedText(): String {
        return ic.getSelectedText(0)?.toString() ?: ""
    }
}
```

**Notes:**
- `setComposingText` shows underlined text. Only works when `EditorInfo.inputType != TYPE_NULL`.
- `TYPE_NULL` fields (Termux, vim, terminal emulators): `appendComposing` should not
  be called — `ComposingEngine` handles this via the `supportsComposing` flag set in
  `onStartInput`.
- Password fields (`TYPE_TEXT_VARIATION_PASSWORD`, `TYPE_TEXT_VARIATION_WEB_PASSWORD`):
  same — `supportsComposing = false`.
- Numeric/phone/datetime fields: same — `supportsComposing = false`.

---

### iOS — `iOSTextInputConnection`

Wraps `UITextDocumentProxy` from `UIKit`.

```swift
class iOSTextInputConnection: TextInputConnection {

    private let proxy: UITextDocumentProxy

    init(proxy: UITextDocumentProxy) {
        self.proxy = proxy
    }

    func appendComposing(text: String) {
        // iOS public API has no composing/underline support for third-party keyboards.
        // No-op — ComposingEngine must set supportsComposing = false on iOS.
        // Characters are committed immediately via commitText instead.
    }

    func commitText(_ text: String) {
        proxy.insertText(text)
    }

    func backspace() {
        proxy.deleteBackward()
    }

    func clearComposing() {
        // No composing region on iOS — no-op.
    }

    func getTextBeforeCursor(maxChars: Int) -> String {
        let before = proxy.documentContextBeforeInput ?? ""
        // documentContextBeforeInput has no length guarantee — may be truncated
        // by the system. Treat it as best-effort.
        return String(before.suffix(maxChars))
    }

    func getSelectedText() -> String {
        return proxy.selectedText ?? ""
    }
}
```

**Notes:**
- `appendComposing` is always a no-op on iOS. `ComposingEngine` must set
  `supportsComposing = false` unconditionally for iOS.
- `documentContextBeforeInput` is unreliable in password fields and some custom
  text views — may return `nil` or truncated text.
- `deleteBackward()` deletes one Unicode scalar, not one Java char — no surrogate
  pair issues unlike Android.
- There is no `getSelectedText` equivalent in the public API before iOS 16.
  `proxy.selectedText` is available from iOS 16+.

---

### Test fake — `FakeTextInputConnection`

Pure Kotlin, no Android dependency. Use in JVM unit tests.

```kotlin
class FakeTextInputConnection : TextInputConnection {
    val committed = StringBuilder()
    var composing: String = ""
    var finishComposingCalled = false

    override fun appendComposing(text: String) { composing = text }

    override fun commitText(text: String) {
        committed.append(text)
        composing = ""
    }

    override fun backspace() {
        if (committed.isNotEmpty())
            committed.deleteCharAt(committed.length - 1)
    }

    override fun clearComposing() {
        composing = ""
        finishComposingCalled = true
    }

    override fun getTextBeforeCursor(maxChars: Int): String =
        committed.takeLast(maxChars)

    override fun getSelectedText(): String = ""

    fun reset() {
        committed.clear()
        composing = ""
        finishComposingCalled = false
    }
}
```

---

## Consequences

**Good:**
- Core logic (`ComposingEngine`, snippets, suggestions) is platform-agnostic
- Unit tests require no Robolectric — `FakeTextInputConnection` is pure Kotlin
- Adding a new platform = implement this interface, nothing else changes
- AI can generate a new platform implementation by reading this document

**Bad:**
- One more indirection layer — `CodeKeyboardIME` constructs
  `AndroidTextInputConnection` and passes it down
- `InputConnection` lifetime is tied to the IME session — the Android wrapper must
  not be cached across `onStartInput` / `onFinishInput` calls. Reconstruct on each
  `onStartInput`.

## Alternatives considered

**Protobuf codegen** — deferred. When multiple platforms exist and interface drift
becomes a real risk, convert this document to `keyboard.proto` and run `protoc`.
The implementations above map 1:1 to what proto would generate.

**Direct `InputConnection` use** — rejected. Makes unit testing require Robolectric
and ties core logic to Android forever.
