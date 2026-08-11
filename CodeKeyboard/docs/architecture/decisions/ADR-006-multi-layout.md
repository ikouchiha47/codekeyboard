# ADR-006: Multi-Layout and Multi-Keymap Architecture

**Status:** Proposed  
**Date:** 2026-08-11

---

## Context

The keyboard supports one physical layout (Sofle V5) and one key map (QWERTY), both hardcoded.
Two independent extension axes have been requested:

1. **Physical layout** — key count, stagger profile, thumb cluster shape (Sofle, Ferris Sweep, future: Corne, Piantor)
2. **Key map** — which character a key produces (QWERTY, Colemak, Dvorak, Programmer's Dvorak, Programmer's Colemak)

These axes are orthogonal: any key map should work on any physical layout.
The codebase already has the right seam (`KeyboardLayoutComputer` interface), but it is not used as a composition boundary — `SofleLayoutComputer` hardcodes both shape and key content, and is instantiated directly in the IME.

---

## Goals

- Adding a new physical layout = **2 new files, 1 registry entry, 0 existing file changes**
- Adding a new key map = **1 new file, 1 registry entry, 0 existing file changes**
- The IME, renderer, and touch engine are **blind to both axes** — they only see `KeyboardLayoutComputer`
- Settings UI composes any layout × any key map without special-casing

---

## Architecture

### Layer 0 — Key map strategy (`KeyMap`)

A key map is responsible only for translating alpha key labels on the base layer.
Non-alpha keys (numbers, symbols, modifiers, function keys, thumb cluster) are never remapped.

```kotlin
interface KeyMap {
    val id:   String   // "qwerty", "colemak", "dvorak", "programmer-dvorak"
    val name: String   // shown in Settings UI
    fun map(qwertyLabel: String): String  // identity for unrecognised labels
}
```

Implementations are pure objects with no Android dependencies — fully unit-testable.

```kotlin
object QwertyKeyMap : KeyMap {
    override val id   = "qwerty"
    override val name = "QWERTY"
    override fun map(q: String) = q
}

object ColemakKeyMap : KeyMap {
    override val id   = "colemak"
    override val name = "Colemak"
    private val TABLE = mapOf(
        "e"->"f", "r"->"p", "t"->"g", "y"->"j", "u"->"l", "i"->"u", "o"->"y", "p"->";",
        "s"->"r", "d"->"s", "f"->"t", "g"->"d",
        "j"->"n", "k"->"e", "l"->"i", ";"->"o",
        "n"->"k"
    )
    override fun map(q: String) = TABLE[q.lowercase()]
        ?.let { if (q[0].isUpperCase()) it.uppercase() else it } ?: q
}

// DvorakKeyMap, ProgrammerDvorakKeyMap, ProgrammerColemakKeyMap follow same pattern
```

**Registry:**

```kotlin
object KeyMapRegistry {
    val ALL: Map<String, KeyMap> = mapOf(
        "qwerty"             to QwertyKeyMap,
        "colemak"            to ColemakKeyMap,
        "dvorak"             to DvorakKeyMap,
        "programmer-dvorak"  to ProgrammerDvorakKeyMap,
        "programmer-colemak" to ProgrammerColemakKeyMap,
    )
    val DEFAULT = QwertyKeyMap
    fun get(id: String): KeyMap = ALL[id] ?: DEFAULT
}
```

---

### Layer 1 — Base layer provider (`BaseLayerProvider`)

A `BaseLayerProvider` owns the canonical QWERTY key definitions for one physical form factor.
It accepts a `KeyMap` and returns fully-remapped layer data.
It knows nothing about geometry.

```kotlin
interface BaseLayerProvider<T> {
    val supportedLayers: List<String>
    fun layers(keyMap: KeyMap): Map<String, T>
}
```

The type parameter `T` is the layout-specific layer data struct.
Each physical layout defines its own struct because the structs differ in shape:

```
Sofle:  topRow + left(4×5) + right(4×5)         — bottom row is just another staggered row
Ferris: topRow + left(3×5) + right(3×5) + thumbL(2) + thumbR(2)
```

Forcing them into one shared struct would require nullable/optional fields that model a lie.
Instead, the `BaseLayerProvider` is the boundary — callers only see `Map<String, T>`,
and the layout computer (which knows T) is the only consumer.

**Example — Sofle provider:**

```kotlin
class SofleBaseLayerProvider : BaseLayerProvider<SofleLayerData> {
    override val supportedLayers = listOf("base", "lower", "raise", "adj", "func")

    override fun layers(keyMap: KeyMap): Map<String, SofleLayerData> = mapOf(
        "base"  to base(keyMap),
        "lower" to LOWER,    // symbols/numbers — keymap-invariant
        "raise" to RAISE,
        "adj"   to ADJUST,
        "func"  to FUNC,
    )

    private fun base(km: KeyMap): SofleLayerData {
        fun alpha(q: String) = k(km.map(q))   // only alphas pass through the map
        return SofleLayerData(
            topRow = TOP_ROW,                  // shared constant, never remapped
            left = listOf(
                listOf(alpha("q"), alpha("w"), alpha("e"), alpha("r"), alpha("t")),
                listOf(alpha("a"), alpha("s"), alpha("d"), alpha("f"), alpha("g")),
                listOf(alpha("z"), alpha("x"), alpha("c"), alpha("v"), alpha("b")),
                listOf(k("Shift","shift"), k("Spc","space",hold="lower"), ...)
            ),
            right = ...
        )
    }
}
```

Lower/raise/func/adj layers contain no alpha keys — they are keymap-invariant constants.
Only the base layer's 3×5 alpha block (and optionally the home-row mod labels) goes through `km.map()`.

---

### Layer 2 — Layout computer (`KeyboardLayoutComputer`)

The existing interface is kept **unchanged**. Each physical layout's computer:
- Is constructed with `(density: Float, provider: BaseLayerProvider<T>)`
- Calls `provider.layers(keyMap)` at construction time to get the layer data it will render
- Owns all geometry: stagger, gap, thumb positions, height calculation

```kotlin
class SofleLayoutComputer(
    density: Float,
    provider: SofleBaseLayerProvider = SofleBaseLayerProvider(),
    keyMap:   KeyMap = QwertyKeyMap
) : KeyboardLayoutComputer {
    private val layerData = provider.layers(keyMap)
    // ... geometry unchanged
}

class FerrisSweepLayoutComputer(
    density: Float,
    provider: FerrisSweepBaseLayerProvider = FerrisSweepBaseLayerProvider(),
    keyMap:   KeyMap = QwertyKeyMap
) : KeyboardLayoutComputer {
    private val layerData = provider.layers(keyMap)

    // Ferris-specific geometry
    override val name   = "Ferris Sweep"
    internal val numRows = 3      // vs Sofle's 4
    internal val staggerLeft  = listOf(0f, 0.25f, 0.50f, 0.50f, 0.75f)  // flatter pinky
    internal val staggerRight = listOf(0.75f, 0.50f, 0.50f, 0.25f, 0f)
    internal val thumbKeyW    = ...   // 2 large thumb keys per side
    // heightPx accounts for: topRow + 3 main rows + stagger + thumbRow
}
```

Default parameter values mean existing call sites (`SofleLayoutComputer(density)`) still compile unchanged during migration.

---

### Layer 3 — Layout registry (DI composition root)

This is the single place that knows all layout and keymap names.
It composes them and hands a ready `KeyboardLayoutComputer` to whoever asks.

```kotlin
object LayoutRegistry {
    val LAYOUTS: Map<String, String> = mapOf(   // id -> display name
        "sofle"  to "Sofle V5",
        "ferris" to "Ferris Sweep",
    )
    val DEFAULT_LAYOUT = "sofle"

    fun build(layoutId: String, keyMapId: String, density: Float): KeyboardLayoutComputer {
        val keyMap = KeyMapRegistry.get(keyMapId)
        return when (layoutId) {
            "ferris" -> FerrisSweepLayoutComputer(density, FerrisSweepBaseLayerProvider(), keyMap)
            else     -> SofleLayoutComputer(density, SofleBaseLayerProvider(), keyMap)
        }
    }
}
```

**Adding a new layout:** create `XyzBaseLayerProvider`, `XyzLayoutComputer`, add one `when` branch.  
**Adding a new keymap:** create `XyzKeyMap`, add to `KeyMapRegistry.ALL`. No layout code changes.

---

### Layer 4 — IME wiring (`CodeKeyboardIME`)

Two lines change in `onCreateInputView`:

```kotlin
// Before
keyboardView.computer = SofleLayoutComputer(density)

// After
val layoutId = KeyboardSettings.getString("layout", LayoutRegistry.DEFAULT_LAYOUT)
val keyMapId = KeyboardSettings.getString("keymap", KeyMapRegistry.DEFAULT.id)
keyboardView.computer = LayoutRegistry.build(layoutId, keyMapId, density)
```

The view is recreated by Android on each `onCreateInputView` call, so the new computer is picked up automatically whenever the user returns from Settings — no explicit reload needed.

---

### Layer 5 — Settings UI (`App.tsx`)

Two independent pickers in `SettingsScreen`:

**Layout picker** — visual card grid (same pattern as ThemesScreen):
```
[ Sofle V5       ]   [ Ferris Sweep    ]
  5×4 + 5 thumb        5×3 + 2 thumb
```
Writes `layout` key.

**Keymap picker** — compact chip/segmented row (keymaps are text-only, no visual preview needed):
```
QWERTY  Colemak  Dvorak  Prog.Dvorak  Prog.Colemak
```
Writes `keymap` key.

Both show: "Takes effect next time you open the keyboard."

---

## Data flow summary

```
Settings
  layout = "ferris"        keymap = "colemak"
       |                        |
       v                        v
LayoutRegistry.build("ferris", "colemak", density)
       |                        |
       |              KeyMapRegistry.get("colemak")
       |                        |
       |                        v
       |              ColemakKeyMap
       |                        |
       v                        v
FerrisSweepBaseLayerProvider.layers(ColemakKeyMap)
       |
       v
Map<String, FerrisSweepLayerData>   (alphas remapped, symbols/mods untouched)
       |
       v
FerrisSweepLayoutComputer           (geometry only, reads layerData)
       |
       v
NativeKeyboardView.computer         (draws List<PositionedKey>, knows nothing else)
```

---

## Files

| File | Status | Role |
|---|---|---|
| `KeyMap.kt` | New | `KeyMap` interface + `KeyMapRegistry` |
| `KeyMaps.kt` | New | All keymap implementations (Colemak, Dvorak, Prog.Dvorak, Prog.Colemak) |
| `BaseLayerProvider.kt` | New | `BaseLayerProvider<T>` interface |
| `SofleBaseLayerProvider.kt` | New | Reads `SofleKeyData.LAYERS` read-only, applies keymap to alpha block |
| `SofleV5LayoutComputer.kt` | New | Same geometry as `SofleLayoutComputer`, provider-aware |
| `FerrisSweepLayerData.kt` | New | `FerrisSweepLayerData` struct (topRow/left/right/thumbL/thumbR) |
| `FerrisSweepBaseLayerProvider.kt` | New | Owns Ferris key defs, applies keymap to alpha block |
| `FerrisSweepLayoutComputer.kt` | New | Full geometry for Ferris variant |
| `LayoutRegistry.kt` | New | Composition root — `build(layoutId, keyMapId, density)` |
| `CodeKeyboardIME.kt` | Modify (additive) | 3 new lines — read 2 settings, call `LayoutRegistry.build()` |
| `App.tsx` | Modify (additive) | Layout picker + Keymap picker sections in SettingsScreen |
| `SofleKeyData.kt` | **No change** | Reference implementation — never touched |
| `SofleLayoutComputer.kt` | **No change** | Reference implementation — never touched |
| `KeyboardLayout.kt` | No change | Interface unchanged |
| `NativeKeyboardView.kt` | No change | Already accepts `KeyboardLayoutComputer` |
| `KeyboardTheme.kt` | No change | |

---

## Workflow

### Immutability constraint

`SofleKeyData.kt` and `SofleLayoutComputer.kt` are **never modified**.
They remain the working reference implementation for Sofle + QWERTY.
All new code reads them as a read-only source; nothing overwrites or extends them.

Consequence: instead of modifying `SofleLayoutComputer` to accept a provider,
a new `SofleV5LayoutComputer` is written from scratch with the provider pattern.
`SofleBaseLayerProvider` reads `SofleKeyData.LAYERS` as-is and remaps only the alpha keys.
`CodeKeyboardIME` is the only existing file that gains new lines (unavoidable wiring).

### Tasks

| ID | Task | File(s) | Type |
|---|---|---|---|
| A | `KeyMap` interface + `KeyMapRegistry` | `KeyMap.kt` | New |
| B | `BaseLayerProvider<T>` interface | `BaseLayerProvider.kt` | New |
| C | All keymap implementations (Colemak, Dvorak, Prog.Dvorak, Prog.Colemak) | `KeyMaps.kt` | New |
| D | `FerrisSweepLayerData` struct (topRow / left / right / thumbL / thumbR) | `FerrisSweepLayerData.kt` | New |
| E | `SofleBaseLayerProvider` — reads `SofleKeyData.LAYERS` read-only, applies `KeyMap` to alpha block only | `SofleBaseLayerProvider.kt` | New |
| F | `FerrisSweepBaseLayerProvider` — owns Ferris key defs directly, applies `KeyMap` to alpha block | `FerrisSweepBaseLayerProvider.kt` | New |
| G | `SofleV5LayoutComputer` — same geometry as `SofleLayoutComputer`, constructed with `(provider, keyMap)` | `SofleV5LayoutComputer.kt` | New |
| H | `FerrisSweepLayoutComputer` — full geometry (3 main rows, flatter stagger, 2-key thumb cluster) | `FerrisSweepLayoutComputer.kt` | New |
| I | `LayoutRegistry` — `build(layoutId, keyMapId, density): KeyboardLayoutComputer` | `LayoutRegistry.kt` | New |
| J | Wire IME — add 3 lines to read `layout` + `keymap` settings, call `LayoutRegistry.build()` | `CodeKeyboardIME.kt` | Modify (additive only) |
| K | Settings UI — layout card picker + keymap chip row in `SettingsScreen` | `App.tsx` | Modify (additive only) |

### Dependencies

```
A ──► C
A ──► G
A ──► H
B ──► E
B ──► F
D ──► F
E ──► G
F ──► H
C ──► I
G ──► I
H ──► I
I ──► J
I ──► K
```

### Wave table

| Wave | Tasks | Parallelisable |
|---|---|---|
| 0 | A, B | yes — pure interfaces, no deps |
| 1 | C (needs A), D (needs nothing) | yes |
| 2 | E (needs B), F (needs B + D) | yes |
| 3 | G (needs A + E), H (needs A + F) | yes |
| 4 | I (needs C + G + H) | single node, merges all |
| 5 | J (needs I), K (needs I) | yes |

### Critical path

**A → C → I → J** (longest chain, blocks device testing)

K (Settings UI) is not on the critical path — it can be stubbed with hardcoded id arrays
and wired to I at Wave 5.

### Blocking notes

- **J** (IME wiring) is the only modification to existing working code. It is additive:
  the new lines read two settings and call `LayoutRegistry.build()`; the old
  `SofleLayoutComputer(density)` line is replaced, not modified in-place. If anything
  goes wrong, reverting J restores the old behaviour exactly.
- **G** (`SofleV5LayoutComputer`) must produce pixel-identical output to `SofleLayoutComputer`
  for the QWERTY case — verify with a layout export diff before Wave 4.
- **`SofleKeyData.kt`** and **`SofleLayoutComputer.kt`** must have no uncommitted changes
  at any point during this work. Add them to a pre-commit check or simply confirm with
  `git diff -- SofleKeyData.kt SofleLayoutComputer.kt` before each commit.

### Progress

- [x] A — `KeyMap` interface + `KeyMapRegistry`
- [x] B — `BaseLayerProvider<T>` interface
- [x] C — Keymap implementations
- [x] D — `FerrisSweepLayerData` struct
- [x] E — `SofleBaseLayerProvider`
- [x] F — `FerrisSweepBaseLayerProvider`
- [x] G — `SofleV5LayoutComputer`
- [x] H — `FerrisSweepLayoutComputer`
- [x] I — `LayoutRegistry`
- [x] J — IME wiring
- [x] K — Settings UI pickers

---

## Consequences

- The renderer, touch engine, snap logic, and NLP stack are completely decoupled from layout/keymap selection.
- Programmer's Dvorak (symbol layer rearrangement) is handled by `ProgrammerDvorakKeyMap` remapping both alpha and punctuation labels on the base layer — same interface.
- A future "custom layout" feature would implement `KeyboardLayoutComputer` directly, with no registry changes required.
- Unit tests for `KeyMap` implementations are trivial (pure string→string maps, no Android).
- Unit tests for `BaseLayerProvider` verify that only alpha keys are remapped and symbol/modifier keys are invariant.
