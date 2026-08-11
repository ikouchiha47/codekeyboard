# Blog Skeleton: Building CodeKeyboard — A React Native + Kotlin Android IME from Scratch

**Audience:** Android developers who know Kotlin and have touched React Native. No prior IME experience assumed.

**Goal:** After reading this, a developer can reproduce the entire keyboard - architecture, suggestion engine, bigram prediction, emoji panel, and F-Droid publishing - from first principles.

**Source of truth rule:** Prefer live `.kt` / scripts / assets over ADRs and root `PLAN.md` / `UX_DESIGN.md`. If an ADR and the code disagree, follow the code and note the drift in one sentence. Root `PLAN.md` and `UX_DESIGN.md` are prototype-era and often wrong for the shipped Sofle IME.

**LLM completion instructions (per section):**
Each section lists the exact source files to read. Write from those sources only. Do not invent. Keep code snippets short - one illustrative block per concept. Explain the why before the what. Prefer concrete numbers and file paths. Parts 4, 7, 9, 12, 13 may run long; other parts target roughly 400-700 words. Use normal ASCII quotes and hyphens only (no curly quotes, no em dashes).

---

## Part 1 — Why Build a Keyboard at All

**Purpose:** Hook the reader. Establish the motivation and the unusual technical choice (React Native + Kotlin IME, not pure native, not pure RN).

**Angle to take:**
- Most keyboards are either pure native (fast, complex) or RN-based UI with no real IME (cosmetic). We wanted real IME behaviour (composing text, suggestion bar, modifier keys) with a React Native settings/theme/preview layer on top.
- The React Native layer is explicitly transitional - the architecture encodes its own removal (`docs/architecture/overview.md`).
- Mixed-language typing (Banglish, Hinglish) is underserved. Seed models are English; personal learning must pick up the rest.
- Layout inspiration: Sofle / Corne split, column stagger, ZMK-style layers and home-row mods.

**No source files required beyond overview motivation. Do not invent product claims not in README/fdroid description.**

---

## Part 2 — Architecture: Three Layers, One Seam

**Purpose:** Give the reader a mental model before any code.

**Source files to read:**
- `docs/architecture/overview.md` - layer diagram (note: diagram is partially stale; correct names below)
- `docs/architecture/decisions/ADR-001-text-input-abstraction.md` - designed `TextInputConnection` interface
- `docs/architecture/decisions/ADR-002-composing-engine.md` - designed composing API
- `android/app/src/main/java/com/codekeyboard/CodeKeyboardIME.kt` - what actually ships
- `android/app/src/main/java/com/codekeyboard/ComposingBuffer.kt` - shipped buffer class
- `android/app/src/main/AndroidManifest.xml` + `res/xml/method.xml` - IME registration

**Key points to cover:**
1. Layer 1: React Native (`App.tsx`, Settings, Themes, preview) - transitional. Settings/snippets still use the RN bridge.
2. Layer 2: Android IME (Kotlin) - `CodeKeyboardIME extends InputMethodService`. Owns composing, suggestions, key rendering (`NativeKeyboardView`), bigrams, snippets, emoji panel swap.
3. Layer 3: Target app text field - only via `InputConnection`. Never touch the app view tree.
4. ADR-001 defines `TextInputConnection` for testability and multi-platform. **Shipped IME still calls `InputConnection` directly** in most paths. Fake exists in tests (`FakeTextInputConnection.kt`). Mention the ADR as the intended seam, not as fully wired production code.
5. Shipped composing owner is **`ComposingBuffer`** (append/backspace/flush/setText/clear), not a full `ComposingEngine` class. IME methods orchestrate buffer + IC.
6. `supportsComposing` in `onStartInput`: false for null editorInfo, `TYPE_NULL`, password variations, number/phone/datetime classes.
7. Manifest: service with `BIND_INPUT_METHOD`, intent `android.view.InputMethod`, meta-data `@xml/method`.

**Code to include:** Manifest service snippet. `ComposingBuffer` API. Field-detection `when` from `CodeKeyboardIME.onStartInput`.

---

## Part 3 — Key Rendering: NativeKeyboardView and the Sofle Layout

**Purpose:** Explain how keys are drawn and why layout is defined in two places.

**Source files to read:**
- `android/app/src/main/java/com/codekeyboard/NativeKeyboardView.kt` - `onDraw`, `hitTest`, hold paths, constants
- `android/app/src/main/java/com/codekeyboard/SofleKeyData.kt` - V5 layers + holdAction annotations
- `android/app/src/main/java/com/codekeyboard/SofleLayoutComputer.kt` - geometry
- `src/keyboard/Layout.ts` - RN mirror
- `CLAUDE.md` - Dual Layout Rule
- `GESTURE_ARCHITECTURE.md` - gesture pipeline (authoritative for timing)

**Key points to cover:**
1. `NativeKeyboardView` extends `View`. Canvas `onDraw`. No XML key layout.
2. `SofleLayoutComputer` computes `PositionedKey` rects from width + density. Dynamic `maxSafeSnapPx` for gap snapping.
3. Dual-layout rule: `Layout.ts` (RN preview/settings) and `SofleKeyData.kt` (IME) must stay in sync by hand. IME never reads Layout.ts at runtime.
4. Layers in data: **base, lower, raise, adj, func** (not "symbol layer" as a name).
5. Hit expand: `HIT_EXPAND_DP = 2.5f` inflates hit test only, not draw.
6. Timing constants (companion object): `TAPPING_TERM_MS = 150`, backspace `REPEAT_INITIAL_DELAY_MS = 400`, `REPEAT_INTERVAL_MS = 50`. Double-tap window is `TapMachine` default 300ms in `TapMachine.kt`.

**Code to include:** `hitTest` expansion. One BASE home-row hold annotation from SofleKeyData.

---

## Part 4 — Composing: Making Text Feel Native

**Purpose:** Hardest IME concept. Composing from first principles + shipped edge cases.

**Source files to read:**
- `android/app/src/main/java/com/codekeyboard/ComposingBuffer.kt`
- `android/app/src/main/java/com/codekeyboard/CodeKeyboardIME.kt` - `handleKey`, `flushComposing`, `recomposeWordAtCursor`, `onUpdateSelection`, `onStartInput`
- `docs/architecture/decisions/ADR-002-composing-engine.md` - design intent only
- `android/app/src/test/java/com/codekeyboard/BackspaceRecomposeTest.kt` if present

**Key points to cover:**
1. `setComposingText` vs `commitText` - underline while mid-word.
2. Buffer ops: append on char, flush on space/punct/enter/mod combo, backspace pops buffer first.
3. `onUpdateSelection`: clear composing when cursor leaves composing region **and** the update is not IME-driven. Guard: `expectSelectionUpdateBy` timestamp window so IME's own selection changes do not wipe state.
4. Field detection matrix (same as Part 2).
5. **Recompose:** when backspace empties composing and deletes into committed text, `recomposeWordAtCursor` reads `getTextBeforeCursor`, takes trailing word fragment, `setComposingRegion`, `composing.setText(fragment)`, refreshes suggestions. This is a real bugfix path (see git history / CKB_COMPOSE logs).
6. Shift latch clears on commit via `kbState.onCharCommitted()`, not on every composing append.

**Code to include:** Character branch in `handleKey`. `onUpdateSelection` clear guard. `recomposeWordAtCursor` sketch.

---

## Part 5 — Suggestion Bar: Native Android View, No RN Bridge

**Purpose:** Why suggestions are Kotlin-native and how ranking is stacked.

**Source files to read:**
- `docs/architecture/decisions/ADR-003-kotlin-native-suggestions.md` - rationale (slot count in ADR is stale)
- `android/app/src/main/java/com/codekeyboard/SuggestionBarView.kt` - actual UI
- `android/app/src/main/java/com/codekeyboard/SuggestionStrategy.kt` - stack
- `android/app/src/main/java/com/codekeyboard/CodeKeyboardIME.kt` - wire-up in `onCreate`, space path, suggestion tap

**Key points to cover:**
1. RN bridge cannot drive IME suggestions: JS never sees real field commits. ADR-003 is the decision.
2. `SuggestionBarView` is a `HorizontalScrollView` + row of `TextView`s above the keyboard in `onCreateInputView`.
3. Slot model (code, not ADR): when composing non-empty, slot 0 = typed word (confirm as-is), then suggestions. When composing empty (after space), show next-word candidates only.
4. Strategy stack built in `onCreate`:
   `BigramAwareSuggestionStrategy(MergedSuggestionStrategy(userTrie, trie), bigramModel)`.
   Fuzzy is **inside** Merged (BEVA fill when exact count &lt; k), not a separate runtime strategy switch.
5. Snippet mode: if composing starts with `;`, bar shows `SnippetStore.matching` instead of trie.
6. After space / after suggestion tap: `bigramModel.nextWords(...)` fills the bar before the next word is typed.

**Code to include:** Strategy construction in `onCreate`. `SuggestionStrategy` interface. `SuggestionBarView.update` empty-word branch.

---

## Part 6 — The Trie: Base TRIF + User TRIE3

**Purpose:** Two dictionary formats, build pipeline, query.

**Source files to read:**
- `tools/build-trie.js` - TRIF writer
- `__tests__/trie.test.js` - TRIF reader spec
- `android/app/src/main/java/com/codekeyboard/Trie.kt` - Kotlin base reader (TRIF + legacy TRIE2)
- `android/app/src/main/java/com/codekeyboard/UserTrie.kt` - mutable user trie
- `android/app/src/main/java/com/codekeyboard/TrieWriter.kt` - TRIE3 serialize
- `android/app/src/main/java/com/codekeyboard/WordLearner.kt`
- `scripts/gen_wordlist.py` if used in pipeline

**Key points to cover:**
1. Base asset `assets/en.trie` is **TRIF** (magic 4 bytes `TRIF`, 12-byte nodes, frequency u32, child entry char+u24 index). Built offline.
2. Why binary: packed, fast walk, no JSON parse on hot path. Size on disk ~2.2MB for shipped en.trie.
3. Build: wordlist lines `word\tfreq` -> `build-trie.js` -> stdout binary. Filters non a-z, length 2-20.
4. `Trie.suggest(prefix, max)` walks to prefix node, DFS collects terminals, sorts by frequency.
5. User dictionary is **TRIE3** (`TrieWriter`, magic TRI3), mutable graph in memory, best-first suggest with `maxDescendantFreq` pruning (Completion Trie / PruningRadixTrie lineage - see plan-phase5).
6. `WordLearner`: `learnFromFlush` only if word length &gt; 1, not `;...`, **and** known to base dict. `learnFromTap` always learns learnable words. Flush path alone must not teach typos.
7. Persist user.trie on `onFinishInput` via single-thread executor.

**Code to include:** TRIF header layout from build-trie.js comments. WordLearner rules. UserTrie suggest pruning idea in words.

---

## Part 7 — Bigram Prediction: Context-Aware Next-Word Suggestions

**Purpose:** Two-layer model + librime decay + seed limitations.

**Source files to read:**
- `docs/bigram-prediction-design.md`
- `docs/adr-001-bigram-prediction.md`
- `android/app/src/main/java/com/codekeyboard/BigramModel.kt`
- `scripts/extract_bigrams.py`
- Measure live `assets/bigrams.json` if needed (do not invent counts)

**Verified shipped seed stats (measure file, do not hardcode stale ADR "100k"):**
- ~618KB JSON
- ~9,287 predecessor words
- ~34,632 pairs
- avg ~3.7 followers, max 10 per predecessor (script `--max-followers`)
- blend 0.4 seed + 0.6 user
- user max 20 followers
- script default `--top 30000` on raw pairs before group/cap

**Key points to cover:**
1. Gap: after "I want" + space, bar was empty. Bigrams show next-word candidates immediately.
2. Seed from Norvig `count_2w.txt`, log-normalized within predecessor bucket.
3. User layer `user_bigrams.json` v2: `{ tick, entries: { prev: [[word, dee, lastTick], ...] } }`. v1 wiped on load (no migration).
4. `formula_d` / `formula_p` from librime `algo/dynamics.h` - explain dee, half-life 200, globalTick.
5. `recordTransition` on flush and on suggestion tap.
6. `BigramAwareSuggestionStrategy` promotes `nextWords(context, prefix)` ahead of base results.
7. Honest limits: thin coverage, web-corpus register, no unigram backoff, hard follower cap. Typing path still has trie+fuzzy when seed misses; after-space with unknown prev can be empty.

**Code to include:** `formula_d` / `formula_p`. `nextWords` merge. extract_bigrams score line.

---

## Part 8 — Emoji Panel: Generated from Unicode Data

**Purpose:** Offline emoji without hand lists.

**Source files to read:**
- `docs/architecture/decisions/ADR-004-user-trie-decay-and-emoji.md` - decision (panel details may drift)
- `scripts/gen_emoji.py` - generator
- `android/app/src/main/java/com/codekeyboard/EmojiPanelView.kt` - UI
- `CodeKeyboardIME.kt` - `showEmojiPanel` / `hideEmojiPanel`

**Key points to cover:**
1. Source: Unicode 15.0 `emoji-test.txt`, fully-qualified only. Skin tones grouped under base.
2. Output: `assets/emoji.json` categories with `{base, variants}`.
3. Panel is **`EmojiPanelView`**, not SuggestionBarView. Shown via `setInputView(emojiPanel)`; back restores keyboard via `onCreateInputView()`.
4. Height locked to keyboard wrapper height + same nav-bar padding so content is not under system IME chrome.
5. Focus: `descendantFocusability = FOCUS_BLOCK_DESCENDANTS`, `isFocusable = false` - focus steal dismissed the IME.
6. Tap: `commitText(emoji)` - no composing.
7. CATEGORY_ICONS map in companion object for tab labels.

**Code to include:** Focus flags. CATEGORY_ICONS. setInputView swap sketch.

---

## Part 9 — Spell Correction: From Hanov DFS to BEVA

**Purpose:** Offline typo correction on the trie.

**Source files to read:**
- `docs/architecture/decisions/ADR-005-spell-correction.md` - evaluation (what we considered)
- `android/app/src/main/java/com/codekeyboard/FuzzyTrieSearch.kt` - Hanov DFS still in tree
- `android/app/src/main/java/com/codekeyboard/BevaTrieSearch.kt` - **what Merged uses**
- `SuggestionStrategy.kt` - call path

**Key points to cover:**
1. ADR evaluated: Schulz automaton, Hanov DP-row DFS, BEVA, SymSpell, BK-tree. Rejected SymSpell (memory) and BK-tree.
2. ADR accepted Hanov as v1; code later swapped production path to **BEVA bitmask edit-vector DFS** (commit history / BevaTrieSearch). FuzzyTrieSearch remains as alternate/benchmark.
3. Production: `MergedSuggestionStrategy.fuzzyFill` calls `BevaTrieSearch.search` on user + base adapters.
4. Fires when exact results size &lt; k (fill remaining), not only when exact is empty. Threshold 0 short-circuits.
5. `FuzzyThreshold.forLength`: &lt;=3 -&gt; 0, 4 -&gt; 1, else 2.
6. Collect all within threshold then rank by edit distance, common prefix, frequency - do not early-exit DFS by count (alphabetical order is not quality order).

**Code to include:** FuzzyThreshold. Merged fuzzyFill ranking. One sentence on BEVA state (evBits).

---

## Part 10 — User Learning and Trie Decay

**Purpose:** Learn words; bound growth overnight.

**Source files to read:**
- `WordLearner.kt`
- `UserTrie.kt` - `applyDecay`
- `TrieDecayWorker.kt`
- `MainApplication.kt` - WorkManager schedule
- ADR-004 decay section
- `plan-phase5-user-trie.md` / `plan-phase6-decay-and-emoji.md` for design refs

**Key points to cover:**
1. Learning policy again (must match Part 6 WordLearner - do not say flush always learns).
2. user.trie grows; decay needed for load/suggest cost.
3. `TrieDecayWorker`: load, `applyDecay(0.9, epoch+1)`, save. Drop terminals with freq &lt; 1 after decay; compact; hard rebuild top 5000 if &gt; 50k nodes.
4. Scheduled in `MainApplication`: periodic 1 day, constraints **requiresCharging + requiresDeviceIdle**, unique work `trie_decay`, KEEP policy.
5. Typing path does not decay. Flush on finish input is separate from overnight prune.
6. TRIE3 stores `decayEpoch` and per-node `lastDecayEpoch`.

**Code to include:** WorkManager constraints block. applyDecay factor call.

---

## Part 11 — Snippet System

**Purpose:** `;shortcode` expansions.

**Source files to read:**
- `SnippetStore.kt`
- `plan-phase4-snippets.md`
- ADR-003 snippet section
- IME char branch for `;`
- RN settings in `App.tsx` only as needed for bridge mention

**Key points to cover:**
1. Type `;` then shortcode; bar shows expansions from SharedPreferences keys `snippet_*`.
2. Defaults seeded empty: em, ph, addr, me, gh, li.
3. Validation: shortcode `^[a-z0-9_]+$`, no blank expansion, add refuses collisions.
4. Tap commits expansion + space path via suggestion handler (same as word tap flow for commit).
5. RN settings UI writes same prefs namespace via native module.

**Code to include:** SnippetStore.matching / add. `;` branch in handleKey.

---

## Part 12 — Modifier Keys, Layers, and Gestures

**Purpose:** State machine + touch paths that make Sofle usable.

**Source files to read:**
- `KeyboardState.kt`
- `TapMachine.kt`
- `NativeKeyboardView.kt` - onTouchEvent branches
- `GESTURE_ARCHITECTURE.md`
- SofleKeyData hold comments

**Key points to cover:**
1. Layers: base, lower, raise, adj, func. Latch vs lock via double-tap on layer keys. `effectiveLayer = layerHeld ?: layer`.
2. Modifiers: shift/ctrl/alt cycle NONE -&gt; LATCHED -&gt; (double) LOCKED. Caps toggles LOCKED.
3. **Two hold implementations:**
   - Dedicated mods (shift/ctrl/alt/lower/raise/func/adj): activate on DOWN; on UP, if quick tap with no other key, also fire tap to cycle latch; if other key pressed while held, pure hold.
   - Home-row letters and thumb spaces with holdAction: 150ms timer; UP before timer = letter/space tap; timer fire = hold modifier/layer.
4. TapMachine is for **double-tap detection**, not the 150ms hold timer.
5. Backspace/delete auto-repeat 400ms then 50ms; cancel on move off key.
6. Dual-layout hazard example: LWR `:` / `;` swap fixed in history - both Layout.ts and SofleKeyData need the same fix.
7. Caps must not count as shift for `isShiftActive` (XOR upper logic).

**Code to include:** applyHold/releaseHold. Touch DOWN branch choosing dedicated vs timer path. Constants.

---

## Part 13 — F-Droid Publishing

**Purpose:** Submission pitfalls.

**Source files to read:**
- `docs/fdroid-recipe.yml`
- `CLAUDE.md` versioning
- Root LICENSE / fastlane metadata layout
- Do not invent F-Droid failures not documented in-repo; if discussing RN gradle/F-Droid friction, mark as operational notes only when present in docs/commits

**Key points to cover:**
1. F-Droid builds from source; recipe in `docs/fdroid-recipe.yml`.
2. subdir, commit/tag, NDK version, npm ci, gradle release.
3. Semver: versionName matches tag; versionCode increments.
4. MIT license, fastlane metadata for store listing.
5. Stay honest: recipe may lag app version numbers - quote file as example of shape, not eternal truth.

**Code to include:** Recipe structure. Semver rules from CLAUDE.md.

---

## Part 14 — CI/CD: GitHub Actions

**Purpose:** Build pipeline as rebuilders will run it.

**Source files to read:**
- `CodeKeyboard/.github/workflows/build.yml` (and root workflow if present)
- `CLAUDE.md` CI section

**Key points to cover:**
1. Job: checkout, JDK 17 temurin, Node 20, npm ci, Gradle cache, sdkmanager platforms 36 / build-tools 36.0.0 / ndk 27.1 / cmake 3.22.1, `testDebugUnitTest`, `assembleRelease`, upload APK.
2. Manual workflow_dispatch release job publishes artifact to GitHub Release.
3. JS tests: `node --test` for trie/build-trie in `__tests__` where used; Android unit tests via Gradle.
4. TRIF test rewrite incident: tests must match TRIF 12-byte nodes + u24 child index, not old TRIE2 assumptions.

**Code to include:** High-level workflow steps list.

---

## Part 15 — What's Next

**Purpose:** Honest unfinished work.

**Source files to read:**
- `docs/adr-002-gif-picker.md`
- `docs/adr-001-bigram-prediction.md` pending section
- `docs/bigram-prediction-design.md`

**Subsections:**

### 15a — GIF Picker (planned)
BYOK GIPHY/Tenor; local MediaStore; no bundled keys (public keys dead). `commitContent` for send.

### 15b — Better LM prior
Current seed limits (Part 7 numbers). Paths:
1. AOSP/HeliBoard `.dict` or `.combined` - denser bigrams; offline convert to TRIF+json or JNI reader.
2. KenLM - mentioned in design notes; heavier.
3. Trigram keys `"$w2 $w1"` with backoff to bigram.
Keep user decay layer either way. Add unigram backoff even before full AOSP.

### 15c — Mixed language
User bigrams already learn Banglish/Hinglish pairs as plain strings. Seed and en.trie are English-only. Need mixed wordlists / packs and optional language switching - not shipped.

### 15d — Other unfinished
- Slide/gesture typing (UX doc only)
- Tap-dance
- Full RN removal
- Fully wire TextInputConnection abstraction in IME

---

## Appendix: Key File Map

Generate a table from real files (update if missing):

| File | Purpose |
|---|---|
| `CodeKeyboardIME.kt` | IME entry - input view, handleKey, composing, suggestions |
| `NativeKeyboardView.kt` | Canvas keys, hit test, hold/repeat |
| `SofleKeyData.kt` | Layer key defs + holdAction |
| `SofleLayoutComputer.kt` | Geometry |
| `KeyboardState.kt` | Latch/lock/hold state |
| `TapMachine.kt` | Double-tap detector |
| `ComposingBuffer.kt` | Composing string buffer |
| `BigramModel.kt` | Seed + user bigrams + decay |
| `SuggestionStrategy.kt` | Merged + BigramAware stack |
| `BevaTrieSearch.kt` | Production fuzzy search |
| `FuzzyTrieSearch.kt` | Hanov DFS (eval/benchmark path) |
| `Trie.kt` | Base TRIF/TRIE2 reader |
| `UserTrie.kt` / `TrieWriter.kt` | User TRIE3 |
| `WordLearner.kt` | Learning policy |
| `TrieDecayWorker.kt` / `MainApplication.kt` | Overnight decay |
| `SnippetStore.kt` | `;` snippets |
| `EmojiPanelView.kt` | Emoji UI |
| `SuggestionBarView.kt` | Suggestion chips |
| `tools/build-trie.js` | TRIF build |
| `scripts/extract_bigrams.py` | bigrams.json build |
| `scripts/gen_emoji.py` | emoji.json build |
| `assets/en.trie` / `bigrams.json` / `emoji.json` | Bundled data |
| `Layout.ts` / `ModifierState.ts` | RN preview only |
| `.github/workflows/build.yml` | CI |
| `CLAUDE.md` | Build, dual layout, semver |
| `GESTURE_ARCHITECTURE.md` | Gesture timing design |
| ADRs under `docs/architecture/decisions/` and `docs/adr-*.md` | Decisions (may lag code) |
