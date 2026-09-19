# ADR-014: Clipboard History Panel + Copied-Emoji Grouping

**Status:** Proposed
**Date:** 2026-09-13

---

## Context

### What is missing today

The native IME has **no clipboard history at all**. The only clipboard behaviour is the
RAISE/FUNC `Copy`/`Paste` keys delegating to the target app's own context menu
(`CodeKeyboardIME.kt:461-466`):

```kotlin
"copy"  -> ic?.performContextMenuAction(android.R.id.copy)
"paste" -> ic?.performContextMenuAction(android.R.id.paste)
```

There is no `ClipboardManager`, `ClipData`, `commitContent`, `FileProvider`, or image
decoding anywhere in the Kotlin source. The only alternate panel that exists is
`EmojiPanelView`, which swaps the entire input view via `setInputView(...)`.

Users want:
1. A clipboard history reachable from a key on the RAISE layer.
2. History capped at 50 entries.
3. Copied passwords detected and removed from **our** history after a single paste.
4. A panel showing history with **Copy** and **Paste** actions (plus back-to-keyboard).
5. Emojis copied from the keyboard **and from other apps** to appear under a custom
   grouping on the emoji board.
6. (Deferred) copying/pasting stickers, images and GIFs, and a GIF tab. See
   `docs/adr-002-gif-picker.md` for that separate design.

### Platform constraints (verified against AOSP + official docs)

These shape the whole design and must not be papered over:

| # | Fact | Consequence |
|---|---|---|
| P1 | On API 29+, clipboard reads are allowed for the package named in `Settings.Secure.DEFAULT_INPUT_METHOD` — **even when its window is hidden or another app has focus**. `ClipboardService.clipboardAccessAllowed` returns early for the default IME. Any other app (including an installed-but-not-selected IME) needs focus. | Background capture works **while CodeKeyboard is the selected keyboard**. Switching to another keyboard stops capture entirely. Device-locked also blocks it. |
| P2 | `OnPrimaryClipChangedListener` dispatch is gated by the same access check, at broadcast time. | For the default IME the callback fires; for a non-default IME no callback is delivered at all. |
| P3 | There is **no clipboard provenance API**. `ClipDescription.EXTRA_IS_SENSITIVE` (API 33; literal `"android.content.extra.IS_SENSITIVE"` works pre-33) is an optional producer hint and, per Android docs, "does not change clipboard behavior or add additional security". `EditorInfo` flags describe only the current field. | A password copied from a non-password field by an app without the flag is **undetectable**. We will not add entropy/length guessing (it misfires on keys/tokens and destroys legitimate clips). |
| P4 | The system clipboard is process-global; any focused app can read it. Android 13+ auto-clears the whole clipboard after ~1 hour (`DEFAULT_CLIPBOARD_TIMEOUT_MILLIS = 3600000`). | We can guarantee our **history** is private and self-expiring; we cannot make the *system* clipboard unreadable to other apps. |
| P5 | Reading as the default IME does **not** show the Android 12+ "pasted from clipboard" toast. | The feature will not nag the user. |
| P6 | API 31+ may invoke `onPrimaryClipChanged` multiple times for one clip (classification-status changes). | Dedupe is required. |

### User decision already taken

For a sensitive entry pasted once, the keyboard **also clears the system clipboard**
(`clearPrimaryClip()`), in addition to dropping its own history entry.

---

## Goals

1. A `Clip` key on the RAISE layer opens a clipboard panel; one key press → panel visible.
2. History holds at most **50** entries, newest first, exact-text deduped (move-to-front).
3. Sensitive entries are **never written to disk**, are masked in the UI, and are removed
   from the in-memory list on the first history-Paste; the system clipboard is cleared at
   the same time.
4. Copy re-arms the system clipboard; Paste inserts into the focused field directly.
5. Emoji-only clips (from the keyboard's own Copy and from other apps) appear in a
   `Recent Emoji` category on the emoji board.
6. Phases 0–4 add **no new permissions and no new dependencies** (framework `ListView`,
   `PopupWindow`, `BreakIterator`, `org.json` only).
7. Adding one history entry is O(1); the store is pure Kotlin testable on the JVM.
8. Existing behaviour is additive only: the current `copy`/`paste` dispatch lines are not
   rewritten, only appended to.

---

## Architecture

### Layer 1 — pure logic (no Android imports)

**`ClipEntry`** and **`ClipboardHistory`** (new file `ClipboardHistory.kt`). Uses only
`java.io.File` and `org.json`, so it is JVM-testable.

```kotlin
data class ClipEntry(
    val id: Long,            // monotonic, stable list key
    val text: String,
    val createdAt: Long,
    val isSensitive: Boolean // never persisted
)

class ClipboardHistory(private val file: File) {
    companion object { const val MAX = 50; const val MAX_BYTES = 8 * 1024 }

    fun record(text: String, isSensitive: Boolean): ClipEntry?  // null if blank/oversize
    fun clips(): List<ClipEntry>                                 // newest first, includes sensitive
    fun remove(id: Long)
    fun clear()
    fun load()
    fun save()                                                   // non-sensitive only
}
```

Schema is versioned (`{"v":1,"clips":[{"text":...,"createdAt":...}]}`) so a later image
store can migrate cleanly. `save()` is plain (no threads) so tests call it directly;
the IME wraps it in the existing single-thread `flushExecutor` (`CodeKeyboardIME.kt:53`).

**`EmojiClassifier`** (new file `EmojiClassifier.kt`). Uses only `java.text.BreakIterator`.

```kotlin
object EmojiClassifier {
    fun graphemes(text: String): List<String>
    fun isEmojiOnly(text: String, maxGraphemes: Int = 8): Boolean
}
```

Handles VS16 (`U+FE0F`), ZWJ (`U+200D`), skin tones (`U+1F3FB–U+1F3FF`), keycaps
(`U+20E3`) and regional indicators. Mixed strings such as `"hi 😀"` return `false` and go
only to text history.

**Design choice — one store, two views.** Emoji clips are *not* a separate type or file.
`ClipKind` is derived at render time via `isEmojiOnly`. This keeps copied emojis in the
same 50-cap store, persisted automatically, with no second index to keep in sync.

### Layer 2 — UI

**`ClipboardPanelView`** (new file). Same pattern as `EmojiPanelView`: a plain
`LinearLayout` that replaces the input view via `setInputView`, fixed to the wrapper's
height, opaque background, `descendantFocusability = FOCUS_BLOCK_DESCENDANTS`,
bottom-padded by `navPaddingPx`.

- Top: title row `Clipboard` + `Clear`.
- Middle: framework `ListView` + `BaseAdapter` (no RecyclerView dependency). Preview is
  single-line ellipsized; sensitive rows show `•••••• sensitive`; emoji rows show the
  glyph. One row is single-select.
- Bottom bar: **`ABC │ Copy │ Paste │ ⌫`**. Actions disabled until a row is selected.
  This implements the requested `Copy | Paste` on the bottom without cramming two
  buttons into each of 50 rows.
- Callbacks (interfaces-first): `onCopy(ClipEntry)`, `onPaste(ClipEntry)`,
  `onDelete(ClipEntry)`, `onClear()`, `onBackToKeyboard()`.

### Layer 3 — wiring (`CodeKeyboardIME`)

- New fields: `clipboardHistory`, `clipboardPanel`, `selfWriteAtUptimeMs`.
- `onCreate`: `clipboardHistory = ClipboardHistory(File(filesDir, "clip_history.json"))`
  then `load()`, registered alongside `SnippetStore.init()` (`:114`). Register
  `ClipboardManager.OnPrimaryClipChangedListener`.
- **Add the currently missing `onDestroy`** to unregister the listener.
- New action branch in `handleKey`: `"clipboard" -> showClipboardPanel()` next to
  `"emoji" -> showEmojiPanel()` (`:452`).
- `showClipboardPanel()` / `hideClipboardPanel()` mirror `showEmojiPanel`/`hideEmojiPanel`
  (`:65-98`), including the `tag = kbHeight` cache guard.

### Capture paths

| Source | Mechanism | Reliability |
|---|---|---|
| Keyboard `Copy` key | Read `ic.getSelectedText(0)` *before* `performContextMenuAction(copy)`; `isSensitive = isPasswordField(currentInputEditorInfo)` (existing helper, `:364`). | High — no clipboard timing race, authoritative for the current field. |
| Keyboard `Paste` key | Read `getPrimaryClip()` before pasting. | High while selected IME. |
| External copies | `OnPrimaryClipChangedListener`. | Works while CodeKeyboard is the selected IME (P1). `null` clip handled silently. |
| Emoji board Copy | Direct `clipboardHistory.record(...)`. | High — no clipboard round-trip. |

Self-write guard: set `selfWriteAtUptimeMs` when we call `setPrimaryClip`; ignore listener
events within ~250 ms. `record()` also dedupes by exact text, so the guard is belt-and-braces.

### Copy vs Paste semantics

- **Copy** → `ClipboardManager.setPrimaryClip(ClipData.newPlainText(...))`, re-setting the
  sensitive extra when the entry is sensitive, so the target app's own paste works.
- **Paste** → `currentInputConnection.commitText(text, 1)` directly. Deterministic and
  works even if the system clipboard has since changed. Fall back to
  set-clip + `performContextMenuAction(android.R.id.paste)` if the field rejects direct
  commit.
- The existing RAISE/FUNC `Paste` key keeps `performContextMenuAction` unchanged — that is
  the app-mediated rich-content path.

### Sensitive handling (exact semantics)

1. Sensitive entries live **only in memory**; `save()` excludes them.
2. They render masked, with an explicit reveal-free `Delete`.
3. First history-Paste → `commitText`, remove the entry from the in-memory list, then
   clear the system clipboard (`clearPrimaryClip()` on API 28+; on 26–27 set an empty
   plain-text clip). A second paste is impossible because the row is gone.
4. Re-copy of the same secret re-enters as a new sensitive entry if it qualifies via
   (a) the keyboard Copy key in a password field, or (b) an external clip carrying
   `EXTRA_IS_SENSITIVE`.

**Honest limits:** a password copied from a non-password field by an app that omits the
flag is not detectable (P3). Clearing the system clipboard only works while CodeKeyboard
is the default/focused app. A `Clear history` action and per-row `Delete` are provided so
users can self-remediate.

### Emoji group

- `EmojiPanelView` receives a `recentEmojiProvider: () -> List<String>` (interfaces-first,
  so the view stays decoupled from the store). `loadEmoji()` prepends
  `Category("Recent Emoji", "🕘", entries)` when non-empty; the existing tab/grid/scroll
  code is category-agnostic and needs no other change.
- `recents = history.clips().filter { !it.isSensitive && EmojiClassifier.isEmojiOnly(it.text) }`
  `.map { it.text }.distinct().take(24)`.
- **Copy from the board:** tap still pastes; long-press now offers `Copy`. For cells with
  variants, prepend a `Copy` cell to the existing variant popup (`showVariantPopup`,
  `:246`); for cells without variants, long-press opens a one-button `Copy` popup
  (currently they have no long-press at all, so nothing regresses).
- Cache invalidation: clear the cached `emojiPanel` when a copy is recorded while the
  panel is alive (cheapest is to null it in the `onEmojiCopied` callback).

### Deferred (not this ADR's workflow)

Images/GIFs/stickers require `commitContent` + a `FileProvider` + a helper Activity for
the Photo Picker (an `InputMethodService` cannot use `ActivityResultContracts`), plus
copying bytes into app-private storage at add-time so source deletion cannot break
history. Target-app support is opportunistic (some apps return `true` and silently drop
content; animated GIF rendering is app-dependent). This stays a separate effort aligned
with `docs/adr-002-gif-picker.md`.

---

## Files

| File | Change | Role |
|---|---|---|
| `android/.../ClipboardHistory.kt` | **New** | `ClipEntry` + 50-ring buffer + versioned JSON persistence; Android-free |
| `android/.../EmojiClassifier.kt` | **New** | Pure grapheme/emoji predicate (`BreakIterator`) |
| `android/.../ClipboardPanelView.kt` | **New** | History UI: `ListView`, mask, `ABC │ Copy │ Paste │ ⌫` |
| `android/.../ClipboardHistoryTest.kt` | **New** | JVM tests: cap, dedupe, sensitive-never-saved, round-trip |
| `android/.../EmojiClassifierTest.kt` | **New** | JVM tests for ZWJ/VS16/keycap/skin-tone/flags/negatives |
| `android/.../CodeKeyboardIME.kt` | **Modify** | Minimal, additive: init store + listener in `onCreate` (`:111`); add `onDestroy`; add `"clipboard" -> showClipboardPanel()` at `:452`; append capture calls in `copy`/`paste` branches (`:461-463`) after the existing `performContextMenuAction` lines; add `show/hideClipboardPanel`; pass `recentEmojiProvider`/`onEmojiCopied` into `EmojiPanelView`. No existing line rewritten. |
| `android/.../EmojiPanelView.kt` | **Modify** | Add `recentEmojiProvider` + `onEmojiCopied` params; prepend `Recent Emoji` in `loadEmoji()` (`:355`); add `Copy` to `showVariantPopup` (`:246`) and a long-press for cells without variants (`:239-241`) |
| `android/.../SofleKeyData.kt` | **Modify** | Replace `empty()` on RAISE (`:108`) with `k("Clip","clipboard")` |
| `android/.../FerrisSweepBaseLayerProvider.kt` | **Modify** | Replace first `empty()` on RAISE right row 1 (`:126`) with `k("Clip","clipboard")` |
| `src/keyboard/Layout.ts` | **Modify** | SOFLE_RAISE right row 0: replace `k('', 4, ...)` (`:266`) with `k('Clip', 4, ..., {action:'clipboard'})` |
| `src/keyboard/Keyboard.tsx` | **Modify** | Add `clipboard` to `ACTION_REGISTRY` (preview no-op) |
| `android/.../KeyboardSettings.kt` | **Modify** | Optional: `clipboardHistoryEnabled` (boolean) and `clearClipboardOnSensitivePaste` (boolean) |
| `AndroidManifest.xml` | **No change** | Phases 0–4 need no new permission |
| `android/app/build.gradle` | **No change** | No new dependencies |
| `docs/adr-002-gif-picker.md` | **Reference** | Deferred image/GIF scope |

---

## Consequences

**Becomes easier / better**
- Clipboard history and emoji grouping reuse two established patterns (JSON-in-`filesDir`
  via `flushExecutor`; `setInputView` panel swap), so the diff is small and low-risk.
- Copied emojis are captured with the exact same pipeline as text; no per-app integration
  is needed because emojis are ordinary Unicode text.
- History is app-private (`filesDir` is sandboxed), so no other app can read it.
- Zero new permissions/dependencies for Phases 0–4.

**Deferred**
- Images, GIFs and stickers (`commitContent`, FileProvider, Photo Picker helper Activity,
  stale-URI validation) — separate effort; see `docs/adr-002-gif-picker.md`.
- Cross-device sync, pinning, and per-entry expiry are out of scope.

**Trade-offs / remaining risks**
- History only captures while CodeKeyboard is the selected IME (P1). This is a platform
  limit, not a bug; the panel should not promise "all clips from all apps".
- Password provenance is best-effort (P3). Undetectable cases remain; `Clear history` and
  per-row `Delete` are the mitigation.
- `commitText` bypasses app paste hooks; a few custom/web fields may reject it (fallback
  specified).
- Emoji classification is approximate across Unicode releases; a manual add/remove is a
  possible later refinement, not a blocker.
- Non-sensitive history persists indefinitely (vs the OS 1-hour auto-clear); a max-age is
  a possible follow-up.

---

## Workflow

### Task list

| Task | Description | Kind |
|---|---|---|
| **A** | `ClipboardHistory.kt` — `ClipEntry` + ring buffer + JSON persistence | new file |
| **B** | `EmojiClassifier.kt` — grapheme/emoji predicate | new file |
| **C** | `ClipboardHistoryTest.kt` + `EmojiClassifierTest.kt` | new files |
| **D** | `ClipboardPanelView.kt` — list + bottom bar + callbacks | new file |
| **E** | `CodeKeyboardIME.kt` — store init, listener + `onDestroy`, `"clipboard"` action, capture in copy/paste, `show/hideClipboardPanel`, panel callbacks | targeted modify (additive only) |
| **F** | `EmojiPanelView.kt` — `recentEmojiProvider`/`onEmojiCopied`, `Recent Emoji` category, copy long-press, cache invalidation | targeted modify |
| **G** | `SofleKeyData.kt` — RAISE `Clip` key (`:108`) | targeted modify |
| **H** | `FerrisSweepBaseLayerProvider.kt` — RAISE `Clip` key (`:126`) | targeted modify |
| **I** | `src/keyboard/Layout.ts` — SOFLE_RAISE `Clip` key (`:266`) | targeted modify |
| **J** | `src/keyboard/Keyboard.tsx` — `ACTION_REGISTRY` entry | targeted modify |
| **K** | `KeyboardSettings.kt` + settings UI — enable toggle, clear history, clear-on-sensitive toggle | targeted modify |

### Dependency edges

- **A** → none
- **B** → none
- **C** → A, B
- **D** → A
- **E** → A, D
- **F** → A, E
- **G** → E
- **H** → E
- **I** → none (independent RN layout)
- **J** → none
- **K** → D, E

### Wave table

| Wave | Tasks | Can parallelise? |
|---|---|---|
| 0 | A, B, I, J | yes |
| 1 | C (needs A, B), D (needs A) | yes |
| 2 | E (needs A, D) | — |
| 3 | F (needs A, E), G (needs E), H (needs E), K (needs D, E) | yes |

### Critical path

**A → D → E → F** (store → panel view → wiring → emoji group). `G`/`H` also join at E,
so the end-to-end "press Clip, see history" path is A → D → E → G/H.

### Blocking notes

- **E touches working code.** Add only a new `when` branch and append capture lines after
  the existing `performContextMenuAction` calls; do not rewrite the dispatch lines. The new
  `onDestroy` override is required or the clipboard listener leaks.
- **F is a two-pass edit**: constructor/`loadEmoji` and the cell/popup gesture change. The
  `emojiPanel` cache (`:76`) must be invalidated when an emoji is copied while the panel is
  alive, or the new group will not appear.
- **G/H/I are a three-file, two-runtime-source edit** (the RN `Layout.ts` is not read at
  runtime but must stay in sync per the Dual Layout Rule). Easy to half-do.
- **No device test before wave 3**: the feature is only end-to-end testable once E and at
  least G or H are done.

### Validation

**JVM unit tests (real classes — both are Android-free):**
- cap 50; dedupe move-to-front; ordering; blank/oversize rejection.
- **sensitive entries absent from `save()` output and absent after reload.**
- save→load round-trip; versioned schema parse.
- paste-removes-once: after `remove()`, a second lookup is null.
- `EmojiClassifier`: `😀`, `❤️`, `1️⃣`, `👨‍👩‍👧`, `🇺🇸`, `👍🏽`, `"hi 😀"` (false),
  `"hello"` (false), `"123"` (false), empty (false).

**Manual device matrix (framework-bound):**
1. RAISE → `Clip` → panel lists newest first; select → Paste inserts; Copy re-arms the
   system clipboard.
2. Existing RAISE/FUNC `Paste` key behaves exactly as before.
3. Password field: type + RAISE `Copy` → masked row; `clip_history.json` contains no
   secret; history-Paste inserts then removes the row **and clears the system clipboard**.
4. External copy while CodeKeyboard is selected (keyboard hidden) → appears; switch to
   another keyboard → copies no longer appear (expected; documents P1).
5. Board long-press → `Copy` → emoji appears in `Recent Emoji` on next open; emoji copied
   in another app → appears; `"hi 😀"` → not in the group.
6. Persistence across IME process death; logcat/leak check confirms `onDestroy` unregisters
   the listener.
7. Oversize clip rejected; rotation / theme / layout switch; API 26, 29, 33 and 36 devices.
8. Confirm IME clipboard reads do **not** trigger the clipboard-access toast (IMEs are
   exempt, P5).

---

## References

- Clipboard access / default-IME exemption: `services/core/java/com/android/server/clipboard/ClipboardService.java` (`clipboardAccessAllowed`), Android 10–16 branches
- `ClipboardManager` docs (null when not default IME / focused): https://developer.android.com/reference/android/content/ClipboardManager
- Clipboard privacy change (API 29): https://developer.android.com/about/versions/10/privacy/changes#clipboard-data
- Sensitive-clip semantics: https://developer.android.com/about/versions/13/behavior-changes-all (hide sensitive content) and https://developer.android.com/privacy-and-security/risks/secure-clipboard-handling
- Copy/paste + toast exemption: https://developer.android.com/develop/ui/views/touch-and-input/copy-paste
- Deferred rich content: https://developer.android.com/develop/ui/views/touch-and-input/image-keyboard and `docs/adr-002-gif-picker.md`
- Emoji data source + panel precedent: `docs/architecture/decisions/ADR-004-user-trie-decay-and-emoji.md`
