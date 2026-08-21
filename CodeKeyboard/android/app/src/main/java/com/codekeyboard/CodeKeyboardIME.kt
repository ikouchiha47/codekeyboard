package com.codekeyboard

import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.KeyEvent
import android.widget.LinearLayout
import android.text.InputType
import java.io.File
import java.util.concurrent.Executors

class CodeKeyboardIME : InputMethodService() {

    companion object {
        // Longest plausible English word is 45 chars; 50 gives a safe margin.
        private const val RECOMPOSE_SCAN_CHARS = 50
        // Guard window: onUpdateSelection arrivals within this many ms of a
        // recompose are treated as IME-driven, not user cursor moves.
        private const val IME_SELECTION_GUARD_MS = 200L
        // How far back to look for a sentence-ending "." "!" "?" when a field
        // gains focus, to decide whether Sentence Case should start armed.
        private const val SENTENCE_CASE_SCAN_CHARS = 8
    }

    private lateinit var keyboardView: NativeKeyboardView
    private lateinit var suggestionBar: SuggestionBarView
    private lateinit var wordDict: WordDictionary
    private lateinit var userTrie: UserTrie
    private lateinit var suggestionStrategy: SuggestionStrategy
    private lateinit var wordLearner: WordLearner
    private val kbState = KeyboardState()
    private val composing = ComposingBuffer()
    // Captured from kbState.shift the moment the CURRENT composing word's
    // first letter was typed. Shift's latch clears off kbState per-letter (so
    // display case is right as you type), so by the time a suggestion chip is
    // tapped the live state has already been consumed — this remembers what
    // it was when the word started, for handleSuggestionTap to apply.
    private var composingStartedShift = LatchState.NONE
    private var activeLayoutId = LayoutRegistry.DEFAULT_LAYOUT
    private var activeKeyMapId = KeyMapRegistry.DEFAULT.id
    private var expectSelectionUpdateBy = 0L  // if now < this, onUpdateSelection is IME-driven, not user
    private var keystrokesSinceCommit = 0
    private var supportsComposing = true
    private val flushExecutor = Executors.newSingleThreadExecutor()
    private var emojiPanel: EmojiPanelView? = null
    private lateinit var packBigram: PackBackedBigramModel
    private lateinit var ngram: Ngram
    private var prevCommittedWord = ""
    // Rolling window of recently committed words, sized off the cascade's
    // highest order (ADR-008) — feeds the trigram tier's 2-word context.
    private val recentContext = ArrayDeque<String>()

    // ── Emoji panel ───────────────────────────────────────────────────────────

    private fun showEmojiPanel() {
        try {
            // The regular keyboard wrapper has setPadding(0,0,0,navBarHeight) which leaves
            // space at the bottom for the system IME controls (globe, minimize). We must
            // use the WRAPPER's full height (children + that padding) and apply the same
            // bottom padding inside the emoji panel so our content never enters that zone.
            val wrapper = keyboardView.parent as? android.view.View
            val kbHeight = wrapper?.height
                ?: (keyboardView.height + suggestionBar.height + getNavBarHeight())
            val navPad = getNavBarHeight() + (12 * resources.displayMetrics.density).toInt()

            if (emojiPanel == null || emojiPanel?.tag != kbHeight) {
                emojiPanel = EmojiPanelView(this, kbHeight, navPad).apply {
                    tag = kbHeight
                    onEmojiSelected = { emoji ->
                        currentInputConnection?.commitText(emoji, 1)
                    }
                    onBackToKeyboard = { hideEmojiPanel() }
                    onDeletePressed = {
                        currentInputConnection?.deleteSurroundingText(1, 0)
                    }
                }
            }
            setInputView(emojiPanel)
        } catch (e: Exception) {
            emojiPanel = null
            android.util.Log.e("CodeKeyboard", "emoji panel failed: $e")
        }
    }

    private fun hideEmojiPanel() {
        emojiPanel = null
        setInputView(onCreateInputView())
    }

    // Modifier name → KeyEvent meta flag — extend this map to add new modifiers.
    private val MODIFIER_META_FLAGS = mapOf(
        "ctrl" to KeyEvent.META_CTRL_ON,
        "alt"  to KeyEvent.META_ALT_ON,
        "meta" to KeyEvent.META_META_ON,
    )

    private val STATE_HOLD_ACTIONS = KeyboardState.HOLD_STATE_MODIFIERS + KeyboardState.LAYER_HOLDS

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        KeyboardSettings.init(this)
        SnippetStore.init()

        // Load CKLM language pack for the active locale (configurable via RN settings).
        // The locale is read from SharedPreferences ("language", default "en") — the
        // same bridge the RN settings screen uses for layout/keymap. Pack asset is
        // copied from assets to filesDir on first run for mmap access.
        val locale = KeyboardSettings.getString("language", "en")
        val packAssetName = "$locale.cklm"
        val packFile = File(filesDir, packAssetName)
        if (!packFile.exists()) {
            assets.open(packAssetName).use { input ->
                packFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        val pack = LanguagePack.open(packFile)
        wordDict = WordDictionary(pack)
        userTrie = UserTrie.load(File(filesDir, "user.trie"))

        // Pack-backed n-gram cascade: trigram (order=3) + bigram (order=2)
        ngram = Ngram(listOf(
            PackNgramModel(pack, order = 3),
            PackNgramModel(pack, order = 2),
        ))

        // Pack-backed bigram with user-learned decay layer for personalization.
        // The user layer file is configurable; the static seed comes from the pack
        // (PackBackedBigramModel ignores BigramModel's seed, so we pass the user file
        // as both args — loadSeed() reads it but the pack path doesn't use it).
        val userBigram = BigramModel(File(filesDir, "user_bigrams.json"), File(filesDir, "user_bigrams.json"))
        userBigram.loadUserLayer()
        packBigram = PackBackedBigramModel(PackNgramModel(pack, order = 2), userBigram)

        // Create adapters for fuzzy search
        val userAdapter = UserTrieAdapter(userTrie)
        val baseAdapter = wordDict.adapter

        suggestionStrategy = BigramAwareSuggestionStrategy(
            MergedSuggestionStrategy(userAdapter, baseAdapter, wordDict), packBigram)
        wordLearner = WordLearner(userTrie) { word -> wordDict.suggest(word, 1).firstOrNull() == word }
        Metrics.client = LogMetrics
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        val layoutId = KeyboardSettings.getString("layout", LayoutRegistry.DEFAULT_LAYOUT)
        val keyMapId = KeyboardSettings.getString("keymap", KeyMapRegistry.DEFAULT.id)
        if (layoutId != activeLayoutId || keyMapId != activeKeyMapId) {
            setInputView(onCreateInputView())
        }
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density

        val layoutId = KeyboardSettings.getString("layout", LayoutRegistry.DEFAULT_LAYOUT)
        val keyMapId = KeyboardSettings.getString("keymap", KeyMapRegistry.DEFAULT.id)
        activeLayoutId = layoutId
        activeKeyMapId = keyMapId

        keyboardView = NativeKeyboardView(this)
        keyboardView.computer    = LayoutRegistry.build(layoutId, keyMapId, density)
        keyboardView.kbState     = kbState
        keyboardView.onKeyTapped = { key -> handleKey(key) }
        keyboardView.onKeyHeld   = { key -> handleHold(key) }
        keyboardView.onKeyReleased = { key -> handleRelease(key) }

        // Wrap the keyboard in a container that adds bottom padding for the
        // navigation bar so the bottom row of keys is never hidden.
        suggestionBar = SuggestionBarView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (40 * density).toInt()
            )
            onSlotTapped = { word -> handleSuggestionTap(word) }
        }

        val wrapper = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
            // Must be opaque — transparent wrapper lets app content bleed through
            // the nav-bar padding area below the keys.
            setBackgroundColor(Color.parseColor("#111111"))
        }
        wrapper.addView(suggestionBar)
        wrapper.addView(keyboardView)

        // Read navigation bar height from system resource (always reliable
        // for IME windows — IMEs don't dispatch WindowInsets like regular apps).
        val navBarHeight = getNavBarHeight()
        val extraPad = (keyboardView.computer!!.extraBottomPadDp * density).toInt()
        wrapper.setPadding(0, 0, 0, navBarHeight + extraPad)

        // Initial key compute — will be corrected by onSizeChanged once the
        // view has real dimensions.
        val w = resources.displayMetrics.widthPixels
        val c = keyboardView.computer!!
        keyboardView.setKeys(c.compute(w, kbState.effectiveLayer), kbState, c.heightPx(w))

        return wrapper
    }

    // Called by the framework whenever the selection or composing region changes.
    // If the cursor moved outside the active composing region (user tapped elsewhere),
    // abandon composing so the next character is inserted at the real cursor position
    // rather than replacing the stale composing region.
    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int,
        candidatesStart: Int, candidatesEnd: Int,
    ) {
        super.onUpdateSelection(oldSelStart, oldSelEnd, newSelStart, newSelEnd, candidatesStart, candidatesEnd)
        val imedriven = android.os.SystemClock.uptimeMillis() < expectSelectionUpdateBy
        android.util.Log.d("CKB_COMPOSE", "onUpdateSelection: composing='${composing.text}' sel=[$newSelStart,$newSelEnd] candidates=[$candidatesStart,$candidatesEnd] imeDriven=$imedriven")
        if (composing.text.isEmpty()) {
            android.util.Log.d("CKB_COMPOSE", "onUpdateSelection: composing empty, skip")
            return
        }
        if (imedriven) {
            android.util.Log.d("CKB_COMPOSE", "onUpdateSelection: IME-driven update, skip clear")
            return
        }
        val cursorOutsideComposing = candidatesStart == -1 || candidatesEnd == -1 ||
            newSelStart < candidatesStart || newSelStart > candidatesEnd ||
            newSelEnd   < candidatesStart || newSelEnd   > candidatesEnd
        android.util.Log.d("CKB_COMPOSE", "onUpdateSelection: cursorOutsideComposing=$cursorOutsideComposing")
        if (cursorOutsideComposing) {
            android.util.Log.d("CKB_COMPOSE", "onUpdateSelection: CLEARING composing buffer (was='${composing.text}')")
            currentInputConnection?.finishComposingText()
            composing.clear()
            if (::suggestionBar.isInitialized) suggestionBar.clear()
        }
    }

    override fun onStartInput(editorInfo: EditorInfo?, restarting: Boolean) {
        super.onStartInput(editorInfo, restarting)
        // onStartInput can fire before onCreateInputView on first invocation,
        // when keyboardView (lateinit) isn't assigned yet — guard instead of
        // skipping the reload outright, otherwise theme changes never reach
        // an already-live view (Settings writes the pref, but nothing re-reads it).
        if (::keyboardView.isInitialized) {
            keyboardView.reloadTheme()
        }
        if (::suggestionBar.isInitialized) {
            suggestionBar.reloadTheme()
        }
        CodeKeyboardModuleHolder.module?.inputConnection = currentInputConnection
        supportsComposing = when {
            editorInfo == null -> false
            editorInfo.inputType == InputType.TYPE_NULL -> false
            isPasswordField(editorInfo) -> false
            isNumericField(editorInfo) -> false
            else -> true
        }
        composing.clear()
        prevCommittedWord = ""
        recentContext.clear()
        currentInputConnection?.finishComposingText()
        if (::suggestionBar.isInitialized) suggestionBar.clear()

        kbState.sentenceCaseEnabled = KeyboardSettings.getBoolean("sentenceCase", true)
        if (kbState.sentenceCaseEnabled && supportsComposing) {
            val before = currentInputConnection?.getTextBeforeCursor(SENTENCE_CASE_SCAN_CHARS, 0)?.toString()
            val atSentenceStart = when {
                before == null -> true // no field content available — treat as start
                before.isEmpty() -> true
                else -> before.trimEnd().lastOrNull()?.let { it == '.' || it == '!' || it == '?' } ?: false
            }
            if (atSentenceStart) kbState.armSentenceCaseShift()
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()
        currentInputConnection?.finishComposingText()
        composing.clear()
        if (::suggestionBar.isInitialized) suggestionBar.clear()
        scheduleUserTrieFlush()
    }

    private fun scheduleUserTrieFlush() {
        if (!::userTrie.isInitialized) return
        val snapshot = userTrie
        val target = File(filesDir, "user.trie")
        flushExecutor.submit { snapshot.save(target) }
        // Also persist the user-learned bigram layer
        if (::packBigram.isInitialized) {
            flushExecutor.submit { packBigram.persistUserLayer() }
        }
    }

    private fun isPasswordField(info: EditorInfo): Boolean {
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
               variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
               variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
    }

    private fun isNumericField(info: EditorInfo): Boolean {
        val cls = info.inputType and InputType.TYPE_MASK_CLASS
        return cls == InputType.TYPE_CLASS_NUMBER ||
               cls == InputType.TYPE_CLASS_PHONE ||
               cls == InputType.TYPE_CLASS_DATETIME
    }

    // ── Key handling ──────────────────────────────────────────────────────────

    private fun handleKey(key: KeyDef) {
        val ic = currentInputConnection
        val action = key.action ?: ""

        when (action) {
            // ── Layer keys ────────────────────────────────────────────────────
            in KeyboardState.LAYER_HOLDS -> {
                kbState.cycleLayer(action)
                keyboardView.notifyStateChanged(kbState)
            }

            // ── Modifier state keys ──────────────────────────────────────────
            in KeyboardState.CYCLE_MODIFIERS -> {
                kbState.cycleModifier(action)
                keyboardView.notifyStateChanged(kbState)
            }

            // ── Backspace / Delete ────────────────────────────────────────────────
            "backspace" -> {
                val sel = ic?.getSelectedText(0)
                when {
                    !sel.isNullOrEmpty() -> {
                        ic?.beginBatchEdit()
                        ic?.finishComposingText()
                        composing.clear()
                        ic?.commitText("", 1)
                        ic?.endBatchEdit()
                        suggestionBar.clear()
                    }
                    composing.backspace() -> {
                        val word = composing.text
                        android.util.Log.d("CKB_COMPOSE", "backspace: in-composing, buffer now='$word'")
                        ic?.setComposingText(word, 1)
                        val suggestions = if (word.startsWith(";")) {
                            SnippetStore.matching(word.drop(1))
                        } else {
                            suggestionStrategy.suggest(word, 5, context = prevCommittedWord)
                        }
                        suggestionBar.update(word, suggestions)
                    }
                    else -> {
                        android.util.Log.d("CKB_COMPOSE", "backspace: composing empty → deleteSurroundingText then recompose")
                        if (ic?.deleteSurroundingText(1, 0) != true) sendDownUp(ic, KeyEvent.KEYCODE_DEL)
                        recomposeWordAtCursor(ic)
                    }
                }
            }
            "delete" -> {
                val sel = ic?.getSelectedText(0)
                if (!sel.isNullOrEmpty()) ic?.commitText("", 1)
                else if (ic?.deleteSurroundingText(0, 1) != true) sendDownUp(ic, KeyEvent.KEYCODE_FORWARD_DEL)
            }

            // ── Other action keys ─────────────────────────────────────────────
            "enter" -> {
                flushComposing(ic)
                val editorInfo = currentInputEditorInfo
                val action = editorInfo?.let { it.imeOptions and EditorInfo.IME_MASK_ACTION } ?: EditorInfo.IME_ACTION_UNSPECIFIED
                val noEnterAction = editorInfo?.let { it.imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION } ?: 0
                if (noEnterAction != 0 || action == EditorInfo.IME_ACTION_UNSPECIFIED || action == EditorInfo.IME_ACTION_NONE) {
                    sendDownUp(ic, KeyEvent.KEYCODE_ENTER)
                } else {
                    if (ic?.performEditorAction(action) != true) sendDownUp(ic, KeyEvent.KEYCODE_ENTER)
                }
            }
            "tab"         -> { flushComposing(ic); sendDownUp(ic, KeyEvent.KEYCODE_TAB) }
            "space"       -> {
                flushComposing(ic)
                commitCharAndNotify(ic, " ")
                val next = ngram.nextWords(recentContext.toList(), n = 5)
                if (next.isNotEmpty()) suggestionBar.update("", next)
            }
            "emoji"       -> showEmojiPanel()
            "escape"      -> sendDownUp(ic, KeyEvent.KEYCODE_ESCAPE)
            "arrow-left"  -> sendDownUp(ic, KeyEvent.KEYCODE_DPAD_LEFT)
            "arrow-right" -> sendDownUp(ic, KeyEvent.KEYCODE_DPAD_RIGHT)
            "arrow-up"    -> sendDownUp(ic, KeyEvent.KEYCODE_DPAD_UP)
            "arrow-down"  -> sendDownUp(ic, KeyEvent.KEYCODE_DPAD_DOWN)
            "meta"        -> sendDownUp(ic, KeyEvent.KEYCODE_META_LEFT)

            // ── Edit actions ──────────────────────────────────────────────────
            "cut"        -> ic?.performContextMenuAction(android.R.id.cut)
            "copy"       -> ic?.performContextMenuAction(android.R.id.copy)
            "paste"      -> ic?.performContextMenuAction(android.R.id.paste)
            "select-all" -> ic?.performContextMenuAction(android.R.id.selectAll)
            "undo"       -> ic?.performContextMenuAction(android.R.id.undo)
            "redo"       -> ic?.performContextMenuAction(android.R.id.redo)
            "save"       -> sendCtrl(ic, KeyEvent.KEYCODE_S)
            "find"       -> sendCtrl(ic, KeyEvent.KEYCODE_F)
            "replace"    -> sendCtrl(ic, KeyEvent.KEYCODE_H)
            "format"     -> sendCtrlShift(ic, KeyEvent.KEYCODE_F)
            "comment"    -> sendCtrl(ic, KeyEvent.KEYCODE_SLASH)
            "duplicate"  -> { sendCtrl(ic, KeyEvent.KEYCODE_C); sendCtrl(ic, KeyEvent.KEYCODE_V) }

            // ── Navigation ────────────────────────────────────────────────────
            "home"      -> sendDownUp(ic, KeyEvent.KEYCODE_MOVE_HOME)
            "end"       -> sendDownUp(ic, KeyEvent.KEYCODE_MOVE_END)
            "page-up"   -> sendDownUp(ic, KeyEvent.KEYCODE_PAGE_UP)
            "page-down" -> sendDownUp(ic, KeyEvent.KEYCODE_PAGE_DOWN)
            "insert"    -> sendDownUp(ic, KeyEvent.KEYCODE_INSERT)

            // ── F-keys ────────────────────────────────────────────────────────
            "f1"  -> sendDownUp(ic, KeyEvent.KEYCODE_F1)
            "f2"  -> sendDownUp(ic, KeyEvent.KEYCODE_F2)
            "f3"  -> sendDownUp(ic, KeyEvent.KEYCODE_F3)
            "f4"  -> sendDownUp(ic, KeyEvent.KEYCODE_F4)
            "f5"  -> sendDownUp(ic, KeyEvent.KEYCODE_F5)
            "f6"  -> sendDownUp(ic, KeyEvent.KEYCODE_F6)
            "f7"  -> sendDownUp(ic, KeyEvent.KEYCODE_F7)
            "f8"  -> sendDownUp(ic, KeyEvent.KEYCODE_F8)
            "f9"  -> sendDownUp(ic, KeyEvent.KEYCODE_F9)
            "f10" -> sendDownUp(ic, KeyEvent.KEYCODE_F10)
            "f11" -> sendDownUp(ic, KeyEvent.KEYCODE_F11)
            "f12" -> sendDownUp(ic, KeyEvent.KEYCODE_F12)

            // ── Media / system ────────────────────────────────────────────────
            "volume-mute"     -> sendDownUp(ic, KeyEvent.KEYCODE_VOLUME_MUTE)
            "volume-down"     -> sendDownUp(ic, KeyEvent.KEYCODE_VOLUME_DOWN)
            "volume-up"       -> sendDownUp(ic, KeyEvent.KEYCODE_VOLUME_UP)
            "media-play"      -> sendDownUp(ic, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "media-next"      -> sendDownUp(ic, KeyEvent.KEYCODE_MEDIA_NEXT)
            "media-previous"  -> sendDownUp(ic, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "brightness-down" -> sendDownUp(ic, KeyEvent.KEYCODE_BRIGHTNESS_DOWN)
            "brightness-up"   -> sendDownUp(ic, KeyEvent.KEYCODE_BRIGHTNESS_UP)
            "bt", "wifi"      -> { /* system-level — no IME key event available */ }

            // ── Character keys ────────────────────────────────────────────────
            else -> {
                val text = kbState.resolveLabel(key) ?: key.label
                if (text.isNotEmpty()) {
                    val metaState = kbState.computeMetaState(MODIFIER_META_FLAGS)
                    if (text.length == 1 && metaState != 0) {
                        val keyCode = charToKeyCode(text[0])
                        if (keyCode != null) {
                            flushComposing(ic)
                            ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, metaState))
                            ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, metaState))
                            kbState.onCharCommitted()
                            keyboardView.notifyStateChanged(kbState)
                            return
                        }
                    }
                    if (supportsComposing && text == ";") {
                        keystrokesSinceCommit++
                        val word = composing.append(";")
                        ic?.setComposingText(word, 1)
                        val suggestions = SnippetStore.matching("")
                        suggestionBar.update(word, suggestions)
                    } else if (supportsComposing && text.length == 1 && !isPunctuation(text[0])) {
                        keystrokesSinceCommit++
                        if (composing.isEmpty) {
                            composingStartedShift = kbState.shift
                        }
                        val word = composing.append(text)
                        ic?.setComposingText(word, 1)
                        val suggestions = if (word.startsWith(";")) {
                            SnippetStore.matching(word.drop(1))
                        } else {
                            suggestionStrategy.suggest(word, 5, context = prevCommittedWord)
                        }
                        suggestionBar.update(word, suggestions)
                    } else {
                        flushComposing(ic)
                        ic?.commitText(text, 1)
                    }
                    // Fires for EVERY character key, including ones that only
                    // went into the composing buffer (";" and letters) — this
                    // is what clears a one-shot Shift latch after exactly one
                    // letter, independent of whether that letter was actually
                    // committed to the input field yet.
                    kbState.onCharCommitted(text)
                    keyboardView.notifyStateChanged(kbState)
                }
            }
        }
    }

    // The single chokepoint for committing a real, standalone character to
    // the input field from an action-key branch (space, and anything future)
    // that sits OUTSIDE the character-key `else ->` above and so doesn't get
    // its unconditional kbState.onCharCommitted call for free. "space" used
    // to commit directly via ic.commitText and skip this entirely, which
    // silently broke Sentence Case's arm-on-space logic since kbState never
    // even saw the space.
    private fun commitCharAndNotify(ic: InputConnection?, text: String) {
        ic?.commitText(text, 1)
        kbState.onCharCommitted(text)
        keyboardView.notifyStateChanged(kbState)
    }

    // ── Navigation bar height ─────────────────────────────────────────────────

    private fun getNavBarHeight(): Int {
        val resId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        if (resId > 0) {
            return resources.getDimensionPixelSize(resId)
        }
        return (48f * resources.displayMetrics.density + 0.5f).toInt()
    }

    // ── Hold-tap handlers ──────────────────────────────────────────────────────

    private fun handleHold(key: KeyDef) {
        val action = key.holdAction ?: return
        android.util.Log.e("CKB_HOLD", "handleHold: action=$action layerHeld=${kbState.layerHeld} effectiveLayer=${kbState.effectiveLayer}")
        if (action in STATE_HOLD_ACTIONS) {
            kbState.applyHold(action)
            kbState.heldKeyLabel = key.label
            android.util.Log.e("CKB_HOLD", "after applyHold: layerHeld=${kbState.layerHeld} effectiveLayer=${kbState.effectiveLayer}")
            keyboardView.notifyStateChanged(kbState)
        } else if (action.isNotEmpty()) {
            handleKey(KeyDef("", action = action))
        }
    }

    private fun handleRelease(key: KeyDef) {
        val action = key.holdAction ?: return
        android.util.Log.e("CKB_HOLD", "handleRelease: action=$action layerHeld=${kbState.layerHeld} effectiveLayer=${kbState.effectiveLayer}")
        if (action in STATE_HOLD_ACTIONS) {
            kbState.releaseHold(action)
            kbState.heldKeyLabel = null
            android.util.Log.e("CKB_HOLD", "after releaseHold: layerHeld=${kbState.layerHeld} effectiveLayer=${kbState.effectiveLayer}")
            keyboardView.notifyStateChanged(kbState)
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sendDownUp(ic: android.view.inputmethod.InputConnection?, keyCode: Int) {
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        ic?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP,   keyCode))
    }

    private fun sendCtrl(ic: android.view.inputmethod.InputConnection?, keyCode: Int) {
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, KeyEvent.META_CTRL_ON))
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, KeyEvent.META_CTRL_ON))
    }

    private fun sendCtrlShift(ic: android.view.inputmethod.InputConnection?, keyCode: Int) {
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_SHIFT_ON
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, keyCode, 0, meta))
        ic?.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_UP,   keyCode, 0, meta))
    }

    private fun recomposeWordAtCursor(ic: InputConnection?) {
        ic ?: return
        val before = ic.getTextBeforeCursor(RECOMPOSE_SCAN_CHARS, 0)?.toString() ?: return
        val fragment = before.takeLastWhile { it.isLetterOrDigit() || it == '\'' }
        android.util.Log.d("CKB_COMPOSE", "recomposeWordAtCursor: before='$before' fragment='$fragment'")
        if (fragment.isEmpty()) return
        val req = android.view.inputmethod.ExtractedTextRequest().apply { token = 0 }
        val absCursor = ic.getExtractedText(req, 0)?.selectionStart ?: return
        expectSelectionUpdateBy = android.os.SystemClock.uptimeMillis() + IME_SELECTION_GUARD_MS
        ic.beginBatchEdit()
        ic.finishComposingText()
        android.util.Log.d("CKB_COMPOSE", "recomposeWordAtCursor: finishComposingText done, about to setComposingRegion cursor=$absCursor fragment.len=${fragment.length}")
        ic.setComposingRegion(absCursor - fragment.length, absCursor)
        composing.setText(fragment)
        android.util.Log.d("CKB_COMPOSE", "recomposeWordAtCursor: composing.setText('$fragment') done, endBatchEdit next")
        ic.endBatchEdit()
        val suggestions = suggestionStrategy.suggest(fragment, 5, context = prevCommittedWord)
        suggestionBar.update(fragment, suggestions)
    }

    // ADR-008: keep the cascade's rolling context window in sync with
    // committed words. Sized off ngram.maxContextNeeded so a later-added
    // pentagram widens it automatically (Open/Closed).
    private fun pushRecentWord(word: String) {
        recentContext.addLast(word)
        while (recentContext.size > ngram.maxContextNeeded) recentContext.removeFirst()
    }

    private fun flushComposing(ic: InputConnection?) {
        val typed = composing.flush()
        if (typed.isNotEmpty()) {
            // A ";shortcode" that exactly matches a saved snippet expands here
            // too, not just via tapping the suggestion bar — otherwise Enter/
            // Space/punctuation right after typing it just commits the literal
            // ";shortcode" text instead of the expansion.
            val shortcode = if (typed.startsWith(";")) typed.drop(1) else null
            val expansion = shortcode?.let { SnippetStore.get(it) }
            val word = if (!expansion.isNullOrEmpty()) expansion else typed

            ic?.commitText(word, 1)
            wordLearner.learnFromFlush(word)
            kbState.onCharCommitted(word)
            keyboardView.notifyStateChanged(kbState)
            Metrics.histogram("keyboard.word.keystrokes", keystrokesSinceCommit.toDouble(),
                "method" to "typed")
            Metrics.incr("keyboard.word.committed", "method" to "typed")
if (prevCommittedWord.isNotEmpty()) packBigram.recordTransition(prevCommittedWord, word)
            pushRecentWord(word)
            prevCommittedWord = word
        }
        keystrokesSinceCommit = 0
        suggestionBar.clear()
    }

    private fun handleSuggestionTap(rawWord: String) {
        val ic = currentInputConnection ?: return
        // Suggestions are committed directly, bypassing resolveLabel — apply
        // Shift here too, scaled to a whole word: LOCKED (double-tap Shift,
        // i.e. caps-lock) uppercases every letter, LATCHED (single tap, or
        // Sentence Case arming it) uppercases only the first.
        //
        // Shift's latch clears off kbState the moment the first letter of a
        // word is typed (so the displayed case is right as you type), so if
        // the user already typed part of the word before tapping a
        // suggestion, the live state has already been consumed — use what
        // was captured when this word started instead. If nothing was typed
        // yet (a bigram/next-word suggestion tapped with an empty composing
        // buffer), the live state is still accurate since nothing cleared it.
        val shiftState = if (composing.isEmpty) kbState.shift else composingStartedShift
        val word = when (shiftState) {
            LatchState.LOCKED  -> rawWord.uppercase()
            LatchState.LATCHED -> rawWord.replaceFirstChar { it.uppercase() }
            LatchState.NONE    -> rawWord
        }
        ic.commitText("$word ", 1)
        composing.clear()
        wordLearner.learnFromTap(word)
        kbState.onCharCommitted(word)
        keyboardView.notifyStateChanged(kbState)
        Metrics.histogram("keyboard.word.keystrokes", keystrokesSinceCommit.toDouble(),
            "method" to "suggestion")
        Metrics.incr("keyboard.word.committed", "method" to "suggestion")
        if (prevCommittedWord.isNotEmpty()) packBigram.recordTransition(prevCommittedWord, word)
        pushRecentWord(word)
        prevCommittedWord = word
        keystrokesSinceCommit = 0
        // Show next-word suggestions immediately after tap
        val next = ngram.nextWords(recentContext.toList(), n = 5)
        if (next.isNotEmpty()) suggestionBar.update("", next) else suggestionBar.clear()
    }

private val PUNCTUATION = setOf(
        '.', ',', '!', '?', ':', '\'', '"', '(', ')', '[', ']', '{', '}',
        '/', '\\', '-', '_', '=', '+', '*', '&', '^', '%', '$', '#', '@', '~', '`', '|'
    )

    private fun isPunctuation(c: Char) = c in PUNCTUATION

    private fun charToKeyCode(c: Char): Int? = when (c.uppercaseChar()) {
        'A' -> KeyEvent.KEYCODE_A
        'B' -> KeyEvent.KEYCODE_B
        'C' -> KeyEvent.KEYCODE_C
        'D' -> KeyEvent.KEYCODE_D
        'E' -> KeyEvent.KEYCODE_E
        'F' -> KeyEvent.KEYCODE_F
        'G' -> KeyEvent.KEYCODE_G
        'H' -> KeyEvent.KEYCODE_H
        'I' -> KeyEvent.KEYCODE_I
        'J' -> KeyEvent.KEYCODE_J
        'K' -> KeyEvent.KEYCODE_K
        'L' -> KeyEvent.KEYCODE_L
        'M' -> KeyEvent.KEYCODE_M
        'N' -> KeyEvent.KEYCODE_N
        'O' -> KeyEvent.KEYCODE_O
        'P' -> KeyEvent.KEYCODE_P
        'Q' -> KeyEvent.KEYCODE_Q
        'R' -> KeyEvent.KEYCODE_R
        'S' -> KeyEvent.KEYCODE_S
        'T' -> KeyEvent.KEYCODE_T
        'U' -> KeyEvent.KEYCODE_U
        'V' -> KeyEvent.KEYCODE_V
        'W' -> KeyEvent.KEYCODE_W
        'X' -> KeyEvent.KEYCODE_X
        'Y' -> KeyEvent.KEYCODE_Y
        'Z' -> KeyEvent.KEYCODE_Z
        '0' -> KeyEvent.KEYCODE_0
        '1' -> KeyEvent.KEYCODE_1
        '2' -> KeyEvent.KEYCODE_2
        '3' -> KeyEvent.KEYCODE_3
        '4' -> KeyEvent.KEYCODE_4
        '5' -> KeyEvent.KEYCODE_5
        '6' -> KeyEvent.KEYCODE_6
        '7' -> KeyEvent.KEYCODE_7
        '8' -> KeyEvent.KEYCODE_8
        '9' -> KeyEvent.KEYCODE_9
        '\n' -> KeyEvent.KEYCODE_ENTER
        ' ' -> KeyEvent.KEYCODE_SPACE
        '\t' -> KeyEvent.KEYCODE_TAB
        else -> null
    }
}
