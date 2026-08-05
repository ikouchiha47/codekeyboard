# Phase 2: Kotlin Trie Reader — Detailed Breakdown

## What this phase delivers

A `Trie.kt` class that reads `assets/en.trie` using the same TRIE2 binary format as
`src/keyboard/Trie.ts`. Loaded once in `CodeKeyboardIME.onCreate()`. Exposes:

```kotlin
class Trie {
    fun suggest(prefix: String, max: Int = 3): List<String>
    fun has(word: String): Boolean
}
```

The JS trie (`Trie.ts`, `Dictionary.ts`) is NOT deleted in this phase — it still
drives the RN preview text field. Delete only when RN is removed (future phase).

---

## TRIE2 Binary Format Spec

This is the canonical format spec derived by reading `Trie.ts` and inspecting
`en.trie` with a binary parser. The Kotlin reader must match this exactly or
suggestions will silently break.

### Header (12 bytes, at file offset 0)

| Offset | Size | Type     | Value         |
|--------|------|----------|---------------|
| 0      | 5    | ASCII    | `TRIE2` magic |
| 5      | 3    | —        | Reserved (zeroes) |
| 8      | 4    | uint32LE | nodeCount (= 34522 in en.trie) |

### Node Array (nodeCount × 8 bytes, starts at file offset 12)

Each node is 8 bytes:

| Offset in node | Size | Type     | Meaning |
|----------------|------|----------|---------|
| 0              | 1    | uint8    | Character this node represents. 0 = root node. |
| 1              | 1    | uint8    | Flags: bit 0 = isEnd (node terminates a word), bit 1 = hasChildren |
| 2              | 4    | uint32LE | childrenOffset — byte offset into the **children section** (NOT the file) |
| 6              | 2    | —        | Reserved (unused) |

### Children Section (starts at byte `12 + nodeCount × 8`)

All children blocks live in this section. A node's children block is at:

```
absoluteOffset = 12 + nodeCount * 8 + node.childrenOffset
```

This is the critical point: `childrenOffset` is relative to the start of the children
section, **not** the start of the file. Getting this wrong causes every lookup to
return garbage without throwing an exception.

Each children block:

| Offset in block | Size | Type     | Meaning |
|-----------------|------|----------|---------|
| 0               | 1    | uint8    | childCount |
| 1 + i*3         | 1    | uint8    | Character (ASCII, lowercase) for child i |
| 2 + i*3         | 2    | uint16LE | nodeIndex of child i (index into node array) |

Max observed children in a single node: **26** (one per letter). Linear scan over
childCount is sufficient — no binary search needed.

### File layout summary

```
[0..11]                        header (12 bytes)
[12..12 + nodeCount*8 - 1]     node array (276,176 bytes for en.trie)
[12 + nodeCount*8..EOF]        children section (172,607 bytes for en.trie)
```

en.trie facts:
- File size: 448,795 bytes
- nodeCount: 34,522
- Children section: 172,607 bytes

---

## Algorithm

### `has(word)` / `walk(prefix)`

```
idx = 0  // root
for each char in prefix.lowercase():
    if node[idx].hasChildren == false: return -1
    childrenBlock = childrenSection[node[idx].childrenOffset]
    idx = childrenBlock.find(char)  // linear scan
    if not found: return -1
return idx
```

### `suggest(prefix, max)`

```
idx = walk(prefix)
if idx < 0: return emptyList()
results = []
stack = [(idx, "")]
while stack not empty and results.size < max:
    (nidx, suffix) = stack.pop()
    if node[nidx].isEnd and suffix.isNotEmpty():
        results.add(prefix + suffix)
    if node[nidx].hasChildren:
        for each child (c, cidx) in reverse order:
            stack.push((cidx, suffix + c))
return results
```

Reverse order on push preserves alphabetical order in the pop sequence (stack is LIFO).

---

## Performance budget

Measured on macOS (Python, slower than JVM):

| Operation | Time |
|-----------|------|
| File read from disk | <0.1ms |
| 1000 `suggest()` calls | 17ms total (~0.017ms each) |

Android targets (JVM, assets stored in APK — mmapped not decompressed):
- File open + `ByteBuffer.wrap`: expected <5ms on first `onCreate`
- Per `suggest()` call: expected <0.1ms (well under one frame at 60fps)
- Per `has()` call: expected <0.05ms

**Load time budget:** total trie load in `onCreate` must be <50ms. On a cold start
this is one of several `onCreate` tasks; 50ms gives plenty of headroom and will
likely run in <5ms on real hardware.

---

## Files that change

### 1. New: `Trie.kt`

Location: `android/app/src/main/java/com/codekeyboard/Trie.kt`

```kotlin
class Trie private constructor(private val buf: ByteBuffer) {

    companion object {
        private const val MAGIC = "TRIE2"
        private const val HEADER_SIZE = 12
        private const val NODE_SIZE = 8

        fun load(context: Context): Trie {
            context.assets.open("en.trie").use { stream ->
                val bytes = stream.readBytes()
                val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = buildString { repeat(5) { append(buf.get().toInt().toChar()) } }
                require(magic == MAGIC) { "Invalid trie magic: $magic" }
                return Trie(buf)
            }
        }
    }

    private val nodeCount: Int = run {
        buf.position(8)
        buf.int  // reads uint32 LE
    }
    private val childrenBase: Int = HEADER_SIZE + nodeCount * NODE_SIZE

    private fun nodeFlags(idx: Int): Int =
        buf.get(HEADER_SIZE + idx * NODE_SIZE + 1).toInt() and 0xFF

    private fun childrenOffset(idx: Int): Int =
        buf.getInt(HEADER_SIZE + idx * NODE_SIZE + 2)

    private fun findChild(nodeIdx: Int, char: Char): Int {
        if (nodeFlags(nodeIdx) and 2 == 0) return -1
        val blockOff = childrenBase + childrenOffset(nodeIdx)
        val childCount = buf.get(blockOff).toInt() and 0xFF
        val target = char.code
        for (i in 0 until childCount) {
            val base = blockOff + 1 + i * 3
            if ((buf.get(base).toInt() and 0xFF) == target) {
                return buf.getShort(base + 1).toInt() and 0xFFFF
            }
        }
        return -1
    }

    private fun walk(prefix: String): Int {
        var idx = 0
        for (ch in prefix) {
            idx = findChild(idx, ch)
            if (idx < 0) return -1
        }
        return idx
    }

    fun has(word: String): Boolean {
        if (word.isEmpty()) return false
        val idx = walk(word.lowercase())
        if (idx < 0) return false
        return (nodeFlags(idx) and 1) != 0
    }

    fun suggest(prefix: String, max: Int = 3): List<String> {
        if (prefix.isEmpty()) return emptyList()
        val lower = prefix.lowercase()
        val startIdx = walk(lower)
        if (startIdx < 0) return emptyList()

        val results = mutableListOf<String>()
        val stack = ArrayDeque<Pair<Int, String>>()
        stack.addLast(startIdx to "")

        while (stack.isNotEmpty() && results.size < max) {
            val (nidx, suffix) = stack.removeLast()
            val flags = nodeFlags(nidx)
            if ((flags and 1) != 0 && suffix.isNotEmpty()) {
                results.add(lower + suffix)
            }
            if ((flags and 2) != 0) {
                val blockOff = childrenBase + childrenOffset(nidx)
                val childCount = buf.get(blockOff).toInt() and 0xFF
                for (i in childCount - 1 downTo 0) {
                    val base = blockOff + 1 + i * 3
                    val ch = (buf.get(base).toInt() and 0xFF).toChar()
                    val cidx = buf.getShort(base + 1).toInt() and 0xFFFF
                    stack.addLast(cidx to (suffix + ch))
                }
            }
        }
        return results
    }
}
```

**Notes:**
- `ByteBuffer.order(LITTLE_ENDIAN)` is required — all multi-byte reads are LE.
- `buf.get().toInt() and 0xFF` prevents sign-extension on unsigned byte reads.
- `buf.getShort().toInt() and 0xFFFF` same for uint16.
- `buf.getInt()` reads int32; childrenOffset is stored as uint32 but values fit in
  positive int32 range for this file (max value << 2^31).
- `stream.readBytes()` reads the full file into a heap byte array — simpler than
  memory-mapping and fast enough for 438K.

### 2. `CodeKeyboardIME.kt`

Add in `onCreate()`:

```kotlin
private lateinit var trie: Trie

override fun onCreate() {
    super.onCreate()
    KeyboardSettings.init(this)
    trie = Trie.load(this)
}
```

The `trie` field is a class-level field. It is only read after `onCreate` completes.
No threading issues — `onCreate` runs on the main thread before any key events.

Phase 3 will wire `trie.suggest(word)` into `SuggestionBarView`. In Phase 2 the
trie is loaded but its output is not yet displayed.

---

## Test cases

### New test file: `TrieTest.kt`

These are pure JVM unit tests. No Android context — pass the binary file bytes
directly. Use `File("../app/src/main/assets/en.trie").readBytes()` to load the
real file in tests.

```kotlin
class TrieTest {
    private val trie: Trie = Trie.loadFromBytes(
        File("src/main/assets/en.trie").readBytes()
    )
    // ...
}
```

`Trie.loadFromBytes(bytes: ByteArray)` — add a secondary factory that skips
`context.assets` and takes raw bytes. Used only in tests.

**Test cases:**

| Test | Assertion |
|------|-----------|
| Magic validation | Loading bytes with wrong magic throws |
| `has("the")` | true |
| `has("hello")` | true |
| `has("world")` | true |
| `has("python")` | true |
| `has("an")` | true |
| `has("and")` | true |
| `has("xqzjw")` | false — not a word |
| `has("")` | false — empty string |
| `has("THE")` | true — case-insensitive |
| `suggest("hel", 3)` | first result is "help" or contains "help" |
| `suggest("th", 3)` | contains "the" |
| `suggest("xqzjw", 3)` | empty list |
| `suggest("", 3)` | empty list |
| `suggest("hel", 1)` | list size == 1 |
| `suggest("hel", 10)` | list size <= 10 and all start with "hel" |
| Performance: 1000 `suggest()` calls | completes in < 500ms total on JVM |

### Known-good values from en.trie (for assertions)

These were verified by walking the binary directly:

- `has("the")` → nodeIndex=6, isEnd=true
- `has("hello")` → nodeIndex=448, isEnd=true
- `has("world")` → nodeIndex=529, isEnd=true
- `has("python")` → nodeIndex=32813, isEnd=true
- `has("an")` → nodeIndex=11, isEnd=true
- `has("a")` → nodeIndex=10, **isEnd=false** (single "a" is not in the word list)
- `has("i")` → nodeIndex=8, **isEnd=false** (single "i" is not in the word list)
- `suggest("hel", 5)` → ["help", "helping", "helped", "helper", "helps"]
- `suggest("th", 3)` → ["the", "ther", "there"] (first 3)

---

## Wiring into ComposingEngine (preview for Phase 3)

In Phase 2 the trie is loaded but results are not shown. The Phase 3 wiring will be:

```
ComposingEngine.onChar(char)
  → composing.append(char) → setComposingText
  → trie.suggest(composing.text, 3) → SuggestionBarView.update(suggestions)
```

`ComposingEngine` receives `Trie` as a constructor parameter. If `trie` is null
(not yet loaded, or error), suggestions are skipped — no crash.

The Phase 3 doc will cover this in detail. Do not implement the wiring in Phase 2.

---

## Definition of done

- [ ] `Trie.kt` compiles and passes all unit tests
- [ ] `Trie.loadFromBytes()` secondary factory exists for tests
- [ ] `Trie.load(context)` loads from `assets/en.trie` without crash in `onCreate`
- [ ] `has("the")` returns true; `has("xqzjw")` returns false
- [ ] `suggest("hel", 3)` returns a non-empty list
- [ ] All `TrieTest` cases pass
- [ ] Existing `KeyboardStateTest`, `ComposingBufferTest` still pass
- [ ] Load completes in `onCreate` — no perceptible delay on cold start
