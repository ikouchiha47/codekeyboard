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
   - `@react-native-community/datetimepicker` style pill buttons.
   - **Language packs** — word lists loaded from external files, not hardcoded.
   - Each language pack is a compressed trie file (~50k words) shipped as a separate asset or downloaded on first use.
   - Pack format: serialized trie (JSON or binary) for O(n) prefix lookup.
   - Tap suggestion → replace current word in buffer.
4. **Slide Typing (Gesture Typing)**.
   - `PanResponder` or `react-native-gesture-handler` on the keyboard surface.
   - Track pointer movement across key boundaries using key-center hit testing.
   - Record key sequence from touch path.
   - Use gesture-to-word matching via levenshtein distance on the path against dictionary.
   - Show predicted word in suggestion bar as user slides.
   - On lift: insert best-match word.
5. **Android IME Integration**.
   - Register as an input method via `InputMethodService`.
   - Use a hidden `TextInput` with `showSoftInputOnFocus={false}`.
   - Send key events to the focused text field via `InputConnection`.
6. **Modifier State Management**.
   - `useReducer` for state: ctrl, alt, shift, caps, fn, currentLayer.
   - Key press → resolve character → insert/replace/delete.
7. **Key Repeat**.
   - `onPressIn` starts interval, `onPressOut` clears it.
8. **Word Prediction / ML (Future)**.
   - Phase 2a: dictionary-based prefix matching (from language packs).
   - Phase 2b: n-gram language model built from user's typed corpus.
   - Phase 2c: small on-device ML model (TFLite) for next-word prediction.
   - Model trained on code comments and technical prose.
   - Language packs, dictionaries, and ML models all loaded as external assets — zero hardcoded data.
9. **Rendering Performance**.
   - Use `React.memo` on keys, `Animated` for press feedback.
   - Avoid re-renders of full layout on modifier toggle.
10. **Build APK**.
    - `npx react-native build-android` → unsigned APK or AAB.

### Deliverable
- Android APK that can be sideloaded and set as default keyboard.

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
