# UX Design – Codeboard Keyboard

## Overview
A mobile keyboard designed specifically for typing code. Prioritises speed, accuracy, and easy access to modifier keys and symbols that programmers use constantly.

---

## Layout

### Row 1 – Numbers & Symbols
```
Esc  `   1  2  3  4  5  6  7  8  9  0  -  =  Bksp
```
- Tap number → inserts number.
- Shift or Fn + tap → inserts symbol (! @ # $ % ^ & * ( ) _ +).
- Esc dismisses keyboard or cancels selection.
- Bksp deletes one char left; hold for repeat.

### Row 2 – Top Alpha (QWERTY)
```
Tab  q  w  e  r  t  y  u  i  o  p  [  ]  \
```
- Tab inserts 4 spaces (configurable).
- `[ ] { }` on bracket keys – `[`/`]` base, `Shift` = `{`/`}`.

### Row 3 – Home Row
```
Caps  a  s  d  f  g  h  j  k  l  ;  '  Enter
```
- Caps: tap to toggle uppercase lock; hold for momentary shift.
- `;` and `'` common in code. Enter inserts newline.

### Row 4 – Bottom Alpha + Modifiers
```
Shift  z  x  c  v  b  n  m  ,  .  /  Shift
```
- Two Shift keys for reachability.
- Hold Shift = momentary; tap = latch until next alpha.
- `,` `.` `/` base; `Shift` → `<` `>` `?`.

### Row 5 – Spacebar + Modifiers
```
Ctrl  Alt  Fn  [spacebar]  Alt  Ctrl  ←  ↑  ↓  →
```
- **Ctrl** and **Alt** on both sides.
- Tap = latch key (lights up); hold = momentary.
- **Fn** toggles symbols/number layer.
- **Arrow keys** for cursor navigation.
- Spacebar: large, centred. Hold for cursor mode (like Gboard).

---

## Modifier Key Behaviour

### Ctrl (Latching + Momentary)
- **Tap**: latches (highlighted). Next key press sends Ctrl+key combo, then unlatches.
- **Double-tap**: locks on (stays highlighted until tapped again).
- **Hold + press another key**: momentary combo, releases after.
- Combos: Ctrl+C (copy), Ctrl+V (paste), Ctrl+X (cut), Ctrl+Z (undo), Ctrl+A (select all), Ctrl+S (save), Ctrl+F (find), Ctrl+D (duplicate line), Ctrl+/ (toggle comment).

### Alt (Latching + Momentary)
- Same latch/lock behaviour as Ctrl.
- Alt+key inserts special chars: Alt+a → α, Alt+b → β, Alt+e → €, Alt+n → ñ, Alt+1 → ¹, etc.
- Alt+arrows → word-jump left/right, line-home/line-end.

### Shift
- Tap = latch for one alpha (next letter is uppercase, then reverts).
- Double-tap = Caps Lock.
- Hold = momentary shift.
- Shift modifies all symbol keys: `1→!`, `2→@`, `[→{`, etc.

### Fn (Layer Toggle)
- Taps toggle between base layer and symbols layer.
- Symbols layer: number row becomes F1–F12; some letters become common symbols.
- Fn+arrows → Home/End/PgUp/PgDn.

---

## Visual Feedback

### Key States
| State | Appearance |
|---|---|
| Idle | Flat, dark key, light text |
| Pressed | Brighter or inverted; slight scale-down (0.95x) |
| Latched (modifier) | Blue tint, indicator dot bottom-right |
| Locked (modifier) | Blue fill, bold outline |
| Disabled | Dimmed, no touch response |

### Popup
- Brief char preview above finger on tap (like Gboard).
- Shows the character that will be inserted.

### Animations
- Press animation: 80ms scale + colour.
- Layer switch: 150ms fade cross-fade.
- Modifier latch: 100ms colour transition.

---

## Suggestion Bar

A fixed bar between the text output and the keyboard showing word predictions.

```
 ┌─────────────────────────────────┐
 │  function   variable   return   │
 └─────────────────────────────────┘
```

### Behavior
- Shows 3 word predictions based on the current word prefix and preceding context.
- Leftmost suggestion is the most likely; rightmost is the least likely.
- Tapping a suggestion:
  - Replaces the current partial word with the full suggestion.
  - Inserts a trailing space (configurable).
- As user types each letter, suggestions update in real-time (< 16ms latency).
- If no suggestions match, the bar shows the current word in gray (fallback).
- Suggestion bar has a subtle divider line separating it from the keyboard.

### Language Packs (External, Not Hardcoded)
- Word lists are **never hardcoded** in the app binary.
- Shipped as external **language pack files** — compressed trie files loaded at runtime.
- Default pack: ~50,000 English words (common + technical/coding terms).
- Pack format: serialized trie (JSON or binary) for O(n) prefix lookup where n = prefix length.
- Downloaded on first launch; stored in app data directory.
- Users can install additional language packs (e.g., Spanish, French, German, or code-specific packs).
- Additional programming keywords included in the default pack: `function`, `const`, `let`, `var`, `return`, `import`, `export`, `class`, `interface`, `type`, `async`, `await`, `if`, `else`, `for`, `while`, `switch`, `case`, `break`, `continue`, `try`, `catch`, `throw`, `true`, `false`, `null`, `undefined`, `NaN`, `this`, `super`, `new`, `delete`, `typeof`, `instanceof`, `void`, `yield`, `from`, `of`, `in`, `as`, `is`, `keyof`, `readonly`, `static`, `public`, `private`, `protected`, `abstract`, `implements`, `extends`, `enum`, `module`, `namespace`, `declare`, `get`, `set`, `then`, `catch`, `finally`.

### Priority Scoring
Suggestions ranked by:
1. **Exact prefix match** — word starts with typed characters.
2. **Frequency** — words used more often rank higher.
3. **Context** — (future) next-word prediction from n-gram model.
4. **Recency** — words recently used by the user.

---

## Slide Typing (Gesture Typing)

User slides finger across keys without lifting, and the predicted word appears in the suggestion bar.

``` 
 [touch down on 'g'] → slide to 'e' → slide to 't' → [lift]
 → suggestion bar shows: "get"  "gets"  "getting"
```

### Touch Handling
- **onTouchStart** on the keyboard surface: record starting key.
- **onTouchMove**: each time the finger crosses into a new key's bounds, append to the key sequence.
- **onTouchEnd**: match the key sequence against the dictionary.

### Key Hit Detection
- Each key has a center point and bounding rect.
- During slide, the finger position is checked against key rects.
- To avoid jitter, apply a hysteresis: a key must be entered by > 30% of its width before it's registered.
- Same key repeated (finger lingering) = single entry in the sequence.

### Sequence-to-Word Matching
- Build a set of candidate words from the dictionary that match the key pattern.
- Use a Trie that maps key sequences to words (phone-keypad style but on QWERTY).
- Multiple keys map to multiple letters (each key = 1 specific letter on QWERTY, so it's a direct letter sequence).
- The finger path may overshoot or undershoot — use Damerau-Levenshtein distance (max edit distance 2) for fuzzy matching.
- Score candidates: exact match > edit distance 1 > edit distance 2 > frequency tiebreaker.

### Visual Feedback During Slide
- A translucent trail line follows the finger (rendered with SVG or Canvas).
- Keys passed through get a subtle "touched" highlight.
- The current best-guess word appears in the center of the suggestion bar as the user slides.
- On lift: either insert the selected word if user taps a suggestion, or auto-insert the best match.

### Modifier Interaction
- If a modifier key is latched/locked before sliding, the slide keys are modified:
  - Shift latched → all letters in the path are uppercased.
  - Fn latched → no slide typing (Fn is for symbols layer).
  - Ctrl/Alt latched → slide does not trigger word prediction; instead records a combo.

---

## Touch Behaviour

- Hidden `TextInput` element receives the keystrokes.
- System suggestions/autocorrect disabled.
- Keyboard handles all editing internally:
  - Insert char at cursor.
  - Move cursor with arrows.
  - Select text with Shift+arrows.
  - Backspace one char or selection.
- Clipboard integration via Ctrl+C/V/X/Z.
- Haptic feedback on key press (configurable).

---

## Settings (Future)

| Setting | Options |
|---|---|
| Key popup | On / Off |
| Haptic feedback | On / Off / System default |
| Sound on keypress | On / Off |
| Tab size | 2 / 4 / 8 spaces |
| Key press repeat delay | 150 / 200 / 300 / 400 ms |
| Theme | Dark / Light / AMOLED / Custom |
| Keyboard height | Small / Medium / Large |
| Number row always visible | On / Off |
| Combo slide gesture | On / Off |
| Word suggestions | On / Off |
| Auto-space after suggestion | On / Off |
| Slide typing | On / Off |
| Slide sensitivity | Low / Medium / High |
| Dictionary language | English / (future: more) |

---

## Themes

### Default (Dark)
- Background: `#1e1e1e`
- Key: `#2d2d2d`
- Key pressed: `#4a4a4a`
- Text: `#d4d4d4`
- Modifier latched: `#264f78`
- Modifier locked: `#1a3a5c`
- Spacebar: slightly wider, `#333`

### Light
- Background: `#f0f0f0`
- Key: `#ffffff`
- Key pressed: `#e0e0e0`
- Text: `#1a1a1a`
- Modifier latched: `#cce5ff`
- Modifier locked: `#99ccff`

---

## Error Prevention

- Modifier keys show visual state clearly so user never guesses.
- Backspace has a short (80ms) grace period before entering repeat mode.
- No ambiguous key labels – every key shows what it inserts.
- Arrow keys separated from modifier keys by spacing.
