package com.ioszhuyin.keyboard

import android.content.res.Configuration
import android.content.Intent
import android.content.ClipboardManager
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import java.io.File

class IOSZhuyinIME : InputMethodService() {

    private var keyboardView: ZhuyinKeyboardView? = null
    private var bopomofoTypeface: Typeface? = null
    private var vibrator: android.os.Vibrator? = null
    private lateinit var userDictionaryStore: UserDictionaryStore

    private val composingText = StringBuilder()
    private var allCandidates: List<String> = emptyList()
    private var candidateChoices: List<CandidateChoice> = emptyList()
    private var selectedCandidateIndex: Int = -1
    private var candidatePage: Int = 0
    private var candidatesExpanded: Boolean = false
    private var showingNextWordSuggestions: Boolean = false
    private var lastCommittedWord = ""
    private var nextWordCandidates: Set<String> = emptySet()
    private var backspaceDismissedSuggestions = false
    private var modeBeforeEmoji = ZhuyinKeyboardView.Mode.ZHUYIN
    private var showFinalPage: Boolean = false

    private var personalizationAllowed = false
    private var clipboardCaptureAllowed = true
    private lateinit var clipboardHistory: ClipboardHistory
    private var inputContainer: ClipboardInputView? = null
    private var clipboardBackHandled = false
    private var editorKeyboardMode = EditorKeyboardMode.ZHUYIN
    private var editorReturnKeyLabel = "換行"
    private var performingEditorEdit = false
    private var editorCompositionPending = false
    private var editorSelectionStart = -1
    private var editorSelectionEnd = -1
    private var editorComposingStart = -1
    private var editorComposingEnd = -1
    private val expectedCompositionUpdates = ExpectedCompositionUpdateTracker()
    private val sortedCandidateCache = object : LinkedHashMap<String, List<String>>(
        CANDIDATE_CACHE_CAPACITY,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<String>>?
        ): Boolean = size > CANDIDATE_CACHE_CAPACITY
    }
    private val userCandidateCache = object : LinkedHashMap<String, List<String>>(
        CANDIDATE_CACHE_CAPACITY,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<String>>?
        ): Boolean = size > CANDIDATE_CACHE_CAPACITY
    }

    private enum class CandidateCommitResult {
        SUCCESS,
        NO_CANDIDATE,
        EDITOR_REJECTED,
        PARTIAL
    }

    private fun wordSelected(reading: String, word: String) {
        if (!personalizationAllowed) { lastCommittedWord = ""; return }
        runCatching {
            if (CandidateLearningSettings.isEnabled(this)) {
                userDictionaryStore.recordSelection(reading, word)
                learnContinuation(word)
                CandidateLearningSettings.notifyRecordsChanged(this)
                sortedCandidateCache.clear()
            }
        }.onFailure {
            Log.w(TAG, "Unable to record candidate learning", it)
        }
        lastCommittedWord = word.takeIf(NextWordSuggestions::eligible).orEmpty()
    }

    private fun learnContinuation(word: String) {
        val previous = lastCommittedWord
        if (!NextWordSuggestions.eligible(previous) || !NextWordSuggestions.eligible(word)) return
        if (currentInputConnection?.getTextBeforeCursor(previous.length + word.length, 0)?.toString() == previous + word) {
            userDictionaryStore.recordSelection(NextWordSuggestions.learningKey(previous), word)
        }
    }

    private fun getSortedCandidates(raw: String): List<String> {
        sortedCandidateCache[raw]?.let { return it }
        val dictionaryCandidates = candidatesForKey(raw)
        if (!personalizationAllowed) {
            sortedCandidateCache[raw] = dictionaryCandidates
            return dictionaryCandidates
        }

        val userCandidates = userCandidatesForKey(raw)
        val lookupReadings = lookupVariants(raw)
        val learnedCounts = runCatching {
            userDictionaryStore.getLearningCounts(lookupReadings)
        }.onFailure {
            Log.w(TAG, "Unable to load candidate learning", it)
        }.getOrDefault(emptyMap())
        val learnedCandidates = runCatching {
            userDictionaryStore.getLearnedCandidates(lookupReadings)
        }.onFailure {
            Log.w(TAG, "Unable to load learned candidates", it)
        }.getOrDefault(emptyList())
        if (
            dictionaryCandidates.isEmpty() &&
            userCandidates.isEmpty() &&
            learnedCandidates.isEmpty()
        ) {
            sortedCandidateCache[raw] = emptyList()
            return emptyList()
        }
        return CandidateRanking.order(
            manualCandidates = userCandidates,
            dictionaryCandidates = dictionaryCandidates,
            learnedCounts = learnedCounts,
            learnedCandidates = learnedCandidates
        ).also {
            sortedCandidateCache[raw] = it
        }
    }

    private fun userCandidatesForKey(raw: String): List<String> {
        if (!personalizationAllowed || !::userDictionaryStore.isInitialized) return emptyList()
        userCandidateCache[raw]?.let { return it }
        return userDictionaryStore.getCandidates(lookupVariants(raw)).also {
            userCandidateCache[raw] = it
        }
    }

    private fun candidatesForKey(raw: String): List<String> {
        val merged = mutableListOf<String>()
        for (key in lookupVariants(raw)) {
            val candidates = ZhuyinDictionary.getCandidates(key)
            candidates?.forEach { candidate ->
                if (candidate !in merged) merged.add(candidate)
            }
        }
        return merged
    }

    private fun prefixCandidatesForKey(raw: String): List<String> =
        ZhuyinDictionary.getPrefixCandidates(raw, PREFIX_CANDIDATE_LIMIT)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val backspaceRepeater = BackspaceRepeater(
        deleteOnce = { handleBackspace() },
        schedule = { task, delay ->
            mainHandler.postDelayed(task, delay)
        },
        cancel = { task ->
            mainHandler.removeCallbacks(task)
        }
    )

    override fun onCreate() {
        super.onCreate()
        clipboardHistory = ClipboardHistory(this) { clipboardCaptureAllowed }
        NextWordTable.preload(this)
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
        userDictionaryStore = UserDictionaryStore(this)
        migrateLegacyLearning()
        ZhuyinDictionary.initialize(this)
        bopomofoTypeface = try {
            val outFile = File(cacheDir, "bopomofo.ttf")
            if (!outFile.exists() || outFile.length() < 1000) {
                assets.open("bopomofo.ttf").use { input ->
                    outFile.outputStream().use { output -> input.copyTo(output) }
                }
            }
            Typeface.createFromFile(outFile)
        } catch (e: Exception) {
            null
        }
    }

    override fun onDestroy() {
        inputContainer?.closeClipboard()
        inputContainer = null
        if (::clipboardHistory.isInitialized) clipboardHistory.close()
        keyboardView?.cancelCursorGesture()
        stopBackspaceRepeat()
        if (::userDictionaryStore.isInitialized) userDictionaryStore.close()
        keyboardView = null
        super.onDestroy()
    }

    override fun onCreateCandidatesView(): View = LinearLayout(this)
    override fun onEvaluateFullscreenMode(): Boolean = false

    override fun onComputeInsets(outInsets: Insets) {
        val view = keyboardView
        if (view != null && view.height > 0) {
            val top = view.keyboardContentTop.toInt().coerceAtLeast(0)
            outInsets.contentTopInsets = top
            outInsets.visibleTopInsets = top
            outInsets.touchableInsets = Insets.TOUCHABLE_INSETS_CONTENT
            outInsets.touchableRegion.setEmpty()
        } else {
            super.onComputeInsets(outInsets)
        }
    }

    override fun onCreateInputView(): View {
        val view = ZhuyinKeyboardView(this)
        view.customTypeface = KeyboardFont.load(this)
        view.bopomofoTypeface = bopomofoTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)

        view.onKeyPress = { key -> onZhuyinKeyPressed(key) }
        view.onBackspace = { handleBackspaceDown() }
        view.onBackspaceRelease = { handleBackspaceUp() }
        view.onSpace = { handleSpace() }
        view.onWidthToggle = {
            if (editorKeyboardMode == EditorKeyboardMode.ZHUYIN && drainComposing(recordLearning = true)) {
                val target = when (view.getMode()) {
                    ZhuyinKeyboardView.Mode.ZHUYIN,
                    ZhuyinKeyboardView.Mode.EMOJI,
                    ZhuyinKeyboardView.Mode.NUMBER -> ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER
                    ZhuyinKeyboardView.Mode.SYMBOL -> ZhuyinKeyboardView.Mode.HALF_WIDTH_SYMBOL
                    ZhuyinKeyboardView.Mode.HALF_WIDTH_SYMBOL -> ZhuyinKeyboardView.Mode.SYMBOL
                    else -> ZhuyinKeyboardView.Mode.NUMBER
                }
                view.setMode(target)
                showNextWordSuggestions()
                vibrateLight()
            }
        }
        view.onEmoji = {
            if (editorKeyboardMode == EditorKeyboardMode.ZHUYIN && drainComposing(recordLearning = true)) {
                if (view.getMode() == ZhuyinKeyboardView.Mode.EMOJI) view.setMode(modeBeforeEmoji)
                else { modeBeforeEmoji = view.getMode(); view.setMode(ZhuyinKeyboardView.Mode.EMOJI) }
                showNextWordSuggestions()
            }
        }
        view.onPaste = { openClipboard() }
        view.onSettings = {
            startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
        view.onCursorGestureStart = {
            (currentInputConnection != null).also { if (it) vibrateLight() }
        }
        view.onCursorMove = { steps ->
            lastCommittedWord = ""
            if (drainComposing(recordLearning = false)) {
                currentInputConnection?.let { connection ->
                    safeEditorOperation("spacebar cursor movement") {
                        CursorNavigation.move(connection, steps)
                    }
                }
            }
        }
        view.onEditAction = { label -> handleEditAction(label) }
        view.onReturn = { handleReturn() }
        view.onCandidateConfirm = { handleCandidateConfirm() }
        view.onCandidatePress = { candidateIndex -> handleCandidatePress(candidateIndex) }
        view.onCandidateExpansionToggle = { toggleCandidateExpansion() }
        view.onCandidatePageSwipe = { delta -> moveCandidatePage(delta) }
        view.onEnglishMode = { handleEnglishMode() }
        view.onNumberMode = { handleNumberMode() }
        view.onSymbolMode = { handleSymbolMode() }
        view.onSymbolChar = { ch -> handleSymbolChar(ch) }
        view.onToggleToZhuyin = { handleToggleToZhuyin() }
        view.onToneSelected = { tone -> onToneSelected(tone) }

        keyboardView = view
        view.setReturnKeyLabel(editorReturnKeyLabel)
        applyEditorKeyboardMode()
        syncKeyboardView()
        applySystemTheme()
        return ClipboardInputView(this, view, clipboardHistory, ::pasteHistoryText, ::pasteClipboard)
            .also { inputContainer = it }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemTheme()
    }

    private fun onZhuyinKeyPressed(key: String) {
        if (key == "⇧") {
            showFinalPage = !showFinalPage
            syncKeyboardView()
            vibrateLight()
            return
        }

        if (key.isEmpty()) return
        if (!synchronizePendingComposition()) return
        composingText.append(key)
        showFinalPage = true
        refreshCandidates(resetSelection = true)
        vibrateLight()
    }

    private fun onToneSelected(tone: String) {
        if (composingText.isEmpty()) return
        if (!synchronizePendingComposition()) return
        applyToneToLastSegment(tone)
        showFinalPage = false
        refreshCandidates(resetSelection = true)
        vibrateLight()
    }

    private fun applyToneToLastSegment(tone: String) {
        val segments = splitSegments(composingText.toString())
        val last = segments.lastOrNull() ?: return

        if (last.hasTone && last.end > last.start) {
            composingText.deleteCharAt(last.end - 1)
        }
        composingText.insert(if (last.hasTone) last.end - 1 else last.end, tone)
    }

    private fun refreshCandidates(
        resetSelection: Boolean,
        clearEmptyEditorComposition: Boolean = false,
        syncEditorComposition: Boolean = true
    ): Boolean {
        if (resetSelection) candidatesExpanded = false
        val raw = composingText.toString()
        if (raw.isEmpty()) {
            nextWordCandidates = emptySet()
            allCandidates = emptyList()
            candidateChoices = emptyList()
            selectedCandidateIndex = -1
            candidatePage = 0
            candidatesExpanded = false
            showingNextWordSuggestions = false
            val editorAccepted = !syncEditorComposition ||
                syncEditorComposing(clearEmptyComposition = clearEmptyEditorComposition)
            syncKeyboardView()
            return editorAccepted
        }

        showingNextWordSuggestions = false

        val match = ZhuyinComposition.resolveLeadingCandidates(
            raw = raw,
            segments = splitSegments(raw),
            preferredPrefixCandidatesForReading = ::userCandidatesForKey,
            prefixCandidatesForReading = ::prefixCandidatesForKey,
            candidatesForReading = ::getSortedCandidates
        )
        candidateChoices = match?.choices.orEmpty()
        allCandidates = candidateChoices.map(CandidateChoice::text)

        selectedCandidateIndex = when {
            allCandidates.isEmpty() -> -1
            resetSelection || selectedCandidateIndex !in allCandidates.indices -> 0
            else -> selectedCandidateIndex
        }
        val pageSize = ImeBehavior.candidatePageSize(allCandidates)
        candidatePage = if (selectedCandidateIndex >= 0) selectedCandidateIndex / pageSize else 0
        val editorAccepted = !syncEditorComposition || syncEditorComposing()
        syncKeyboardView()
        return editorAccepted
    }

    private fun syncEditorComposing(clearEmptyComposition: Boolean = false): Boolean {
        val ic = currentInputConnection
        if (ic == null) {
            if (composingText.isNotEmpty()) {
                editorCompositionPending = true
            }
            return EditorSelectionBehavior.isSynchronizedWithoutConnection(
                composingLength = composingText.length,
                compositionPending = editorCompositionPending
            )
        }

        val desiredText = composingText.toString()
        val reattachExistingComposition = if (
            desiredText.isNotEmpty() && editorCompositionPending
        ) {
            compositionImmediatelyBeforeCursor(ic, desiredText) ?: run {
                Log.w(TAG, "Cannot safely locate pending editor composition")
                return false
            }
        } else {
            false
        }
        val expectedCursor = if (desiredText.isNotEmpty()) {
            if (reattachExistingComposition) {
                editorSelectionEnd
            } else {
                EditorSelectionBehavior.composingCursorAfterSet(
                    selectionStart = editorSelectionStart,
                    selectionEnd = editorSelectionEnd,
                    composingStart = editorComposingStart,
                    composingEnd = editorComposingEnd,
                    composingLength = desiredText.length
                )
            }
        } else {
            null
        }
        val geometryUnchanged = !reattachExistingComposition &&
            desiredText.isNotEmpty() &&
            expectedCursor != null &&
            EditorSelectionBehavior.matchesCurrentComposition(
                selectionStart = editorSelectionStart,
                selectionEnd = editorSelectionEnd,
                candidatesStart = editorComposingStart,
                candidatesEnd = editorComposingEnd,
                composingLength = desiredText.length
            )
        val expectedToken = if (desiredText.isNotEmpty()) {
            when {
                geometryUnchanged -> expectedCompositionUpdates.advanceWithoutExpectation(
                    cursor = requireNotNull(expectedCursor),
                    length = desiredText.length
                )
                expectedCursor != null -> expectedCompositionUpdates.expect(
                    expectedCursor,
                    desiredText.length
                )
                else -> expectedCompositionUpdates.advance()
            }
        } else {
            expectedCompositionUpdates.advance()
        }
        performingEditorEdit = true
        val accepted = try {
            if (desiredText.isEmpty()) {
                val cleared = !clearEmptyComposition || ic.setComposingText("", 1)
                val finished = ic.finishComposingText()
                cleared && finished
            } else if (reattachExistingComposition) {
                val cursor = editorSelectionEnd
                ic.setComposingRegion(cursor - desiredText.length, cursor)
            } else {
                ic.setComposingText(desiredText, 1)
            }
        } catch (error: RuntimeException) {
            Log.w(TAG, "Editor composing-text synchronization failed", error)
            false
        } finally {
            performingEditorEdit = false
        }

        if (accepted) {
            if (geometryUnchanged) {
                editorCompositionPending = false
            } else if (
                expectedCursor != null &&
                expectedCompositionUpdates.isPending(expectedToken)
            ) {
                editorCompositionPending = false
                editorComposingStart = expectedCursor - desiredText.length
                editorComposingEnd = expectedCursor
                editorSelectionStart = expectedCursor
                editorSelectionEnd = expectedCursor
            } else if (desiredText.isNotEmpty() && expectedCursor == null) {
                editorCompositionPending = true
                editorComposingStart = -1
                editorComposingEnd = -1
            } else if (desiredText.isEmpty()) {
                editorCompositionPending = false
                clearExpectedCompositionUpdates()
                editorComposingStart = -1
                editorComposingEnd = -1
            }
        } else {
            expectedCompositionUpdates.discard(expectedToken)
            editorCompositionPending = true
            Log.w(
                TAG,
                if (desiredText.isEmpty()) {
                    "Editor rejected composition cleanup"
                } else {
                    "Editor rejected composing-text synchronization"
                }
            )
        }
        return accepted
    }

    private fun compositionImmediatelyBeforeCursor(
        ic: android.view.inputmethod.InputConnection,
        composition: String
    ): Boolean? {
        if (
            composition.isEmpty() ||
            editorSelectionStart < 0 ||
            editorSelectionStart != editorSelectionEnd ||
            editorSelectionEnd < composition.length
        ) {
            return null
        }
        return try {
            val beforeCursor = ic.getTextBeforeCursor(composition.length, 0)
                ?: return null
            beforeCursor.toString() == composition
        } catch (error: RuntimeException) {
            Log.w(TAG, "Unable to inspect text before pending composition", error)
            null
        }
    }

    private fun clearExpectedCompositionUpdates() = expectedCompositionUpdates.clear()

    private fun syncKeyboardView() {
        val view = keyboardView ?: return
        val pageSize = ImeBehavior.candidatePageSize(allCandidates)
        val start = candidatePage * pageSize
        view.updateCandidateState(
            newCandidates = allCandidates,
            pageStart = start,
            selectedIndex = selectedCandidateIndex,
            hasMore = allCandidates.size > pageSize,
            expanded = candidatesExpanded,
            composing = composingText.isNotEmpty()
        )
        view.setFinalPage(showFinalPage)
        view.refresh()
    }

    private fun toggleCandidateExpansion() {
        if (allCandidates.size <= ImeBehavior.candidatePageSize(allCandidates)) return
        candidatesExpanded = !candidatesExpanded
        syncKeyboardView()
        vibrateLight()
    }

    private fun handleCandidatePress(candidateIndex: Int) {
        if (showingNextWordSuggestions && composingText.isEmpty()) {
            allCandidates.getOrNull(candidateIndex)?.let(::commitNextWordSuggestion)
        } else {
            commitSelectedCandidate(candidateIndexOverride = candidateIndex)
        }
    }

    private fun moveCandidatePage(delta: Int) {
        if (allCandidates.isEmpty()) return
        val pageSize = ImeBehavior.candidatePageSize(allCandidates)
        val pageCount = (allCandidates.size + pageSize - 1) / pageSize
        val newPage = CandidatePanelBehavior.pageAfterSwipe(candidatePage, delta, pageCount)
        if (newPage == candidatePage) return
        candidatePage = newPage
        selectedCandidateIndex = candidatePage * pageSize
        syncKeyboardView()
        vibrateLight()
    }

    private fun cycleCandidate() {
        if (allCandidates.isEmpty()) return
        selectedCandidateIndex = (selectedCandidateIndex + 1).floorMod(allCandidates.size)
        candidatePage = selectedCandidateIndex / ImeBehavior.candidatePageSize(allCandidates)
        syncKeyboardView()
        vibrateLight()
    }

    private fun commitSelectedCandidate(
        candidateIndexOverride: Int? = null,
        provideFeedback: Boolean = true,
        recordLearning: Boolean = true
    ): CandidateCommitResult {
        if (editorCompositionPending && !syncEditorComposing()) {
            return CandidateCommitResult.EDITOR_REJECTED
        }
        val ic = currentInputConnection ?: return CandidateCommitResult.EDITOR_REJECTED
        if (candidateChoices.isEmpty()) return CandidateCommitResult.NO_CANDIDATE

        val choice = candidateChoices.getOrNull(
            candidateIndexOverride ?: selectedCandidateIndex
        )
            ?: return CandidateCommitResult.NO_CANDIDATE
        val start = choice.start.coerceIn(0, composingText.length)
        val end = choice.end.coerceIn(start, composingText.length)
        val raw = composingText.toString()
        val edit = CompositionEditing.candidateSelection(
            raw = raw,
            start = start,
            end = end,
            candidate = choice.text
        )
        val tracksCurrentComposition = editorComposingStart >= 0 &&
            editorComposingEnd - editorComposingStart == raw.length &&
            editorSelectionStart == editorComposingEnd &&
            editorSelectionEnd == editorComposingEnd
        val candidateCursor = if (tracksCurrentComposition) {
            EditorSelectionBehavior.cursorAfterReplacingComposition(
                selectionStart = editorSelectionStart,
                selectionEnd = editorSelectionEnd,
                composingLength = raw.length,
                replacementLength = edit.committedText.length
            )
        } else {
            null
        }
        val suffixCursor = candidateCursor?.plus(edit.remainingText.length)
        val suffixExpectation = if (edit.remainingText.isNotEmpty()) {
            suffixCursor?.let { cursor ->
                expectedCompositionUpdates.expect(cursor, edit.remainingText.length)
            } ?: expectedCompositionUpdates.advance()
        } else {
            null
        }

        performingEditorEdit = true
        val transaction = try {
            CandidateEditorTransaction.execute(
                hasSuffix = edit.remainingText.isNotEmpty(),
                beginBatchEdit = ic::beginBatchEdit,
                commitCandidate = { ic.commitText(edit.committedText, 1) },
                setComposingSuffix = { ic.setComposingText(edit.remainingText, 1) },
                commitPlainSuffix = { ic.commitText(edit.remainingText, 1) },
                endBatchEdit = ic::endBatchEdit
            )
        } finally {
            performingEditorEdit = false
        }

        transaction.operationFailure?.let { error ->
            Log.w(TAG, "Editor candidate transaction failed", error)
        }
        if (!transaction.batchStarted) {
            Log.w(TAG, "Editor rejected candidate batch edit")
        }
        if (transaction.batchCompletionAccepted == false) {
            Log.w(TAG, "Editor rejected candidate batch completion")
        }
        transaction.batchCompletionFailure?.let { error ->
            Log.w(TAG, "Editor candidate batch completion failed", error)
        }
        if (!transaction.candidateCommitted) {
            suffixExpectation?.let(expectedCompositionUpdates::cancel)
            Log.w(TAG, "Editor rejected candidate commit")
            return CandidateCommitResult.EDITOR_REJECTED
        }
        val suffixState = CompositionEditing.suffixState(
            hasSuffix = edit.remainingText.isNotEmpty(),
            composingAccepted = transaction.suffixComposed,
            plainFallbackAccepted = transaction.plainSuffixCommitted
        )
        return when (suffixState) {
            CompositionEditing.SuffixState.NONE,
            CompositionEditing.SuffixState.COMPOSING -> {
                composingText.clear()
                composingText.append(edit.remainingText)
                if (edit.remainingText.isEmpty()) {
                    editorCompositionPending = false
                    clearExpectedCompositionUpdates()
                    editorComposingStart = -1
                    editorComposingEnd = -1
                    candidateCursor?.let { cursor ->
                        editorSelectionStart = cursor
                        editorSelectionEnd = cursor
                    }
                } else if (
                    candidateCursor != null &&
                    suffixCursor != null &&
                    suffixExpectation != null &&
                    expectedCompositionUpdates.isPending(suffixExpectation)
                ) {
                    editorCompositionPending = false
                    editorComposingStart = candidateCursor
                    editorComposingEnd = suffixCursor
                    editorSelectionStart = suffixCursor
                    editorSelectionEnd = suffixCursor
                } else if (suffixCursor == null) {
                    editorCompositionPending = true
                    editorComposingStart = -1
                    editorComposingEnd = -1
                } else {
                    // A synchronous callback already supplied the current bounds.
                }
                if (recordLearning && !choice.isProvisional) {
                    wordSelected(edit.reading, edit.committedText)
                }
                recomputePageFromComposing()
                refreshCandidates(
                    resetSelection = true,
                    syncEditorComposition = false
                )
                if (composingText.isEmpty()) showNextWordSuggestions()
                if (provideFeedback) vibrateLight()
                CandidateCommitResult.SUCCESS
            }
            CompositionEditing.SuffixState.PLAIN -> {
                Log.w(TAG, "Remaining composition was preserved as plain text")
                candidateCursor?.plus(edit.remainingText.length)?.let { cursor ->
                    editorSelectionStart = cursor
                    editorSelectionEnd = cursor
                }
                if (recordLearning && !choice.isProvisional) {
                    wordSelected(edit.reading, edit.committedText)
                }
                resetToInitial()
                if (provideFeedback) vibrateLight()
                CandidateCommitResult.SUCCESS
            }
            CompositionEditing.SuffixState.PENDING -> {
                suffixExpectation?.let(expectedCompositionUpdates::discard)
                Log.w(TAG, "Editor rejected both composing and plain-text suffix preservation")
                composingText.clear()
                composingText.append(edit.remainingText)
                editorCompositionPending = true
                candidateCursor?.let { cursor ->
                    editorSelectionStart = cursor
                    editorSelectionEnd = cursor
                }
                editorComposingStart = -1
                editorComposingEnd = -1
                recomputePageFromComposing()
                refreshCandidates(
                    resetSelection = true,
                    syncEditorComposition = false
                )
                CandidateCommitResult.PARTIAL
            }
        }
    }

    private fun splitSegments(text: String): List<ZhuyinSegment> {
        return ZhuyinComposition.splitSegments(text, ::isKnownUntonedSyllable)
    }

    private fun isKnownUntonedSyllable(value: String): Boolean =
        value in STANDALONE_FINALS || ZhuyinDictionary.getCandidates(value) != null

    private fun recomputePageFromComposing() {
        val last = splitSegments(composingText.toString()).lastOrNull()
        showFinalPage = composingText.isNotEmpty() && last?.hasTone != true
    }

    private fun handleBackspaceDown() {
        if (backspaceDismissedSuggestions) return
        if (composingText.isEmpty() && showingNextWordSuggestions) {
            stopBackspaceRepeat()
            backspaceDismissedSuggestions = true
            lastCommittedWord = ""
            showNextWordSuggestions()
            vibrateLight()
            return
        }
        backspaceRepeater.press()
    }

    private fun handleBackspaceUp() {
        stopBackspaceRepeat()
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeater.release()
        backspaceDismissedSuggestions = false
    }

    private fun handleBackspace() {
        if (!synchronizePendingComposition()) return
        if (composingText.isNotEmpty()) {
            composingText.deleteCharAt(composingText.length - 1)
            recomputePageFromComposing()
            refreshCandidates(
                resetSelection = true,
                clearEmptyEditorComposition = composingText.isEmpty()
            )
        } else {
            val ic = currentInputConnection
            if (ic != null) {
                val collapsedSelection = EditorSelectionBehavior.collapsedAfterDeletingSelection(
                    editorSelectionStart,
                    editorSelectionEnd
                )
                val deleted = safeEditorOperation("backspace") {
                    val selectedText = ic.getSelectedText(0)
                    if (collapsedSelection != null || !selectedText.isNullOrEmpty()) {
                        ic.commitText("", 1)
                    } else {
                        val beforeCursor = ic.getTextBeforeCursor(MAX_BACKSPACE_CONTEXT, 0)
                        val codeUnits = UnicodeBackspace.codeUnitsToDelete(beforeCursor)
                        when {
                            codeUnits > 0 -> ic.deleteSurroundingText(codeUnits, 0)
                            else -> ic.deleteSurroundingTextInCodePoints(1, 0)
                        }
                    }
                }
                if (deleted && collapsedSelection != null) {
                    editorSelectionStart = collapsedSelection
                    editorSelectionEnd = collapsedSelection
                }
                if (!deleted) {
                    safeEditorOperation("backspace key event fallback") {
                        val downAccepted = ic.sendKeyEvent(
                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)
                        )
                        val upAccepted = ic.sendKeyEvent(
                            KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL)
                        )
                        downAccepted && upAccepted
                    }
                }
            }
            syncKeyboardView()
        }
        vibrateLight()
    }

    private fun handleSpace() {
        if (composingText.isNotEmpty()) {
            if (!synchronizePendingComposition()) return
            if (showFinalPage) {
                applyToneToLastSegment(FIRST_TONE)
                showFinalPage = false
                refreshCandidates(resetSelection = true)
                vibrateLight()
            } else {
                cycleCandidate()
            }
        } else {
            lastCommittedWord = ""
            if (safeEditorOperation("space commit") {
                    currentInputConnection?.commitText(" ", 1) == true
                }
            ) {
                vibrateLight()
            }
        }
    }

    private fun handleCandidateConfirm() {
        if (composingText.isEmpty()) return
        if (allCandidates.isNotEmpty()) {
            when (commitSelectedCandidate()) {
                CandidateCommitResult.SUCCESS,
                CandidateCommitResult.EDITOR_REJECTED,
                CandidateCommitResult.PARTIAL -> return
                CandidateCommitResult.NO_CANDIDATE -> Unit
            }
        }
        commitRawComposition(provideFeedback = true)
    }

    private fun handleReturn() {
        if (!drainComposing(recordLearning = true)) return
        lastCommittedWord = ""
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val actionPlan = ImeBehavior.editorActionPlan(
            hasCustomActionLabel = info?.actionLabel != null,
            imeOptions = info?.imeOptions ?: EditorInfo.IME_ACTION_NONE
        )
        val handled = safeEditorOperation("editor action") {
            when (actionPlan) {
                EditorActionPlan.CUSTOM_ACTION -> ic.performEditorAction(info?.actionId ?: 0)
                EditorActionPlan.DEFAULT_ACTION -> sendDefaultEditorAction(true)
                EditorActionPlan.INSERT_NEWLINE -> ic.commitText(
                    requireNotNull(actionPlan.committedText),
                    1
                )
            }
        }
        val accepted = handled || (
            actionPlan.allowsKeyEventFallback &&
                safeEditorOperation("return key event fallback") {
                    val downAccepted = ic.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                    )
                    val upAccepted = ic.sendKeyEvent(
                        KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER)
                    )
                    downAccepted && upAccepted
                }
            )
        if (accepted) vibrateLight()
    }

    private fun handleEnglishMode() {
        if (!drainComposing(recordLearning = true)) {
            android.widget.Toast.makeText(
                this, "輸入框暫時無法接收組字，已保留內容，請再試一次。",
                android.widget.Toast.LENGTH_SHORT
            ).show()
            return
        }
        keyboardView?.setMode(ZhuyinKeyboardView.Mode.ENGLISH)
        refreshNextWordSuggestionsForCurrentMode()
        vibrateLight()
    }

    private fun handleNumberMode() {
        if (!drainComposing(recordLearning = true)) return
        val view = keyboardView ?: return
        view.setMode(
            when (view.getMode()) {
                ZhuyinKeyboardView.Mode.ENGLISH,
                ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER,
                ZhuyinKeyboardView.Mode.HALF_WIDTH_SYMBOL ->
                    ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER
                else -> ZhuyinKeyboardView.Mode.NUMBER
            }
        )
        refreshNextWordSuggestionsForCurrentMode()
        vibrateLight()
    }

    private fun handleSymbolMode() {
        if (!drainComposing(recordLearning = true)) return
        val view = keyboardView ?: return
        view.setMode(
            if (view.getMode() == ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER) {
                ZhuyinKeyboardView.Mode.HALF_WIDTH_SYMBOL
            } else {
                ZhuyinKeyboardView.Mode.SYMBOL
            }
        )
        refreshNextWordSuggestionsForCurrentMode()
        vibrateLight()
    }

    private fun handleSymbolChar(ch: String) {
        if (!drainComposing(recordLearning = true)) return
        lastCommittedWord = ""
        val committed = safeEditorOperation("symbol commit") {
            currentInputConnection?.commitText(ch, 1) == true
        }
        if (!committed) return
        showNextWordSuggestions()
        vibrateLight()
    }

    private fun commitNextWordSuggestion(word: String) {
        val isNextWord = word in nextWordCandidates
        // The editor may move its cursor without immediately delivering selection callbacks.
        if (!isNextWord || !isAtLastCommittedWord()) {
            lastCommittedWord = ""
            showNextWordSuggestions()
            return
        }
        val committed = safeEditorOperation("next word suggestion commit") {
            currentInputConnection?.commitText(word, 1) == true
        }
        if (!committed) return
        if (word in NextWordSuggestions.PUNCTUATION_FALLBACK) {
            lastCommittedWord = ""
        } else if (personalizationAllowed) {
            if (CandidateLearningSettings.isEnabled(this)) {
                runCatching { learnContinuation(word); CandidateLearningSettings.notifyRecordsChanged(this) }
            }
            lastCommittedWord = word
        } else lastCommittedWord = ""
        showNextWordSuggestions()
        vibrateLight()
    }

    private fun showNextWordSuggestions() {
        if (composingText.isNotEmpty()) return
        val mode = keyboardView?.getMode() ?: return
        val previous = lastCommittedWord
        nextWordCandidates = if (personalizationAllowed && mode == ZhuyinKeyboardView.Mode.ZHUYIN &&
            isAtLastCommittedWord()
        ) {
            val learned = runCatching {
                userDictionaryStore.getLearnedCandidates(listOf(NextWordSuggestions.learningKey(previous)))
            }.getOrDefault(emptyList())
            NextWordSuggestions.suggest(previous, learned, NextWordTable.continuations(this, previous))
                .ifEmpty { NextWordSuggestions.PUNCTUATION_FALLBACK }.toSet()
        } else emptySet()
        allCandidates = nextWordCandidates.toList()
        candidateChoices = emptyList()
        showingNextWordSuggestions = nextWordCandidates.isNotEmpty()
        selectedCandidateIndex = -1
        candidatePage = 0
        candidatesExpanded = false
        syncKeyboardView()
    }

    private fun isAtLastCommittedWord(): Boolean = lastCommittedWord.isNotEmpty() && runCatching {
        currentInputConnection?.getSelectedText(0).isNullOrEmpty() &&
            currentInputConnection?.getTextBeforeCursor(lastCommittedWord.length, 0)?.toString() == lastCommittedWord
    }.getOrDefault(false)

    private fun handleEditAction(label: String) {
        if (editorKeyboardMode != EditorKeyboardMode.ZHUYIN || !drainComposing(recordLearning = false)) return
        lastCommittedWord = ""
        val connection = currentInputConnection ?: return
        val handled = safeEditorOperation("edit tool $label") {
            when (label) {
                "全選" -> connection.performContextMenuAction(android.R.id.selectAll)
                "複製" -> connection.performContextMenuAction(android.R.id.copy)
                "剪下" -> connection.performContextMenuAction(android.R.id.cut)
                "貼上" -> connection.performContextMenuAction(android.R.id.pasteAsPlainText)
                "◀" -> CursorNavigation.move(connection, -1)
                "▶" -> CursorNavigation.move(connection, 1)
                else -> false
            }
        }
        if (handled) vibrateLight()
        showNextWordSuggestions()
    }

    private fun refreshNextWordSuggestionsForCurrentMode() {
        if (composingText.isEmpty()) showNextWordSuggestions()
    }

    private fun openClipboard() {
        if (editorKeyboardMode != EditorKeyboardMode.ZHUYIN || !personalizationAllowed) return
        if (!drainComposing(recordLearning = false)) return
        lastCommittedWord = ""
        showNextWordSuggestions()
        inputContainer?.openClipboard()
    }

    private fun pasteClipboard() {
        if (editorKeyboardMode != EditorKeyboardMode.ZHUYIN) return
        val text = runCatching {
            val clip = (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).primaryClip
            if (clip != null && clip.itemCount > 0) clip.getItemAt(0).text?.toString() else null
        }.getOrNull()
        if (text.isNullOrEmpty()) {
            android.widget.Toast.makeText(this, "剪貼簿沒有可貼上的文字", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        pasteHistoryText(text)
    }

    private fun pasteHistoryText(text: String) {
        if (editorKeyboardMode != EditorKeyboardMode.ZHUYIN || !personalizationAllowed) return
        if (!drainComposing(recordLearning = false)) return
        lastCommittedWord = ""
        safeEditorOperation("paste text") { currentInputConnection?.commitText(text, 1) == true }
        showNextWordSuggestions()
    }

    private fun handleToggleToZhuyin() {
        keyboardView?.setMode(ZhuyinKeyboardView.Mode.ZHUYIN)
        if (showingNextWordSuggestions) {
            showNextWordSuggestions()
        } else {
            syncKeyboardView()
        }
        vibrateLight()
    }

    private fun applyEditorKeyboardMode() {
        val view = keyboardView ?: return
        val allowsZhuyin = editorKeyboardMode == EditorKeyboardMode.ZHUYIN
        view.setZhuyinModeAllowed(allowsZhuyin)
        view.setMode(
            when (editorKeyboardMode) {
                EditorKeyboardMode.ZHUYIN -> ZhuyinKeyboardView.Mode.ZHUYIN
                EditorKeyboardMode.ENGLISH -> ZhuyinKeyboardView.Mode.ENGLISH
                EditorKeyboardMode.NUMBER -> ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER
            }
        )
    }

    private fun commitRawComposition(provideFeedback: Boolean): Boolean {
        if (
            editorCompositionPending &&
            !syncEditorComposing(clearEmptyComposition = composingText.isEmpty())
        ) {
            return false
        }
        if (composingText.isEmpty()) return true
        val ic = currentInputConnection ?: return false
        val raw = composingText.toString()
        performingEditorEdit = true
        val committed = try {
            ic.commitText(raw, 1)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Editor raw composition commit failed", error)
            false
        } finally {
            performingEditorEdit = false
        }
        if (!committed) {
            Log.w(TAG, "Editor rejected raw composition commit")
            return false
        }
        resetToInitial()
        showNextWordSuggestions()
        if (provideFeedback) vibrateLight()
        return true
    }

    private fun drainComposing(recordLearning: Boolean): Boolean {
        if (
            editorCompositionPending &&
            !syncEditorComposing(clearEmptyComposition = composingText.isEmpty())
        ) {
            return false
        }
        while (composingText.isNotEmpty()) {
            if (editorCompositionPending && !syncEditorComposing()) return false
            val previousLength = composingText.length
            val selectedChoice = candidateChoices.getOrNull(selectedCandidateIndex)
            if (selectedChoice != null && !selectedChoice.isProvisional) {
                when (
                    commitSelectedCandidate(
                        provideFeedback = false,
                        recordLearning = recordLearning
                    )
                ) {
                    CandidateCommitResult.SUCCESS -> {
                        if (composingText.length >= previousLength) return false
                        continue
                    }
                    CandidateCommitResult.NO_CANDIDATE -> Unit
                    CandidateCommitResult.EDITOR_REJECTED,
                    CandidateCommitResult.PARTIAL -> return false
                }
            }

            if (!commitRawComposition(provideFeedback = false)) return false
        }
        return true
    }

    private fun finishComposingForLifecycle(): Boolean =
        drainComposing(recordLearning = false)

    private fun synchronizePendingComposition(): Boolean =
        !editorCompositionPending ||
            syncEditorComposing(clearEmptyComposition = composingText.isEmpty())

    private inline fun safeEditorOperation(
        operation: String,
        block: () -> Boolean
    ): Boolean = try {
        block()
    } catch (error: RuntimeException) {
        Log.w(TAG, "Editor rejected $operation", error)
        false
    }

    private fun resetToInitial() {
        composingText.clear()
        nextWordCandidates = emptySet()
        editorCompositionPending = false
        clearExpectedCompositionUpdates()
        editorComposingStart = -1
        editorComposingEnd = -1
        allCandidates = emptyList()
        candidateChoices = emptyList()
        selectedCandidateIndex = -1
        candidatePage = 0
        candidatesExpanded = false
        showingNextWordSuggestions = false
        showFinalPage = false
        syncKeyboardView()
    }

    private fun vibrateLight() {
        try {
            val currentVibrator = vibrator ?: return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                currentVibrator.vibrate(
                    VibrationEffect.createOneShot(8L, VibrationEffect.DEFAULT_AMPLITUDE)
                )
            } else {
                @Suppress("DEPRECATION")
                currentVibrator.vibrate(8L)
            }
        } catch (e: Exception) {
        }
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        inputContainer?.closeClipboard()
        lastCommittedWord = ""
        nextWordCandidates = emptySet()
        keyboardView?.cancelCursorGesture()
        stopBackspaceRepeat()
        super.onStartInput(attribute, restarting)
        sortedCandidateCache.clear()
        userCandidateCache.clear()
        if (!restarting && composingText.isNotEmpty()) {
            resetToInitial()
        }
        editorSelectionStart = attribute?.initialSelStart ?: -1
        editorSelectionEnd = attribute?.initialSelEnd ?: -1
        editorComposingStart = -1
        editorComposingEnd = -1
        personalizationAllowed = ImeBehavior.allowsPersonalizedLearning(
            inputType = attribute?.inputType,
            imeOptions = attribute?.imeOptions
        )
        clipboardCaptureAllowed = personalizationAllowed
        editorKeyboardMode = ImeBehavior.keyboardMode(
            inputType = attribute?.inputType,
            imeOptions = attribute?.imeOptions
        )
        editorReturnKeyLabel = ImeBehavior.returnKeyLabel(
            actionLabel = attribute?.actionLabel,
            imeOptions = attribute?.imeOptions ?: EditorInfo.IME_ACTION_NONE
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        if (::clipboardHistory.isInitialized) clipboardHistory.captureCurrent()
        stopBackspaceRepeat()
        super.onStartInputView(info, restarting)
        keyboardView?.customTypeface = KeyboardFont.load(this)
        applySystemTheme()
        keyboardView?.setReturnKeyLabel(editorReturnKeyLabel)
        if (composingText.isEmpty() && !editorCompositionPending) {
            resetToInitial()
        } else if (composingText.isEmpty()) {
            if (
                refreshCandidates(
                    resetSelection = false,
                    clearEmptyEditorComposition = true
                )
            ) {
                resetToInitial()
            }
        } else {
            recomputePageFromComposing()
            refreshCandidates(resetSelection = false)
        }
        applyEditorKeyboardMode()
    }

    override fun onUpdateSelection(
        oldSelStart: Int,
        oldSelEnd: Int,
        newSelStart: Int,
        newSelEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart,
            oldSelEnd,
            newSelStart,
            newSelEnd,
            candidatesStart,
            candidatesEnd
        )
        val trackedSelectionEnd = editorSelectionEnd
        val expectedMatch = expectedCompositionUpdates.consume(
            selectionStart = newSelStart,
            selectionEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd
        )
        if (expectedMatch?.kind == ExpectedCompositionMatchKind.STALE) {
            // A delayed callback from an older editor write must not overwrite
            // the composition state established by a newer write.
            if (candidatesEnd < 0) {
                editorCompositionPending = composingText.isNotEmpty()
            }
            return
        }
        val matchesExpectedComposition =
            expectedMatch?.kind == ExpectedCompositionMatchKind.CURRENT
        editorSelectionStart = newSelStart
        editorSelectionEnd = newSelEnd
        editorComposingStart = candidatesStart
        editorComposingEnd = candidatesEnd
        if (matchesExpectedComposition && candidatesEnd < 0) {
            val match = requireNotNull(expectedMatch)
            editorComposingStart = match.cursor - match.length
            editorComposingEnd = match.cursor
        }
        if (matchesExpectedComposition) {
            // Some custom editors omit composing bounds even after accepting
            // setComposingText(). Keep the buffer, but require one fresh sync
            // before a commit transaction instead of assuming an unreported
            // composing span still exists.
            editorCompositionPending = candidatesEnd < 0
            return
        }
        if (performingEditorEdit) return
        if (composingText.isEmpty()) {
            if (nextWordCandidates.isNotEmpty() && !isAtLastCommittedWord()) {
                lastCommittedWord = ""
                showNextWordSuggestions()
            }
            return
        }

        if (
            candidatesStart < 0 &&
            candidatesEnd < 0 &&
            newSelStart == newSelEnd &&
            newSelEnd == trackedSelectionEnd
        ) {
            // A boundsless callback at the same cursor is ambiguous: some
            // editors omit composing bounds, while others have just committed
            // the preedit. Reattach the exact text before the next mutation or
            // commit so it cannot be appended twice.
            editorCompositionPending = true
            return
        }

        val remainsAtComposingEnd = EditorSelectionBehavior.matchesCurrentComposition(
            selectionStart = newSelStart,
            selectionEnd = newSelEnd,
            candidatesStart = candidatesStart,
            candidatesEnd = candidatesEnd,
            composingLength = composingText.length
        )
        if (remainsAtComposingEnd) {
            editorCompositionPending = false
            return
        }

        performingEditorEdit = true
        try {
            currentInputConnection?.finishComposingText()
        } catch (error: RuntimeException) {
            Log.w(TAG, "Editor composition cleanup after selection change failed", error)
        } finally {
            performingEditorEdit = false
        }
        resetToInitial()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        inputContainer?.closeClipboard()
        lastCommittedWord = ""
        keyboardView?.cancelCursorGesture()
        stopBackspaceRepeat()
        val drained = finishComposingForLifecycle()
        if (drained || finishingInput) resetToInitial()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        inputContainer?.closeClipboard()
        lastCommittedWord = ""
        keyboardView?.cancelCursorGesture()
        stopBackspaceRepeat()
        finishComposingForLifecycle()
        resetToInitial()
        personalizationAllowed = false
        clipboardCaptureAllowed = true
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        inputContainer?.closeClipboard()
        keyboardView?.cancelCursorGesture()
        stopBackspaceRepeat()
        if (finishComposingForLifecycle()) resetToInitial()
        super.onWindowHidden()
    }

    private fun applySystemTheme() {
        keyboardView?.applySystemTheme()
        inputContainer?.refreshTheme()
        val imeWindow = window?.window ?: return
        val night = ThemePalette.isNightMode(this)
        @Suppress("DEPRECATION")
        imeWindow.navigationBarColor = ThemePalette.keyboard(this).background
        if (Build.VERSION.SDK_INT >= 30) {
            imeWindow.insetsController?.setSystemBarsAppearance(
                if (night) 0 else android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        } else if (Build.VERSION.SDK_INT >= 26) {
            @Suppress("DEPRECATION")
            imeWindow.decorView.systemUiVisibility = if (night) {
                imeWindow.decorView.systemUiVisibility and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                imeWindow.decorView.systemUiVisibility or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> if (inputContainer?.showingClipboard == true) {
            inputContainer?.closeClipboard()
            clipboardBackHandled = true
            true
        } else super.onKeyDown(keyCode, event)
        KeyEvent.KEYCODE_DEL -> {
            handleBackspaceDown()
            true
        }
        KeyEvent.KEYCODE_ENTER -> {
            handleReturn()
            true
        }
        KeyEvent.KEYCODE_SPACE -> {
            handleSpace()
            true
        }
        else -> super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
        KeyEvent.KEYCODE_BACK -> if (clipboardBackHandled) {
            clipboardBackHandled = false
            true
        } else super.onKeyUp(keyCode, event)
        KeyEvent.KEYCODE_DEL -> {
            handleBackspaceUp()
            true
        }
        else -> super.onKeyUp(keyCode, event)
    }

    private fun Int.floorMod(modulus: Int): Int =
        ((this % modulus) + modulus) % modulus

    private fun lookupVariants(raw: String): List<String> {
        val variants = linkedSetOf<String>()
        variants.add(raw)
        variants.add(markLastSegmentAsNeutralTone(raw))
        variants.add(stripTones(raw))
        variants.add(markUntonedSegmentsAsFirstTone(raw))
        return variants.filter { it.isNotEmpty() }
    }

    private fun migrateLegacyLearning() {
        val prefs = getSharedPreferences(LegacyCandidateLearning.PREFS_NAME, MODE_PRIVATE)
        val raw = prefs.getString(LegacyCandidateLearning.FREQ_KEY, "").orEmpty()
        if (raw.isEmpty()) return

        runCatching {
            userDictionaryStore.mergeLegacyLearning(LegacyCandidateLearning.parse(raw))
            prefs.edit().remove(LegacyCandidateLearning.FREQ_KEY).apply()
        }.onFailure {
            Log.w(TAG, "Unable to migrate legacy candidate learning", it)
        }
    }

    private fun stripTones(raw: String): String =
        raw.filter { it !in TONE_CHARS }

    private fun markUntonedSegmentsAsFirstTone(raw: String): String {
        val segments = splitSegments(raw)
        if (segments.isEmpty()) return raw
        val builder = StringBuilder()
        var cursor = 0
        for (segment in segments) {
            if (cursor < segment.start) builder.append(raw.substring(cursor, segment.start))
            builder.append(segment.text)
            if (!segment.hasTone) builder.append(FIRST_TONE)
            cursor = segment.end
        }
        if (cursor < raw.length) builder.append(raw.substring(cursor))
        return builder.toString()
    }

    private fun markLastSegmentAsNeutralTone(raw: String): String {
        val segments = splitSegments(raw)
        val last = segments.lastOrNull() ?: return raw
        val builder = StringBuilder(raw)
        if (last.hasTone && last.end > last.start) {
            builder.deleteCharAt(last.end - 1)
            builder.insert(last.end - 1, NEUTRAL_TONE)
        } else {
            builder.insert(last.end, NEUTRAL_TONE)
        }
        return builder.toString()
    }

    companion object {
        private const val TAG = "IOSZhuyinIME"
        private const val MAX_BACKSPACE_CONTEXT = 64
        private const val PREFIX_CANDIDATE_LIMIT = 18
        private const val CANDIDATE_CACHE_CAPACITY = 128
        private const val FIRST_TONE = "ˉ"
        private const val NEUTRAL_TONE = "˙"
        private val TONE_CHARS = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')
        private val STANDALONE_FINALS = setOf(
            "ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ",
            "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ", "ㄧ", "ㄨ", "ㄩ"
        )
    }
}
