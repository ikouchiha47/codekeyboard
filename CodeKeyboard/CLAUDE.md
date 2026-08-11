# Build

## CI (primary)
Push to `master` — GitHub Actions builds APK automatically.
Artifact: `app-release.apk` uploaded to the workflow run.

Monitor build:
```bash
gh run list --repo ikouchiha47/codekeyboard --limit 3
gh run view <run-id> --repo ikouchiha47/codekeyboard --log | grep -E "error:|FAIL|BUILD"
```

## Local (fallback)
```bash
cd android && ./gradlew assembleRelease --warning-mode all
```

# CI Workflow
`.github/workflows/build.yml` steps:
1. `actions/checkout@v4`
2. `actions/setup-java@v4` — JDK 17 (temurin)
3. `actions/setup-node@v4` — Node 20, `npm ci`
4. `actions/cache@v4` — Gradle caches
5. `ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager` — platforms 36, build-tools 36.0.0, ndk 27.1.12297006, cmake 3.22.1
6. `./gradlew assembleRelease --console=plain`
7. `actions/upload-artifact@v4` — uploads `app-release.apk`

# Version Numbering
Follows **semver**: `vMajor.Minor.Patch` (e.g. `v1.8.0`)
- **Patch** — bug fixes, no new features
- **Minor** — new features, backward compatible
- **Major** — breaking changes or major rewrites

`versionCode` — increment by 1 each release (in `android/app/build.gradle`)
`versionName` — must match the tag (e.g. tag `v1.8.0` → versionName `1.8.0`)

Always tag releases: `git tag v<Major>.<Minor>.<Patch> && git push origin v<Major>.<Minor>.<Patch>`

# Key Files
- `App.tsx` — root RN component, tab nav for Keyboard/Settings/Themes/Languages
- `src/keyboard/Keyboard.tsx` — main keyboard UI, action registry, input area
- `src/keyboard/Layout.ts` — keyboard layout definitions (SOFLE, layers)
- `src/keyboard/ModifierState.ts` — modifier/layer state machine
- `src/keyboard/Key.tsx` — individual key component
- `android/app/src/main/java/com/codekeyboard/` — IME native layer (Kotlin)
- `.github/workflows/build.yml` — CI pipeline

# IMPORTANT: Dual Layout Rule
The keyboard layout is defined in TWO places that must be kept in sync:
1. `src/keyboard/Layout.ts` — React Native UI (settings/preview screens)
2. `android/app/src/main/java/com/codekeyboard/SofleKeyData.kt` — native IME (what the user actually types on)

Any key added, removed, or changed in one file MUST be updated in the other.
The IME does NOT read from Layout.ts at runtime — they are completely independent.

# Tech Stack
- React Native 0.86, Kotlin 2.1.20, Android SDK 36, NDK 27.1
- Hermes engine, New Architecture (Fabric) enabled
- IME service uses ReactSurface API (not startReactApplication)

# ADR Process

ADRs live in `docs/architecture/decisions/`. Filename: `ADR-NNN-slug.md`.

## Required sections (in order)

1. **Context** — why this decision exists, what is broken or missing
2. **Goals** — measurable properties the solution must have (e.g. "adding X = N file changes")
3. **Architecture** — layered design: interfaces first, implementations second, wiring last
4. **Files** — table of every file touched: New / Modify / No change, one-line role
5. **Consequences** — what becomes easier, what is deferred, what trade-offs remain
6. **Workflow** — DAG of implementation tasks (see below)

## Workflow DAG rules

Every ADR must end with a `## Workflow` section containing:

**Task list** — enumerate every atomic implementation unit (A, B, C, …). Each task is one of:
- a new file
- a targeted change to an existing file (state which lines/methods)
- a pure deletion

**Dependency edges** — for each task, list which earlier tasks it requires.
A task with no dependencies is a Wave 0 root.

**Wave table** — group tasks by wave (all dependencies satisfied by earlier waves):

| Wave | Tasks | Can parallelise? |
|---|---|---|
| 0 | A, B | yes |
| 1 | C (needs A), D (needs B), E (no deps) | yes |
| … | … | … |

**Critical path** — the longest chain from root to leaf. Label it explicitly.

**Blocking notes** — call out any task that is riskier than it looks (touches working code,
requires a two-pass edit, or blocks end-to-end testing).

Once the Workflow section exists, implementation begins wave by wave.
Complete all tasks in a wave (or as many as possible in parallel) before starting the next.
Mark each task done in the ADR as it is finished.

## Immutability rule

When an ADR introduces new abstractions alongside existing working code, the existing files
are **never modified** unless the ADR explicitly marks them `Modify` with a specific,
minimal change (e.g. "add 3 lines to onCreateInputView"). Any refactor that would touch
working logic must instead be expressed as a new parallel implementation that reads the
old code as a read-only source. This keeps the old behaviour reachable for diffing and
rollback without a git revert.
