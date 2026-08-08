# ADR-002: GIF Picker Architecture

**Status:** Planned
**Date:** 2026-08-08

---

## Context

Users want to send GIFs from the keyboard, similar to Gboard. Gboard uses GIPHY via
Google's API. We need a GIF picker that works without bundling an API key into the app
(bad for open source — key gets scraped and abused).

---

## Decision

**BYOK (Bring Your Own Key)** — user registers for a free GIPHY or Tenor account,
gets their own API key, and pastes it into the Settings screen. Local MediaStore GIFs
(files already on the device) work with no key at all.

---

## Alternatives Considered

### A: Bundle a public/anonymous API key
- GIPHY's old public beta key (`dc6zaTOxFJmzC`) — **banned as of 2026**
- Tenor's anonymous key (`AIzaSyAyimkuYQYF_FXVALexPQygcBOMahvjmgg`) — **dead as of 2026**
- Rejected: these die unpredictably and break all users at once

### B: Scrape gifdb.com
- No public API exists — would require HTML scraping
- Fragile, breaks on site changes, may get IP-blocked
- Rejected: too unreliable for a shipped feature

### C: Local MediaStore only
- Query device storage for `image/gif` via `MediaStore` + `READ_MEDIA_IMAGES`
- Zero dependencies, works offline, fully private
- Good as a baseline tab but poor cold-start experience (user may have no GIFs saved)
- Kept as the "Saved" tab alongside external search

### D: Chosen — BYOK + Local
- Free GIPHY tier: 1000 searches/day, more than enough for personal use
- Free Tenor tier: similar limits
- User pastes key once in Settings; stored in SharedPreferences
- Local MediaStore tab always available regardless of key

---

## Implementation Plan

### Provider abstraction (Kotlin)
```kotlin
interface GifProvider {
    suspend fun trending(limit: Int = 24): List<GifItem>
    suspend fun search(query: String, limit: Int = 24): List<GifItem>
}

data class GifItem(val id: String, val previewUrl: String, val fullUrl: String)

class GiphyProvider(private val apiKey: String) : GifProvider { ... }
class TenorProvider(private val apiKey: String) : GifProvider { ... }
class LocalGifProvider(private val context: Context) : GifProvider { ... }
```

### Settings screen (React Native)
- "GIF Source" section in existing Settings tab
- Dropdown: None / GIPHY / Tenor
- Text field for API key (shown only when provider selected)
- Key stored via `SharedPreferences`, read by IME layer at runtime

### GIF panel in keyboard
- Tab alongside emoji panel
- Two sub-tabs: **Trending** (external, needs key) + **Saved** (local MediaStore)
- Search bar at top
- Grid of GIF previews via Glide (handles animated GIF playback)
- On tap: download to temp file, send via `InputConnection.commitContent()`

### Android permissions needed
- `INTERNET` — for external GIF search
- `READ_MEDIA_IMAGES` — for local MediaStore tab (Android 13+)

---

## Sending GIFs

Android rich content API:
```kotlin
val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", tempFile)
inputConnection.commitContent(
    InputContentInfoCompat(uri, ClipDescription("gif", arrayOf("image/gif")), null),
    InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
    null
)
```

Receiving apps (WhatsApp, Telegram, etc.) must declare `android:name="android.view.inputmethod.InputMethod"` keyboard target support — all major messengers do.

---

## Consequences

**Good:**
- No bundled key to scrape or revoke
- Local tab works offline with zero setup
- Provider-agnostic — easy to add gifdb if they ever ship a public API
- Fits existing Settings tab in RN without new screens

**Bad:**
- Cold start for new users with no key and no saved GIFs is empty
- Requires user to create a GIPHY/Tenor account (one-time friction)
- GIPHY/Tenor free tier limits could be hit by power users

---

## References

- GIPHY Developers: https://developers.giphy.com (free tier: 1000 req/day)
- Tenor API: https://developers.google.com/tenor (free tier via Google Cloud)
- Android commitContent: https://developer.android.com/reference/androidx/core/view/inputmethod/InputConnectionCompat
- Glide GIF support: https://bumptech.github.io/glide/
