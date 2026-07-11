# Gesture Architecture — Native IME

## Overview

The native keyboard view distinguishes tap, double-tap, and long-press
through **timing**, not state machines cycling blind. Each gesture fires
on a different event (DOWN vs UP vs timer), so they are mutually exclusive
by definition.

## The Three Gesture Axes

| Gesture | Pattern | Detected when |
|---|---|---|
| Tap | DOWN → UP (fast) | On UP, before long-press threshold |
| Double-tap | DOWN→UP→DOWN→UP (fast ×2) | On 2nd UP via `TapMachine` |
| Long-press | DOWN → hold → (UP) | Timer fires while finger still down |

They cannot conflict:
- Long-press ≠ tap (finger never lifted)
- Double-tap ≠ long-press (two distinct releases, no hold)
- Tap-dance (future) = N taps, all released before long-press threshold

The one design tension: on a **hold-tap** key (home row mod, thumb layer),
you don't know at DOWN whether it will be a tap (→ character) or hold
(→ modifier). See "Hold-tap (future)" below.

## Pipeline

```
          NativeKeyboardView
          (Touch handler)

MotionEvent ─────────────────────────────► hitTest → find KeyDef
  ACTION_DOWN                                    │
    │                                            │
    ├─ Fire key immediately (backspace,           │
    │  regular keys)                              │
    │                                             │
    ├─ Start long-press timer                     │
    │   (backspace auto-repeat,                   │
    │    future hold-tap)                         │
    │                                             │
    └─ Record key, position, time                 │
                                                  │
MotionEvent                                       │
  ACTION_UP ──────────────────────────────────────┤
    │                      ┌──────────┐           │
    ├── if fast ──────────►│ TapMachine│◄─────────┘
    │                      │ .check() │
    │                      │          │──► SINGLE → LATCHED
    │                      │          │──► DOUBLE → LOCKED
    │                      └──────────┘
    │
    └── if slow ──────────► cancel, no tap

MotionEvent
  ACTION_MOVE ────────────► off-key? ──► cancel all
```

## Long-press timer

Started on DOWN for keys that support it. Fires while finger is still
down. On timer expiry the key repeats (backspace) or switches action
(hold-tap). Cancelled on UP (tap path wins) or MOVE off-key.

## TapMachine

Pure-Kotlin, no Android dependency. One instance per key action.

```kotlin
class TapMachine(private val doubleTapMs: Long = 300L) {
    fun check(name: String, now: Long): Boolean  // true if double-tap
    fun reset()
}
```

- Stores last tap key identity + timestamp.
- `check()` returns `true` when same key is tapped twice within window.
- `reset()` clears state (called on LOCKED, on char commit, on layer
  switch, on app reset).

## How double-tap → LATCHED / LOCKED works

`KeyboardState` owns one `TapMachine` per latchable action (layer, shift,
ctrl, alt). On each key UP:

1. Read `System.currentTimeMillis()`.
2. Call `tapMachine.check(keyName, now)`.
3. If double-tap → set `LOCKED`, reset that `TapMachine`.
4. If single tap → toggle `NONE ↔ LATCHED`.

```kotlin
fun cycleLayer(name: String): Boolean {
    if (locked && same_layer) → unlock
    if (double_tap)           → lock
    if (latched && same_layer)→ unlock
    if (latched && diff_layer)→ switch layer, latch new
    else                      → latch
}
```

`onCharCommitted()` resets all `TapMachine` instances so a slow subsequent
tap on the same key starts fresh.

## Decision tree per key action

| Action | Fire on DOWN? | Double-tap? | Long-press? | Tap-dance? |
|---|---|---|---|---|---|
| `backspace` | Yes (1 char) | No | Yes (auto-repeat) | No |
| `tab`, `escape`, `enter` | Yes | No | No | No |
| letter keys | Yes (unless holdAction) | No | Hold-tap if holdAction set | Future |
| `shift`, `ctrl`, `alt` | Yes (LATCHED) | Yes (→ LOCKED) | No | No |
| `lower`, `raise`, `adj`, `func` | Yes (latches layer) | Yes (→ LOCKED) | No | No |
| `space` | Yes (unless holdAction) | No | Hold-tap if holdAction set | Future |
| `caps` | Toggle LOCKED | No | No | No |
| `meta` | Yes | Future | No | No |
| hold-tap key (any) | No (starts timer) | Yes (via TapMachine on tap path) | Yes (150ms → HOLD) | Future |

## Hold-tap (implemented)

Hold-tap keys (home row `a`/`s`/`d`/`f` etc., thumb `Spc`) defer the
action. Instead of firing on DOWN, they start a `tapping-term-ms` timer:

- **Released before timer**: TAP action (character/key's normal action).
- **Timer fires while held**: HOLD action (modifier/layer).

This adds `tapping-term-ms` latency to the tap (150ms default,
configurable later).

### How it works

`KeyDef.holdAction` marks a key as hold-tap. The `NativeKeyboardView`
touch handler checks this field on DOWN:

1. **holdAction == null** — fire `onKeyTapped` immediately (original behavior).
2. **holdAction != null** — start a 150ms `Handler` timer. On UP before
   expiry → fire `onKeyTapped` (TAP). On timer expiry → fire `onKeyHeld`
   (HOLD), mark `holdTapFired = true`. On UP after HOLD → fire
   `onKeyReleased` to deactivate the transient state.

### State management

`KeyboardState` tracks transient state separately from latched/locked:

- `layerHeld: String?` — set when a layer hold fires, cleared on release.
- `ctrlHeld`, `shiftHeld`, `altHeld`, `metaHeld: Boolean` — same for mods.
- `effectiveLayer` — returns `layerHeld ?: layer` (held layer takes priority).
- `isCtrlActive`, etc. — OR the permanent LATCHED/LOCKED with held state.

`onCharCommitted()` does **not** clear held state — only the touch handler
lifecycle (UP/MOVE off-key) does, via `onKeyReleased`.

### Hold-tap key annotations (Sofle BASE layer)

Home row mods: a→ctrl, s→meta, d→alt, f→shift, h→shift, j→alt, k→meta, l→ctrl.
Thumb layer-holds: left Spc→lower, right Spc→raise.

### Compatibility

`tapping-term-ms` (150ms) and `doubleTapMs` (300ms) are independent:
- A fast double-tap on a hold-tap key works: first tap completes (UP
  before 150ms) and `TapMachine` records it; second tap is detected as
  double-tap by `TapMachine` in `KeyboardState`.
- A hold (held >150ms) on a hold-tap key fires the HOLD action once;
  subsequent taps on other keys behave as if the modifier/layer is active.

## Tap-dance (future)

`TapMachine` extends naturally — same class, no conflict:

```kotlin
sealed class TapResult {
    object SINGLE : TapResult()
    object DOUBLE : TapResult()
    data class TAP_N(val count: Int) : TapResult()  // tap-dance
}
```

Tap-dance keys don't fire on first UP. They wait for the double-tap
window to expire, then fire. This means **tap-dance and double-tap use
the same timer** — they are the same mechanism, just with a different
response to `count`.

## File ownership

| File | Role |
|---|---|
| `KeyboardLayout.kt` | `KeyDef`, `KeyRect`, `PositionedKey`, `SofleLayerData`, `KeyboardLayoutComputer` — no Android imports. `KeyDef.holdAction` for hold-tap annotation. |
| `TapMachine.kt` | Pure-Kotlin double-tap detector. One per latchable action. |
| `KeyboardState.kt` | Latch/lock state machine. Owns TapMachine instances. Plus `applyHold`/`releaseHold` for transient hold-tap state, `effectiveLayer`. Pure Kotlin. |
| `NativeKeyboardView.kt` | Canvas renderer + touch handler. Converts MotionEvent → hit test → callback. HoldTapTracker (Handler-based, 150ms TAPPING_TERM_MS) defers hold-tap keys. Auto-repeat for backspace/delete. |
| `SofleKeyData.kt` | Layer definitions (5 layers, V5 layout). Home row mods + thumb layer-holds annotated with `holdAction`. Pure Kotlin. |
| `SofleLayoutComputer.kt` | Geometry calculator. Exports `holdAction` in JSON. Pure Kotlin (+ `density` float constructor param). |

## Current state

- Tap: fires on DOWN via `onKeyTapped` callback. Works for all keys.
- Double-tap: 4 `TapMachine` instances in `KeyboardState` handle layer,
  shift, ctrl, alt. Tested with 21 state + 9 TapMachine tests.
- Long-press auto-repeat (backspace, delete): `Handler`-based timer,
  400ms initial delay then 50ms repeat, cancels on UP or MOVE off-key.
  Tracked per pointer ID for multi-touch safety.
- Hold-tap: `KeyDef.holdAction` + `HoldTapTracker` in `NativeKeyboardView`.
  150ms `TAPPING_TERM_MS` timer on DOWN; released before timer → TAP,
  timer fires while held → HOLD. `KeyboardState.applyHold`/`releaseHold`
  manage transient modifier/layer state (`ctrlHeld`, `shiftHeld`, etc.,
  and `layerHeld`). Home row mods on BASE layer (a/s/d/f/h/j/k/l) and
  thumb layer-holds (both Spc keys) are annotated.
- Tap-dance: designed but not implemented. Same mechanism as double-tap
  with more counters.
