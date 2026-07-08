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

## Touch Behaviour

- **Tap**: insert character, play popup.
- **Double-tap**: on modifiers = lock; on space = insert period+space (like Gboard).
- **Hold**: after 200ms, enter key repeat mode (30ms interval for text keys, 50ms for backspace/arrows).
- **Slide**: from modifier → key → release → triggers combo. (E.g., slide from Ctrl to C = Ctrl+C without releasing.)

---

## Text Input

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
