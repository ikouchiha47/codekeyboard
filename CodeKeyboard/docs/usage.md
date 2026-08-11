# CodeKeyboard — Key Reference

CodeKeyboard uses a Sofle-inspired compact layout with 5 layers. Most keys behave
differently depending on which layer is active, and many keys have a hold action
separate from their tap action.

---

## Activating layers

| Key | Tap | Hold |
|---|---|---|
| LWR | toggle Lower layer | momentary Lower (held only) |
| RSE | toggle Raise layer | momentary Raise (held only) |
| FUNC | toggle Func layer | momentary Func (held only) |
| ADJ | toggle Adjust layer | momentary Adjust (held only) |
| Space (left thumb) | Space | momentary Lower |
| Space (right thumb) | Space | momentary Raise |

Momentary means the layer is active only while the key is held. Toggle (tap) latches the
layer until you tap the key again.

LWR + RSE held together = Adjust layer.

---

## Home-row mods (BASE layer only)

The middle four keys on each home row double as modifiers when held. There is a 150ms
timer to separate a quick letter tap from a held modifier.

| Key | Tap | Hold |
|---|---|---|
| A | a | Ctrl |
| S | s | Meta (Cmd/Win) |
| D | d | Alt |
| F | f | Shift |
| H | h | Shift |
| J | j | Alt |
| K | k | Meta |
| L | l | Ctrl |

Type quickly through these keys and the letters come out. Hold one and press another key
to send the modifier combination.

---

## BASE layer

Top row (left to right): Tab, Esc, `` ` ``, `^`, Ctrl, Alt, Emoji, Backspace

```
Left                          Right
q  w  e  r  t                y  u  i  o  p
a  s  d  f  g                h  j  k  l  ;/:
z  x  c  v  b                n  m  ,/<  ./>  Bksp
Shift  Spc  LWR  Ctrl  Alt   RSE  Enter  Spc  FUNC  ADJ
```

- `;` taps as `;`, shift taps as `:`
- `,` taps as `,`, shift taps as `<`
- `.` taps as `.`, shift taps as `>`
- Emoji key opens the emoji panel

---

## LOWER layer (LWR)

Numbers, brackets, and common symbols.

Top row: Tab, Esc, `(`, `)`, `[`, `]`, `{`, `}`

```
Left                               Right
1/!  2/@  3/#  4/$  5/%            6/^  7/&  8/*  9/(  0/)
`    -/_  =/+  [/{  ]/}            //?  :  '/\"  <    >
~    \|   (    )    '/"            !    @    #    $    Del
Shift  Spc  LWR  Esc  Tab          RSE  Enter  Spc  FUNC  ADJ
```

Keys with two values: tap = left value, shift = right value (e.g. `1` taps as `1`, with
Shift taps as `!`).

---

## RAISE layer (RSE)

Function keys, navigation, and editing shortcuts.

Top row: Tab, Esc, F1, F2, F3, F4, F5, F6

```
Left                                    Right
F7   F8   F9   F10  F11                 Left  Down  Up   Right  PgDn
F12  Ins  Home PgUp PgDn               Home  End   PgUp PgDn   -
End  Cut  Copy Paste Undo              Cut   Copy  Paste Undo   Bksp
Shift  Spc  LWR  Ctrl  Alt             RSE  Enter  Spc  FUNC  ADJ
```

---

## FUNC layer (FUNC)

Editor actions — intended for IDE and terminal use.

Top row: Tab, Esc, Undo, Redo, Cut, Copy, Paste, SelectAll

```
Left                                Right
Save  Find  Replace  Comment  Dup   -     -     -     -     -
Fmt   -     -        -        -     -     -     -     -     -
-     -     -        -        -     -     -     -     -     Bksp
Shift  Spc  LWR  -   -               RSE  Enter  Spc  FUNC  ADJ
```

---

## ADJUST layer (ADJ)

Media and system controls. Reached by holding LWR + RSE, or by tapping ADJ.

Top row: Tab, Esc, Brightness-, Brightness+, Mute, Vol-, Vol+, Play

```
Left                          Right
Prev  Play  Next  -   -       -    -    -    -    -
-     -     -     -   -       BT   WiFi -    -    -
-     -     -     -   -       -    -    -    -    Bksp
Shift  Spc  LWR  -   -        RSE  Enter  Spc  FUNC  ADJ
```

BT and WiFi keys send system quick-settings intents (toggle behaviour depends on Android
version and OEM).

---

## Dedicated modifier keys

These activate on key-down, not key-up. That means the modifier is live before you press
the next key — useful for fast combos.

| Key | Tap | Hold |
|---|---|---|
| Shift | latch shift (one char), double-tap = caps lock | held shift |
| Ctrl (top row / thumb) | latch Ctrl | held Ctrl |
| Alt (top row / thumb) | latch Alt | held Alt |

Shift latch auto-clears after the next committed character.

---

## Snippets

Type `;shortcode` in any text field. The suggestion bar shows the expansion. Tap to
commit the full expansion in place of the shortcode.

Shortcodes are managed in the app's Settings tab. They are stored locally and never
leave the device.

---

## Emoji panel

Tap the Emoji key (top-right of top row on BASE layer). A grid panel replaces the
keyboard. Tap any emoji to insert it. Tap the Emoji key again or press Backspace to
dismiss.

Categories scroll horizontally across the top of the panel.
