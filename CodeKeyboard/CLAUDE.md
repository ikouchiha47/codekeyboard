# Build

## CI (primary)
Push to `master` — GitHub Actions builds APK automatically.
Artifact: `app-release.apk` uploaded to the workflow run.

## Local (fallback)
```bash
cd android && ./gradlew assembleRelease
```

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
