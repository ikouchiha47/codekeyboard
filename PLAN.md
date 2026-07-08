# Codeboard – Android Keyboard for Coders

## Goal
A fully functional Android keyboard app for coders, built with React Native. Includes Ctrl, Alt, and other modifier keys typically missing from mobile keyboards. Existing apps (CodeBoard, Unexpected Keyboard) have rendering/UX issues — this project aims to fix that.

## Tech Stack
- **Phase 1 (prototype)**: Vanilla HTML/CSS/JS – served locally, tested in browser
- **Phase 2 (app)**: React Native – Android APK
- **Build env**: Termux + proot-distro (Debian), Node.js v20, npm 11

---

## Phase 1 – Web Prototype

**File**: `keyboard.html` (single-file standalone)

### Steps
1. Create a full keyboard layout using Flexbox.
   - Rows: number row, QWERTY top, home, bottom, spacebar row.
   - Modifier keys: Ctrl, Alt, Shift, Caps, Fn.
   - Navigation: arrows, Tab, Esc, Backspace, Enter.
2. Implement JS logic:
   - Track `ctrlKey`, `altKey`, `shiftKey`, `capsLock`, `fnLayer` state.
   - `ctrl+c`, `ctrl+v`, `ctrl+x`, `ctrl+z` map to clipboard/undo.
   - Alt key produces special characters (Alt+a = α, etc.).
   - Shift/Caps modify letter case.
   - Fn layer shows function keys F1-F12.
3. **Suggestion bar** above the keyboard showing 3 word predictions.
   - Built-in English dictionary for word lookup.
   - Tapping suggestion inserts the word.
   - Suggestion bar also shows the current word being typed.
4. Text input via `<textarea>`.
   - Keystrokes insert/delete text programmatically.
   - Track current word boundary for suggestions.
5. Test all key combinations, touch/click interactions, layout scaling.

### Deliverable
- `keyboard.html` – open in browser to validate UX before React Native.

---

## Phase 2 – React Native Android App

### Steps
1. **Scaffold** React Native project.
   - Use `@react-native-community/cli` or Expo.
   - Configure for Android (AndroidManifest, IME service).
2. **Build Keyboard Component**.
   - Replicate the web layout with `View`, `TouchableOpacity`, `Text`.
   - Use `useWindowDimensions` for dynamic sizing.
3. **Suggestion Bar**.
   - Fixed row above the keyboard showing 3 word predictions.
   - Pill-style buttons.
   - **Language packs** — word lists loaded from external files, never hardcoded.
   - Tap suggestion → replace current word in buffer.

4. **Trie – Word Lookup Engine**.
   - A trie (prefix tree) is built from each language pack at load time.
   - **Trie structure**: each node stores a map of `{nextChar -> childNode}` and a boolean `isEnd`.
   - **Lookup**: walk the trie character-by-character from the root. At the final node, DFS to collect up to 3 completions.
   - **Packing format**: the trie is serialized as a flat array of nodes. Each node:
     ```
     { c: char, children: [childIndex], isEnd: bool }
     ```
   - The packed file is bundled as an app asset (e.g. `assets/lang/en.trie`).
   - At startup, the app reads the file from disk and deserializes into memory.
   - **Build script** (`tools/build-trie.js`): reads a plain-text word list (one per line), builds the trie, serializes to `.trie`.
   - Word list sources: [SCOWL](https://github.com/en-wl/wordlist), [dwyl/english-words](https://github.com/dwyl/english-words), LibreOffice Hunspell dictionaries, plus a curated programming-keywords list.
   - Zero hardcoded words in React Native source code.
5. **Slide Typing (Gesture Typing)**.
   - `PanResponder` or `react-native-gesture-handler` on the keyboard surface.
   - Track pointer movement across key boundaries using key-center hit testing.
   - Record key sequence from touch path.
   - Use gesture-to-word matching via levenshtein distance on the path against dictionary.
   - Show predicted word in suggestion bar as user slides.
   - On lift: insert best-match word.
6. **Android IME Integration**.
   - Register as an input method via `InputMethodService`.
   - Use a hidden `TextInput` with `showSoftInputOnFocus={false}`.
   - Send key events to the focused text field via `InputConnection`.
7. **Modifier State Management**.
   - `useReducer` for state: ctrl, alt, shift, caps, fn, currentLayer.
   - Key press → resolve character → insert/replace/delete.
8. **Key Repeat**.
   - `onPressIn` starts interval, `onPressOut` clears it.
9. **Word Prediction / ML (Future)**.
   - Phase 2a: dictionary-based prefix matching (from language packs).
   - Phase 2b: n-gram language model built from user's typed corpus.
   - Phase 2c: small on-device ML model (TFLite) for next-word prediction.
   - Model trained on code comments and technical prose.
   - Language packs, dictionaries, and ML models all loaded as external assets — zero hardcoded data.
10. **Rendering Performance**.
   - Use `React.memo` on keys, `Animated` for press feedback.
   - Avoid re-renders of full layout on modifier toggle.
11. **Build APK**.
    - `npx react-native build-android` → unsigned APK or AAB.

### Deliverable
- Android APK that can be sideloaded and set as default keyboard.

---

## Language Pack Sourcing

### Can we use Google's language packs?
**No.** Google's language packs (used by Gboard) are proprietary, closed-format, and stored inside Gboard's private data directory. They are not licensed for redistribution or use in third-party apps.

### Sources we *can* use

| Source | License | Words | Format |
|---|---|---|---|
| [SCOWL](https://github.com/en-wl/wordlist) | LGPL / BSD | 60k+ | Plain text |
| [dwyl/english-words](https://github.com/dwyl/english-words) | Public domain | 466k | Text |
| [LibreOffice Hunspell dicts](https://github.com/LibreOffice/dictionaries) | LGPL / MPL | Varies | .dic/.aff |
| [Android AOSP](https://android.googlesource.com/platform/packages/inputmethods/LatinIME/+/refs/heads/main/dictionaries/) | Apache 2.0 | Varies | Binary (can convert) |
| Custom code-keywords list | Curated by us | ~500 | Manual |

**Pipeline**: raw word list → `tools/build-trie.js` → `.trie` file → bundled as app asset.

---

## Rendering Fixes (Why existing apps feel broken)

| Issue | Fix |
|---|---|
| Janky resize on layer switch | Use `Animated` transitions; pre-compute layout |
| Delayed key response | `onPressIn`/`onPressOut` + immediate state update |
| Modifier keys not latching | Dual behavior: tap = latch, hold = momentary |
| Popup/suggestion overlap | Disable system suggestions; full-screen keyboard view |
| Scrolling/closing unexpectedly | Handle `onKeyPreIme`; lock orientation if needed |
| Slide trail lags behind finger | Use `Animated.event` with native driver for trail path |
| Suggestion bar flickers | Keep 3 suggestion slots always mounted; swap text only |

## Milestones

1. [x] Web prototype renders correctly in browser
2. [x] All modifier keys work in web prototype
3. [ ] Suggestion bar + word prediction in web prototype
4. [ ] RN project scaffolds and builds
5. [ ] Keyboard layout renders in RN
6. [ ] Suggestion bar works in RN with dictionary
7. [ ] Slide (gesture) typing works in RN
8. [ ] Text input works via hidden TextInput
9. [ ] Ctrl/Alt/Shift/Caps/Fn all functional
10. [ ] APK builds and installs
11. [ ] Tested as system keyboard on device
