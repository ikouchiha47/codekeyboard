package com.codekeyboard

import android.graphics.Color

data class KeyboardTheme(
    val bg: Int,
    val key: Int,
    val keyMod: Int,
    val keyThumb: Int,
    val keyLayer: Int,
    val keyActive: Int,
    val keyLocked: Int,
    val accent: Int,
    val label: Int,
    val labelMod: Int,
    val labelLayer: Int,
    val labelSub: Int,
    // Suggestion bar / emoji panel surfaces. Defaulted from the fields above
    // so the 8 existing dark themes don't need updating individually — only
    // a theme that wants a genuinely different panel treatment (e.g. a light
    // theme where "bg" and "key" read closer together than on dark themes)
    // needs to override these explicitly.
    val panelBg: Int = key,
    val panelBar: Int = bg,
    val divider: Int = keyMod,
    val panelTextMuted: Int = labelSub,
)

object KeyboardThemes {
    private fun c(hex: String) = Color.parseColor(hex)

    val ALL: Map<String, KeyboardTheme> = mapOf(
        "carbon" to KeyboardTheme(
            bg = c("#111111"), key = c("#2c2c2c"), keyMod = c("#252525"),
            keyThumb = c("#1a2a3a"), keyLayer = c("#162616"),
            keyActive = c("#1a3a5c"), keyLocked = c("#264f78"),
            accent = c("#4a9eff"),
            label = c("#e0e0e0"), labelMod = c("#aac4e0"),
            labelLayer = c("#7eb8ff"), labelSub = c("#666666"),
        ),
        "midnight" to KeyboardTheme(
            bg = c("#080d14"), key = c("#0f1c2e"), keyMod = c("#0a1422"),
            keyThumb = c("#0c1f38"), keyLayer = c("#091830"),
            keyActive = c("#0d2a50"), keyLocked = c("#123a6a"),
            accent = c("#3b82f6"),
            label = c("#c8d8ec"), labelMod = c("#7badd4"),
            labelLayer = c("#60a5fa"), labelSub = c("#3a5070"),
        ),
        "obsidian" to KeyboardTheme(
            bg = c("#0c0c10"), key = c("#1a1a24"), keyMod = c("#14141e"),
            keyThumb = c("#181828"), keyLayer = c("#12121c"),
            keyActive = c("#2a1a40"), keyLocked = c("#3a2858"),
            accent = c("#8b5cf6"),
            label = c("#ddd8f0"), labelMod = c("#a090cc"),
            labelLayer = c("#a78bfa"), labelSub = c("#504060"),
        ),
        "ash" to KeyboardTheme(
            bg = c("#141210"), key = c("#272320"), keyMod = c("#201d1a"),
            keyThumb = c("#2a2218"), keyLayer = c("#1e1a10"),
            keyActive = c("#3a2a10"), keyLocked = c("#504018"),
            accent = c("#f59e0b"),
            label = c("#e8e0d4"), labelMod = c("#c0ae90"),
            labelLayer = c("#fbbf24"), labelSub = c("#60503a"),
        ),
        "moss" to KeyboardTheme(
            bg = c("#0d1210"), key = c("#1a2420"), keyMod = c("#141e1a"),
            keyThumb = c("#162820"), keyLayer = c("#0e1e14"),
            keyActive = c("#0e2e24"), keyLocked = c("#124030"),
            accent = c("#2dd4bf"),
            label = c("#d4e8e0"), labelMod = c("#88b8a8"),
            labelLayer = c("#5eead4"), labelSub = c("#3a5a50"),
        ),
        "dusk" to KeyboardTheme(
            bg = c("#10101a"), key = c("#1e1e2e"), keyMod = c("#181828"),
            keyThumb = c("#1c1c30"), keyLayer = c("#141420"),
            keyActive = c("#2e1a2e"), keyLocked = c("#3e2040"),
            accent = c("#f43f5e"),
            label = c("#e0d8f0"), labelMod = c("#a898cc"),
            labelLayer = c("#fb7185"), labelSub = c("#504060"),
        ),
        "iron" to KeyboardTheme(
            bg = c("#0e1014"), key = c("#1c2028"), keyMod = c("#161a22"),
            keyThumb = c("#1a2030"), keyLayer = c("#121820"),
            keyActive = c("#0e2a30"), keyLocked = c("#123a44"),
            accent = c("#06b6d4"),
            label = c("#d0dae8"), labelMod = c("#80a0c0"),
            labelLayer = c("#22d3ee"), labelSub = c("#405060"),
        ),
        "ember" to KeyboardTheme(
            bg = c("#100c08"), key = c("#241c14"), keyMod = c("#1c1410"),
            keyThumb = c("#28200e"), keyLayer = c("#1e1408"),
            keyActive = c("#381800"), keyLocked = c("#502400"),
            accent = c("#ea580c"),
            label = c("#ecdcc8"), labelMod = c("#c09868"),
            labelLayer = c("#fb923c"), labelSub = c("#604838"),
        ),
        "frost" to KeyboardTheme(
            bg = c("#eef3fa"), key = c("#dbe6f5"), keyMod = c("#c7d9f0"),
            keyThumb = c("#d3e3f7"), keyLayer = c("#cfe0f3"),
            keyActive = c("#a9c9f2"), keyLocked = c("#6f9cea"),
            accent = c("#2f6fed"),
            label = c("#16233d"), labelMod = c("#3c5a85"),
            labelLayer = c("#2f6fed"), labelSub = c("#6b7f9e"),
            // Light themes need the panel bar to read as a distinct, slightly
            // deeper surface than the panel body — reusing bg (near-white)
            // for both would erase the visual separation dark themes get for
            // free (bg is already the deepest tone there).
            panelBg = c("#f6f9fd"), panelBar = c("#dde8f7"),
            divider = c("#c7d9f0"), panelTextMuted = c("#5b7096"),
        ),
    )

    val DEFAULT = ALL["carbon"]!!

    fun load(): KeyboardTheme {
        val name = KeyboardSettings.getString("theme", "carbon")
        return ALL[name] ?: DEFAULT
    }
}
