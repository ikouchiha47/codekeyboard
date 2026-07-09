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

# Version Bumping
- `versionCode` — increment by 1 each release (in `android/app/build.gradle`)
- `versionName` — bump when shipping visible changes

# Key Files
- `App.tsx` — root RN component, tab nav for Keyboard/Settings/Themes/Languages
- `src/keyboard/Keyboard.tsx` — main keyboard UI, action registry, input area
- `src/keyboard/Layout.ts` — keyboard layout definitions (SOFLE, layers)
- `src/keyboard/ModifierState.ts` — modifier/layer state machine
- `src/keyboard/Key.tsx` — individual key component
- `android/app/src/main/java/com/codekeyboard/` — IME native layer (Kotlin)
- `.github/workflows/build.yml` — CI pipeline

# Tech Stack
- React Native 0.86, Kotlin 2.1.20, Android SDK 36, NDK 27.1
- Hermes engine, New Architecture (Fabric) enabled
- IME service uses ReactSurface API (not startReactApplication)
