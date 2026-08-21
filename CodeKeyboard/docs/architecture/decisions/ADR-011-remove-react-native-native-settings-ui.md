# ADR-011: Replace React Native launcher with a native Kotlin settings Activity

*Status: Proposed*

---

## Context

The launcher app (the screen users open from the home screen) is built in React Native. It provides four tabs: Settings, Themes, Layouts, and Languages. The IME itself (the keyboard) is pure Kotlin and has been since the beginning; React Native has never touched a keystroke.

React Native and the Hermes JS engine are the primary reason the APK was 302 MB before the CKLM pack landed. Even after that reduction, RN still contributes significant binary weight and blocks F-Droid distribution — F-Droid requires fully reproducible builds from source, and pre-built JS bundles and native .so files from the RN ecosystem make that hard to guarantee.

The RN layer is also the only part of the project that requires Node, npm, and a metro bundler to build. Removing it makes the build a single `./gradlew assembleRelease`.

### What the RN layer actually does

Two native bridges are called from RN:

- `SettingsModule` -- `getString`, `setString`, `deleteSnippet` over `SharedPreferences`
- `IMEHelperModule` -- one call: open the system IME picker via `Intent`

Four screens:

| Screen | What it does |
|---|---|
| Settings | Opens Android IME settings; hosts SnippetsEditor |
| SnippetsEditor | CRUD for `snippet_<key>` entries in SharedPreferences, debounced autosave |
| Themes | Grid of 9 theme cards; writes `theme` key to SharedPreferences |
| Layouts | Layout + keymap picker; writes `layout` and `keymap` keys |
| Languages | Placeholder |

One preview component:

- `Keyboard.tsx` + `Key.tsx` + `Layout.ts`: renders a visual preview of the active layout in the Layouts screen. 600 lines of layout math, key sizing, layer labels.

---

## Goals

1. Remove React Native, Hermes, metro, and Node from the build entirely.
2. The four settings screens look and behave identically to the current RN screens.
3. The keyboard layout preview remains in the Layouts screen.
4. A single `./gradlew assembleRelease` produces the APK with no pre-build steps.
5. F-Droid reproducible build passes.

---

## Architecture

### Settings storage

`SettingsModule` wraps `SharedPreferences`. In native code, every screen reads and writes `SharedPreferences` directly -- no bridge, no module. Same keys, same values.

```kotlin
// before (via bridge)
NativeModules.SettingsModule?.setString("theme", id)

// after (direct, inside the Activity)
prefs.edit().putString("theme", id).apply()
```

### Activity + Fragment structure

One `SettingsActivity` replaces the RN launcher. It hosts a `BottomNavigationView` and a `FragmentContainerView`. Four fragments:

```
SettingsActivity
  BottomNavigationView  (Settings | Themes | Layouts | Languages)
  FragmentContainerView
    SettingsFragment      -- IME enable button + SnippetsFragment
    ThemesFragment        -- theme grid
    LayoutsFragment       -- layout + keymap picker + keyboard preview
    LanguagesFragment     -- placeholder (matches current RN placeholder)
```

### Snippets

`SnippetsFragment` owns a `RecyclerView` with one row per snippet. Each row has a key label and an editable value field. Autosave fires on every keystroke via a `TextWatcher` with a 300ms debounce -- same behaviour as the current RN implementation.

```kotlin
// pseudocode: SnippetsAdapter row binding
holder.valueField.addTextChangedListener(object : TextWatcher {
    val handler = Handler(Looper.getMainLooper())
    var runnable: Runnable? = null
    override fun afterTextChanged(s: Editable) {
        runnable?.let { handler.removeCallbacks(it) }
        runnable = Runnable {
            prefs.edit().putString("snippet_${item.key}", s.toString().trim()).apply()
        }
        handler.postDelayed(runnable!!, 300)
    }
})
```

Delete: long-press row -> confirm dialog -> `prefs.edit().remove("snippet_${key}").apply()`.

Add: floating `+` button -> bottom sheet with key + value fields.

### Themes

`ThemesFragment` uses a `RecyclerView` with a `GridLayoutManager(spanCount=2)`. Theme data moves from `App.tsx` into a Kotlin data class:

```kotlin
data class ThemeConfig(
    val id: String,
    val name: String,
    val desc: String,
    val bg: Int,       // Color int
    val key: Int,
    val accent: Int,
    val text: Int,
    val textMuted: Int,
)

val THEMES = listOf(
    ThemeConfig("carbon",   "Carbon",   "Neutral dark grey",      0xFF111111, 0xFF2c2c2c, 0xFF4a9eff, 0xFFe0e0e0, 0xFF888888),
    ThemeConfig("midnight", "Midnight", "Deep navy blue",         0xFF080d14, 0xFF0f1c2e, 0xFF3b82f6, 0xFFe0e0e0, 0xFF888888),
    ThemeConfig("obsidian", "Obsidian", "Near-black with violet", 0xFF0c0c10, 0xFF1a1a24, 0xFF8b5cf6, 0xFFe0e0e0, 0xFF888888),
    ThemeConfig("ash",      "Ash",      "Warm grey, amber accent",0xFF141210, 0xFF272320, 0xFFf59e0b, 0xFFe0e0e0, 0xFF888888),
    ThemeConfig("moss",     "Moss",     "Dark green-grey, teal",  0xFF0d1210, 0xFF1a2420, 0xFF2dd4bf, 0xFFe0e0e0, 0xFF888888),
    ThemeConfig("dusk",     "Dusk",     "Slate purple, rose",     0xFF10101a, 0xFF1e1e2e, 0xFFf43f5e, 0xFFe0e0e0, 0xFF888888),
    ThemeConfig("iron",     "Iron",     "Cool steel, cyan",       0xFF0e1014, 0xFF1c2028, 0xFF06b6d4, 0xFFe0e0e0, 0xFF888888),
    ThemeConfig("ember",    "Ember",    "Warm brown, orange",     0xFF100c08, 0xFF241c14, 0xFFea580c, 0xFFe0e0e0, 0xFF888888),
    ThemeConfig("frost",    "Frost",    "Bluish white, cool blue",0xFFEef3fa, 0xFFdbe6f5, 0xFF2f6fed, 0xFF16233d, 0xFF5b7096),
)
```

Each card: `CardView` with background = `theme.bg`, a row of three small rounded rects in `theme.key` colour (key preview), an accent bar, name + desc text. Selected card gets a border in `theme.accent`.

### Layouts

`LayoutsFragment` has two `RecyclerView` rows (layouts, keymaps) and a keyboard preview image below.

```kotlin
// pseudocode: layout selection
layoutAdapter.onSelect = { id ->
    prefs.edit().putString("layout", id).apply()
    updatePreview(id, currentKeymap)
}

fun updatePreview(layout: String, keymap: String) {
    val svgId = resources.getIdentifier(
        "preview_${layout}_${keymap}", "raw", packageName)
    val svg = SVGParser.parse(resources.openRawResource(svgId))
    previewImageView.setImageDrawable(svg.createPictureDrawable())
}
```

### Keyboard preview: SVG generation

A Python script `scripts/gen_layout_svg.py` reads `SofleKeyData.kt` (authoritative key data) and emits one SVG per layout per layer combination. SVGs are stored as Android raw resources (`res/raw/preview_sofle_base.svg`, `res/raw/preview_qwerty_base.svg`, etc.).

```python
# pseudocode: gen_layout_svg.py
LAYOUTS = parse_sofle_key_data("android/.../SofleKeyData.kt")

for layout_id, layout in LAYOUTS.items():
    for layer_name, keys in layout.layers.items():
        svg = SVGBuilder(width=800, height=300)
        for key in keys:
            x, y = key.grid_pos_to_px(KEY_W, KEY_H, GAP)
            svg.rect(x, y, key.width * KEY_W, KEY_H, rx=6, fill=KEY_FILL)
            svg.text(x + PAD, y + PAD, key.label(layer_name), size=18, fill=TEXT_FILL)
            if key.shift_label:
                svg.text(x + PAD, y + KEY_H - PAD, key.shift_label, size=11, fill=MUTED_FILL)
        svg.save(f"android/app/src/main/res/raw/preview_{layout_id}_{layer_name}.svg")
```

SVG rendering at runtime uses AndroidSVG (already a small, pure-Java library, F-Droid safe):

```kotlin
// build.gradle
implementation("com.caverock:androidsvg-aar:1.4")
```

SVGs are regenerated by running `python3 scripts/gen_layout_svg.py` after any layout change. This is a build-time step documented in `CLAUDE.md`, not a runtime dependency.

### IME enable flow

`SettingsFragment` replaces the `IMEHelperModule` bridge with a direct Intent:

```kotlin
fun openImeSettings() {
    startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
}

fun openImePicker() {
    (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
        .showInputMethodPicker()
}
```

---

## Files

| File | Action | Role |
|---|---|---|
| `android/app/src/main/java/com/codekeyboard/SettingsActivity.kt` | New | Root Activity, BottomNavigationView, Fragment host |
| `android/app/src/main/java/com/codekeyboard/SettingsFragment.kt` | New | IME enable button + snippet list |
| `android/app/src/main/java/com/codekeyboard/SnippetsFragment.kt` | New | RecyclerView CRUD, debounced autosave |
| `android/app/src/main/java/com/codekeyboard/ThemesFragment.kt` | New | Theme grid, ThemeConfig data class |
| `android/app/src/main/java/com/codekeyboard/LayoutsFragment.kt` | New | Layout/keymap picker, SVG preview |
| `android/app/src/main/res/layout/*.xml` | New | Layouts for each Fragment and RecyclerView row |
| `android/app/src/main/res/raw/preview_*.svg` | New | Generated SVG assets, one per layout x layer |
| `scripts/gen_layout_svg.py` | New | SVG generator, reads SofleKeyData.kt |
| `android/app/src/main/java/com/codekeyboard/SettingsModule.kt` | Delete | Replaced by direct SharedPreferences access |
| `android/app/src/main/java/com/codekeyboard/IMEHelperModule.kt` | Delete | Replaced by direct Intent |
| `android/app/src/main/java/com/codekeyboard/CodeKeyboardPackage.kt` | Delete | RN package registration |
| `android/app/src/main/java/com/codekeyboard/MainApplication.kt` | Modify | Remove RN application class, replace with plain Application |
| `android/app/src/main/java/com/codekeyboard/MainActivity.kt` | Modify | Replace RN activity with redirect to SettingsActivity |
| `android/app/build.gradle` | Modify | Remove RN/Hermes dependencies, add androidsvg-aar |
| `android/gradle.properties` | Modify | Remove reactNativeArchitectures and RN flags |
| `App.tsx`, `src/`, `index.js`, `package.json`, `metro.config.js`, `babel.config.js` | Delete | Entire RN layer |
| `CLAUDE.md` | Modify | Update build instructions, add SVG regen step |

---

## Consequences

**Easier after this:**
- Single `./gradlew assembleRelease` builds everything
- F-Droid submission becomes viable
- APK drops further (RN .so libs + Hermes + JS bundle removed)
- No Node/npm required on any build machine

**Deferred:**
- Languages tab remains a placeholder (matches current state)
- SVG previews are static -- layer switching in the preview screen is a follow-up if needed

**Trade-offs:**
- SVGs must be regenerated manually after layout changes. A CI check (`python3 scripts/gen_layout_svg.py --check`) can catch stale assets.
- `AndroidSVG` is a new dependency, but it is small (80KB), pure Java, and F-Droid safe.

---

## Workflow

### Tasks

| ID | Task |
|---|---|
| A | `gen_layout_svg.py` -- parse SofleKeyData.kt, emit SVGs for all layouts x layers |
| B | `ThemeConfig` data class + `THEMES` list in Kotlin (port from App.tsx) |
| C | `SettingsActivity` -- BottomNavigationView + FragmentContainerView shell |
| D | `ThemesFragment` -- RecyclerView grid, card layout XML, SharedPreferences write |
| E | `LayoutsFragment` -- layout/keymap RecyclerView + SVG ImageView, wired to SVGs from A |
| F | `SnippetsFragment` -- RecyclerView, TextWatcher debounce, add/delete flow |
| G | `SettingsFragment` -- IME enable + IME picker buttons, host SnippetsFragment |
| H | `AndroidManifest.xml` -- register SettingsActivity as launcher, remove RN Activity |
| I | Delete `SettingsModule.kt`, `IMEHelperModule.kt`, `CodeKeyboardPackage.kt` |
| J | Rewrite `MainApplication.kt` and `MainActivity.kt` to remove RN |
| K | `build.gradle` -- remove RN/Hermes, add androidsvg-aar |
| L | Delete `App.tsx`, `src/`, `index.js`, `package.json`, `metro.config.js`, `babel.config.js`, `node_modules` |
| M | Update `CLAUDE.md` build instructions |

### Dependencies

- A has no dependencies (pure script, reads existing Kotlin source)
- B has no dependencies
- C has no dependencies
- D needs B, C
- E needs A, C
- F needs C
- G needs C, F
- H needs C, D, E, G
- I needs H
- J needs I
- K needs J
- L needs K
- M needs L

### Wave table

| Wave | Tasks | Parallel? |
|---|---|---|
| 0 | A, B, C | yes |
| 1 | D, E, F, G | yes (all need C; D needs B; E needs A) |
| 2 | H | no (needs all of wave 1) |
| 3 | I | no |
| 4 | J, K | yes |
| 5 | L | no (after K confirms build passes) |
| 6 | M | no |

**Critical path:** C -> G -> H -> I -> J -> K -> L -> M

**Blocking notes:**
- L (deleting RN) is irreversible. Run a full build and smoke-test all four tabs before executing L.
- K (removing RN from gradle) will break the build until J is complete. Do J and K together.
- A (SVG generator) determines whether E is testable. Write and run A before starting E.
