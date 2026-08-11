# ADR-003: Advanced Input Features — Permissive Hold, Repeat Key, Key Overrides, Tap Dance, Sentence Case, Caps Word

## Context

CodeKeyboard's current input model covers tap, hold-tap (timer), and dedicated-modifier (immediate) paths.
Several ZMK features that power-users rely on are missing:

- **Permissive Hold** — home-row mods mis-fire when rolling keys quickly; the timer path alone is not enough
- **Repeat Key** — no way to replay the last keystroke without finding and re-tapping the key
- **Key Overrides** — Shift+Backspace should produce Delete; no general remapping at the combo level exists
- **Tap Dance** — single key, multiple tap-count actions (e.g. `(` / `[` / `{` on one key)
- **Sentence Case** — auto-capitalise after sentence-ending punctuation + space
- **Caps Word** — auto-shift letters until a word boundary (space or punctuation) exits the mode

## Goals

1. Each feature adds ≤ 2 new files and touches the minimum set of existing files.
2. Permissive Hold must not regress typing speed for non-mod keys.
3. Sentence Case and Caps Word must integrate cleanly with the existing latch/lock shift state.
4. Features are individually toggleable (settings flag per feature).
5. No Android SDK calls inside `KeyboardState` — stays pure-JVM testable.

## Architecture

### A. Permissive Hold

**What**: In the holdTap timer path (`holdTapRunnable`), if another key is fully pressed-and-released while the hold-tap key is still down, fire the hold immediately without waiting for the timer.

**Where**: `NativeKeyboardView.kt` — ACTION_DOWN regular-key branch already sets `otherKeyPressed` on confirmed mod-holds. Add the symmetric check: when a regular key's ACTION_UP fires and `holdTapKeyDef != null` and `!holdTapFired`, fire `onKeyHeld(holdTapKeyDef)` immediately (permissive hold) instead of waiting for the timer.

**Toggle**: `KeyboardSettings.permissiveHold: Boolean` read in `NativeKeyboardView`.

### B. Repeat Key

**What**: A `KeyDef` with `action = "repeat"`. On tap, CodeKeyboardIME replays the last committed key event.

**Where**:
- `CodeKeyboardIME.kt` — add `lastKeyEvent: Pair<String, Set<String>>?` tracking the last `(action, activeModifiers)` pair from `handleKey`. On `action == "repeat"`, call `handleKey` with the stored pair.
- Key data files — add a `Repeat` key wherever desired (e.g. top-row slot).

**Edge cases**: Repeat is a no-op if `lastKeyEvent` is null or if the last action was a layer/modifier key.

### C. Key Overrides

**What**: A priority-ordered list of `KeyOverride(modifiers: Set<String>, inputAction: String, outputAction: String)`. Applied in `CodeKeyboardIME.handleKey` before any other dispatch.

**Where**:
- New file `KeyOverrides.kt` — defines the override table and a `resolve(action, activeModifiers): String?` function.
- `CodeKeyboardIME.kt` — call `KeyOverrides.resolve(action, kbState.activeModifierNames)` at the top of `handleKey`; if non-null, substitute the action and consume the modifier (clear latch).

**Built-in overrides** (hardcoded defaults, user-extensible later):

| Modifiers | Input | Output |
|---|---|---|
| Shift | backspace | delete |

### D. Tap Dance

**What**: A `TapDanceDef(actions: List<String>, holdAction: String?)` associated with a `KeyDef`. Tapping the key N times within `TAP_DANCE_WINDOW_MS` (default 200ms) selects `actions[N-1]`.

**Where**:
- New file `TapDanceDef.kt` — data class + registry (`object TapDanceRegistry`).
- `KeyDef.kt` — add optional `tapDanceId: String?` field.
- `NativeKeyboardView.kt` — in the regular-key DOWN branch, if `key.tapDanceId != null`, route to a new `TapDanceTracker` instead of firing immediately.
- New file `TapDanceTracker.kt` — per-key tap counter with a `postDelayed` commit timer.

**Interaction with hold-tap**: A tap dance key with a `holdAction` uses the existing timer path for hold detection; tap-count accumulation only applies to quick taps.

### E. Sentence Case

**What**: After committing a character that is `.`, `!`, or `?`, the *next* character input (after optional whitespace) is automatically upper-cased.

**Where**:
- `KeyboardState.kt` — add `sentenceCaseArmed: Boolean`. Set to `true` in `onCharCommitted` when the committed char is a sentence-ender. Clear on any non-space, non-punct character commit (after applying the case).
- `CodeKeyboardIME.kt` — when `kbState.sentenceCaseArmed` and the incoming action is a letter, force uppercase for that one character then clear `sentenceCaseArmed`.

**Toggle**: `KeyboardSettings.sentenceCase: Boolean`.

### F. Caps Word

**What**: A mode where every letter is automatically shifted until the first word boundary (space, punctuation, or Enter). Activated by double-tapping Shift.

**Where**:
- `KeyboardState.kt` — add `capsWord: Boolean`. Activated via `cycleModifier("caps-word")` (new entry). In `resolveLabel`, if `capsWord`, treat letters as shifted. In `onCharCommitted`, clear `capsWord` if the committed char is a space or punctuation.
- `NativeKeyboardView.kt` — draw Caps Word active state visually (accent bar on Shift key, same mechanism as other active modifiers).

**Interaction with Shift latch**: Caps Word is mutually exclusive with the shift latch; activating one clears the other.

## Files

| File | Change | Role |
|---|---|---|
| `KeyboardState.kt` | Modify | sentenceCaseArmed, capsWord fields; cycleModifier("caps-word"); onCharCommitted updates |
| `NativeKeyboardView.kt` | Modify | Permissive hold in holdTap path; tap dance routing; capsWord visual state |
| `CodeKeyboardIME.kt` | Modify | lastKeyEvent tracking; Key Override call; repeat dispatch; sentenceCase enforcement |
| `KeyOverrides.kt` | New | Override table + resolve() |
| `TapDanceDef.kt` | New | TapDanceDef data class + TapDanceRegistry |
| `TapDanceTracker.kt` | New | Per-key tap counter with commit timer |
| `KeyDef.kt` | Modify | Optional tapDanceId field |
| `KeyboardSettings.kt` | Modify | permissiveHold, sentenceCase toggle flags |
| `SofleKeyData.kt` | Modify | Add Repeat key; add tap dance IDs where wanted |
| `FerrisSweepBaseLayerProvider.kt` | Modify | Same as above for Ferris layout |

## Consequences

- Permissive Hold eliminates the main pain point for home-row mod users without touching the layer-hold path.
- Repeat Key is zero-cost when not used; last-event tracking adds one field to IME state.
- Key Overrides at the dispatch level means they compose cleanly with all layers and mods.
- Tap Dance adds a 200ms delay on first tap of a tap-dance key (same tradeoff as ZMK). Non-tap-dance keys are unaffected.
- Sentence Case and Caps Word share the existing `onCharCommitted` hook — no new event surface.
- Each feature is independently shippable; they do not depend on each other.

## Workflow

### Tasks

| ID | Task |
|---|---|
| A | `KeyboardState.kt` — add sentenceCaseArmed, capsWord, cycleModifier("caps-word"), onCharCommitted updates |
| B | `KeyOverrides.kt` — new file: override table + resolve() |
| C | `TapDanceDef.kt` + `TapDanceTracker.kt` — new files |
| D | `KeyDef.kt` — add tapDanceId field |
| E | `KeyboardSettings.kt` — add permissiveHold, sentenceCase flags |
| F | `NativeKeyboardView.kt` — permissive hold in holdTap path |
| G | `NativeKeyboardView.kt` — tap dance routing (needs C, D) |
| H | `NativeKeyboardView.kt` — capsWord visual state (needs A) |
| I | `CodeKeyboardIME.kt` — lastKeyEvent tracking + repeat dispatch |
| J | `CodeKeyboardIME.kt` — Key Override call (needs B) |
| K | `CodeKeyboardIME.kt` — sentenceCase enforcement (needs A) |
| L | Key data files — Repeat key + tap dance IDs (needs D) |

### Dependency edges

- B, C, D, E: no dependencies (Wave 0)
- A: no dependencies (Wave 0)
- F: needs E
- G: needs C, D
- H: needs A
- I: no dependencies (Wave 0)
- J: needs B
- K: needs A
- L: needs D

### Wave table

| Wave | Tasks | Parallelisable? |
|---|---|---|
| 0 | A, B, C, D, E, I | yes |
| 1 | F (needs E), G (needs C+D), H (needs A), J (needs B), K (needs A), L (needs D) | yes |

### Critical path

A → H → (visual verification) — shortest blocking chain; all others are leaf nodes at wave 1.

### Blocking notes

- G (tap dance routing) requires careful interaction with the existing holdTap timer — test home-row mods after this change.
- K (sentence case) must not activate inside password fields; gate on `supportsComposing`.
