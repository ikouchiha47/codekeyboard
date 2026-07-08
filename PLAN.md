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
1. Create a full keyboard layout using CSS Grid.
   - Rows: number row, QWERTY top, home, bottom, spacebar row.
   - Modifier keys: Ctrl, Alt, Shift, Caps, Fn.
   - Navigation: arrows, Tab, Esc, Backspace, Enter.
   - Extra: symbols layer toggle.
2. Implement JS logic:
   - Track `ctrlKey`, `altKey`, `shiftKey`, `capsLock`, `fnLayer` state.
   - `ctrl+c`, `ctrl+v`, `ctrl+x`, `ctrl+z` map to clipboard/undo.
   - Alt key produces special characters (Alt+a = α, etc.).
   - Shift/Caps modify letter case.
   - Fn layer shows symbols layer (numbers → symbols).
3. Text input via `<textarea>` or `contenteditable`.
   - Keystrokes insert/delete text programmatically.
4. Test all key combinations, touch/click interactions, layout scaling.

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
3. **Android IME Integration**.
   - Register as an input method via `InputMethodService`.
   - Use a hidden `TextInput` with `showSoftInputOnFocus={false}`.
   - Send key events to the focused text field.
4. **Modifier State Management**.
   - `useReducer` for state: ctrl, alt, shift, caps, fn, currentLayer.
   - Key press → resolve character → insert/replace/delete.
5. **Key Repeat**.
   - `onPressIn` starts interval, `onPressOut` clears it.
6. **Rendering Performance**.
   - Use `React.memo` on keys, `Animated` for press feedback.
   - Avoid re-renders of full layout on modifier toggle.
7. **Build APK**.
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

## Milestones

1. [ ] Web prototype renders correctly in browser
2. [ ] All modifier keys work in web prototype
3. [ ] RN project scaffolds and builds
4. [ ] Keyboard layout renders in RN
5. [ ] Text input works via hidden TextInput
6. [ ] Ctrl/Alt/Shift/Caps/Fn all functional
7. [ ] APK builds and installs
8. [ ] Tested as system keyboard on device
