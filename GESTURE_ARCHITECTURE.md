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
|---|---|---|---|---|
| `backspace` | Yes (1 char) | No | Yes (auto-repeat) | No |
| `tab`, `escape`, `enter` | Yes | No | No | No |
| letter keys | Yes | No | Future (hold→mod) | Future |
| `shift`, `ctrl`, `alt` | Yes (LATCHED) | Yes (→ LOCKED) | No | No |
| `lower`, `raise`, `adj`, `func` | Yes (latches layer) | Yes (→ LOCKED) | No | No |
| `space` | Yes | No | Future (hold→layer) | Future |
| `caps` | Toggle LOCKED | No | No | No |
| `meta` | Yes | Future | No | No |

## Hold-tap (future)

Hold-tap keys (home row `a`/`s`/`d`/`f` etc., thumb `Spc`) defer the
action. Instead of firing on DOWN, they start a `tapping-term-ms` timer:

- **Released before timer**: TAP action (character).
- **Timer fires while held**: HOLD action (modifier/layer).

This adds `tapping-term-ms` latency to the tap (50–100ms default,
configurable later).

The same `GesturePipeline` handles this: a static `IS_HOLD_TAP` flag on
`KeyDef` (not yet defined) tells the touch handler to defer.

### Compatibility with present code

`tapping-term-ms` and `doubleTapMs` are independent:
- `tapping-term-ms` (50–100ms) = per-hold decision on a single DOWN.
- `doubleTapMs` (300ms) = window between two separate UP events in
  `TapMachine`.

Since 50–100ms << 300ms, a fast double-tap is still detected as double-tap
(the first tap completes before the hold timer fires). Present `TapMachine`
and `KeyboardState` are unaffected — `KeyDef` has no hold-tap field yet,
so all keys still fire on DOWN as before.

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
| `KeyboardLayout.kt` | `KeyDef`, `KeyRect`, `PositionedKey`, `SofleLayerData`, `KeyboardLayoutComputer` — no Android imports |
| `TapMachine.kt` | Pure-Kotlin double-tap detector. One per latchable action. |
| `KeyboardState.kt` | Latch/lock state machine. Owns TapMachine instances. Pure Kotlin. |
| `NativeKeyboardView.kt` | Canvas renderer + touch handler. Converts MotionEvent → hit test → callback. |
| `SofleKeyData.kt` | Layer definitions (5 layers, V5 layout). Pure Kotlin. |
| `SofleLayoutComputer.kt` | Geometry calculator. Pure Kotlin (+ `density` float constructor param). |

## Current state

- Tap: fires on DOWN via `onKeyTapped` callback. Works for all keys.
- Double-tap: 4 `TapMachine` instances in `KeyboardState` handle layer,
  shift, ctrl, alt. Tested with 21 state + 9 TapMachine tests.
- Long-press auto-repeat (backspace, delete): `Handler`-based timer,
  400ms initial delay then 50ms repeat, cancels on UP or MOVE off-key.
  Tracked per pointer ID for multi-touch safety.
- Hold-tap: designed but not implemented. Needs `tapping-term-ms`, a
  `IS_HOLD_TAP` flag on `KeyDef`, and deferred action dispatch.
- Tap-dance: designed but not implemented. Same mechanism as double-tap
  with more counters.
