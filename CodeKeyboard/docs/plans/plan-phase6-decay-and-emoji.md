# Phase 6: User Trie Decay + Emoji Panel

## What this phase delivers

1. **Bounded user trie** — frequency decay via a WorkManager idle job prevents
   unbounded growth. Typing path is completely unaffected.
2. **Emoji panel** — self-hosted emoji selector populated from Unicode data,
   accessible via a dedicated key on the base layer.

See ADR-004 for rationale and format decisions.

---

## Part A: User trie decay

### A1 — Extend TRIE3 format

File: `TrieWriter.kt`

Header gains one field (header grows from 16 to 20 bytes):
```
magic:          4 bytes  "TRI3"
nodeCount:      4 bytes
totalCommits:   4 bytes
decayEpoch:     4 bytes  (was reserved)
```

Each terminal node gains `lastDecayEpoch: Int` (4 bytes, appended after
`maxDescendantFreq`). Node size grows from 16 to 20 bytes.

Backward compatibility: if file length matches the old format (16-byte nodes),
load without `lastDecayEpoch` and default it to 0.

Changes:
- `TrieWriter.serialize`: write `decayEpoch` in header; write `lastDecayEpoch`
  per node
- `TrieWriter.deserialize`: read both; detect old format by size
- `UserTrieNode`: add `var lastDecayEpoch: Int = 0`
- `UserTrie`: add `var decayEpoch: Int = 0`

### A2 — TrieDecayWorker

File: `TrieDecayWorker.kt` (new)

```kotlin
class TrieDecayWorker(ctx: Context, params: WorkerParameters)
    : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val file = File(applicationContext.filesDir, "user.trie")
        val trie = withContext(Dispatchers.IO) { UserTrie.load(file) }
        val newEpoch = trie.decayEpoch + 1
        trie.applyDecay(factor = 0.9, newEpoch = newEpoch)
        withContext(Dispatchers.IO) { trie.save(file) }
        return Result.success()
    }
}
```

`UserTrie.applyDecay(factor, newEpoch)`:
- DFS all nodes
- For terminals: `freq = (freq * factor^(newEpoch - lastDecayEpoch)).toInt()`
- If `freq < 1`: set `frequency = 0` (marks as non-terminal)
- Update `lastDecayEpoch = newEpoch`
- After DFS: compact (remove interior nodes with `maxDescendantFreq == 0`)
- Recompute `maxDescendantFreq` bottom-up
- If node count > 50,000: rebuild from top-5000 terminals by frequency
- Set `trie.decayEpoch = newEpoch`

### A3 — Register WorkManager job

File: `MainApplication.kt` (or `CodeKeyboardIME.onCreate`)

```kotlin
val request = PeriodicWorkRequestBuilder<TrieDecayWorker>(1, TimeUnit.DAYS)
    .setConstraints(
        Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresDeviceIdle(true)
            .build()
    )
    .build()

WorkManager.getInstance(context).enqueueUniquePeriodicWork(
    "trie_decay",
    ExistingPeriodicWorkPolicy.KEEP,
    request
)
```

### A4 — Dependency

`android/app/build.gradle`:
```groovy
implementation "androidx.work:work-runtime-ktx:2.9.0"
```

### A5 — Tests

File: `TrieDecayTest.kt` (new)

Acceptance tests:
- Word typed once, decay applied 22 times → frequency rounds to 0, not suggested
- Word typed 10 times, decay applied once → still suggested, reduced frequency
- Compaction: after decay, nodes with no terminal descendants are removed
- Hard cap: trie with 6000 terminals after decay → pruned to 5000
- Round-trip: save after decay → load → frequencies preserved
- Old format load: 16-byte node file loads without crash, `lastDecayEpoch` defaults to 0

---

## Part B: Emoji panel

### B1 — Generate emoji.json

File: `scripts/gen_emoji.py`

Fetches (or reads local copy of) `emoji-test.txt`. Outputs
`android/app/src/main/assets/emoji.json`:
```json
[
  {
    "category": "Smileys & Emotion",
    "emoji": [
      { "base": "😀", "variants": [] },
      { "base": "👋", "variants": ["👋🏻","👋🏼","👋🏽","👋🏾","👋🏿"] }
    ]
  },
  ...
]
```

Run once, commit the output. Re-run on Unicode updates.

### B2 — EmojiPanelView

File: `EmojiPanelView.kt` (new)

- `RecyclerView` with `GridLayoutManager` (8 columns)
- Sticky category headers via `ConcatAdapter` or section header decoration
- Each cell: `TextView` with emoji glyph, font size 24sp
- Long press on a cell with variants: show a small popup row of variant options
- `onEmojiSelected: (String) -> Unit` callback
- `close()` method called on back or swipe-down

### B3 — Wire into IME

File: `CodeKeyboardIME.kt`

Add `private var emojiPanel: EmojiPanelView? = null` field.

In `handleKey`, add case `"emoji"`:
```kotlin
"emoji" -> showEmojiPanel()
```

```kotlin
private fun showEmojiPanel() {
    if (emojiPanel == null) {
        emojiPanel = EmojiPanelView(this).apply {
            onEmojiSelected = { emoji ->
                currentInputConnection?.commitText(emoji, 1)
                hideEmojiPanel()
            }
        }
    }
    setInputView(emojiPanel)
}

private fun hideEmojiPanel() {
    setInputView(onCreateInputView())
}
```

### B4 — Add emoji key to layout

File: `SofleLayoutComputer.kt` (or equivalent layout definition)

Add `KeyDef(label = "😊", action = "emoji")` to the space bar row on the base
layer. Position: left of space bar.

### B5 — Tests

File: `EmojiPanelTest.kt` (new, Robolectric or pure logic)

- `gen_emoji.py` output is valid JSON with at least 10 categories
- `EmojiPanelView` loads JSON without crash
- `onEmojiSelected` fires with correct emoji on cell tap
- Variants array is non-empty only for skin-tone-eligible emoji

---

## Sequence

```
A1 (format) → A2 (worker) → A3 (register) → A4 (dep) → A5 (tests)
B1 (script) → B2 (panel)  → B3 (wire IME) → B4 (key) → B5 (tests)
```

A and B are independent — can be done in either order.

## Acceptance criteria

- After 24 hours idle+charging, `user.trie` node count does not grow unboundedly
- A word inserted once and not used for 22+ prune cycles does not appear in suggestions
- Emoji key opens panel; tapping any emoji commits it to the focused field
- `emoji.json` is regeneratable by running `python scripts/gen_emoji.py` with no manual edits needed
- All existing tests (211) continue to pass
