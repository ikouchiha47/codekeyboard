# ADR-004: User Trie Decay via WorkManager + Emoji Panel from Unicode Data

## Status
Accepted.

---

## Context

### User trie growth
`user.trie` is mutable and grows without bound. After months of use a user
could accumulate thousands of distinct words, many typed only once and never
again. Unbounded growth causes:
- Increasing load time (deserialize larger file on IME onCreate)
- Increasing suggest latency (more nodes to prune in A* traversal)
- Increasing disk use

A cap or eviction strategy is needed. The typing path must not be affected.

### Emoji
Standard keyboards surface an emoji selector. Building the emoji list by hand
is unmaintainable (~3600 base emoji in Unicode 15). The list must come from
an authoritative source and be regeneratable on Unicode updates.

---

## Decisions

### 1. Frequency decay — epoch-based, idle-only, via WorkManager

**What we store (additions to TRIE3 format):**
- Header: `decayEpoch: Int` — global counter incremented on each prune pass
- Each terminal node: `lastDecayEpoch: Int` — epoch when frequency was last decayed

**Typing path (unchanged):**
`insert()` and `suggest()` are untouched. No decay on every flush.
`WordLearner.learnFromFlush` and `learnFromTap` call `userTrie.insert()` as today.

**Flush path (unchanged):**
`onFinishInput` submits `userTrie.save()` to a single-thread executor.
The saved file reflects raw accumulated frequencies with no decay applied.

**Prune pass (new — WorkManager):**
A `TrieDecayWorker : CoroutineWorker` registered with constraints:
```
NetworkType.NOT_REQUIRED
requiresCharging = true
requiresDeviceIdle = true  // API 23+
```
Runs at most once per day. Steps:
1. Load `user.trie` from filesDir
2. Walk all terminals; apply `freq = freq * 0.9^(currentEpoch - lastDecayEpoch)`
3. Drop any terminal with decayed frequency < 1
4. Compact: remove interior nodes with no terminal descendants
5. Increment `decayEpoch`
6. Atomic save (write `.tmp`, rename)

**Why WorkManager over a plain coroutine / Handler:**
- Survives process death — if IME is killed mid-session the prune still runs
- System-managed scheduling respects battery and doze mode
- Constraints (idle + charging) guarantee it never runs during active typing
- `PeriodicWorkRequest` with a 24h interval prevents repeated passes

**Eventual consistency:**
The trie the IME reads may be up to 24h stale relative to the last prune. This
is acceptable — suggestion quality degrades gracefully. The user never observes
a stale suggestion; they may simply see a slightly inflated result set until the
next overnight pass.

**Safety net — hard cap:**
If the prune pass finds node count > 50,000 after decay (abnormal vocabulary),
it falls back to a top-5000-words-by-frequency rebuild from scratch. This
prevents a degenerate case where decay alone doesn't converge fast enough.

**No separate ledger needed:**
The trie itself is the ledger. Raw frequencies accumulate in-memory during a
session, are flushed to disk at session end, and decayed overnight. No extra
data structure, no write-ahead log.

---

### 2. Emoji panel — generated from Unicode data, not hardcoded

**Source:**
`https://unicode.org/Public/emoji/15.0/emoji-test.txt`

**Build-time generation:**
A Python script at `scripts/gen_emoji.py` parses the Unicode test file and
writes `android/app/src/main/assets/emoji.json`. Format:
```json
[
  { "category": "Smileys & Emotion", "emoji": ["😀","😃","😄",...] },
  { "category": "People & Body",     "emoji": ["👋","🤚","🖐",...] },
  ...
]
```
Skin-tone variants are collapsed under a long-press model (one entry per base
emoji; variants stored as a sub-array). Fully-qualified sequences only
(no text-presentation variants).

**Runtime panel:**
An `EmojiPanelView` replaces the keyboard view (via `setInputView`) when the
emoji key is tapped. It is a `RecyclerView` with a sticky category header and
a search bar. Tapping an emoji calls `ic.commitText(emoji, 1)` and hides the
panel. A back key or swipe-down restores the keyboard view.

**Emoji key placement:**
Added to the base layer of the Sofle layout alongside the space bar row.
Action string `"emoji"` in `KeyDef`, handled in `CodeKeyboardIME.handleKey`.

**Why not the system emoji picker:**
`InputMethodService.showSoftInput` cannot open a sibling IME's panel. The
system emoji picker (`InputMethodManager.showInputMethodPicker`) switches the
entire IME, losing composing state. A self-hosted panel is the standard
approach (Gboard, SwiftKey, AnySoftKeyboard all embed their own panel).

---

## Consequences

- Typing path: zero additional cost — insert/suggest/flush unchanged
- Overnight prune: O(N) walk, runs while charging and idle, invisible to user
- Trie size bounded at ~50k nodes (~5MB in-memory, ~800KB on disk at TRIE3 density)
- Emoji list stays current with one script re-run per Unicode release
- `scripts/gen_emoji.py` is the single source of truth — no hand-maintained lists
- WorkManager dependency added to `android/app/build.gradle`
