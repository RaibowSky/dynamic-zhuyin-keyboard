package com.ioszhuyin.keyboard

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
    private var selectedCandidateIndex: Int = -1
    private var candidatePage: Int = 0
    private var candidatesExpanded: Boolean = false
    private var activeSegmentStart: Int = 0
    private var activeSegmentEnd: Int = 0
    private var activeCandidatesProvisional: Boolean = false
    private var showingPunctuationSuggestions: Boolean = false
    private var showFinalPage: Boolean = false

    private var personalizationAllowed = false
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
        if (!personalizationAllowed || !CandidateLearningSettings.isEnabled(this)) return
        runCatching {
            userDictionaryStore.recordSelection(reading, word)
            CandidateLearningSettings.notifyRecordsChanged(this)
            sortedCandidateCache.clear()
        }.onFailure {
            Log.w(TAG, "Unable to record candidate learning", it)
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
        if (dictionaryCandidates.isEmpty() && userCandidates.isEmpty()) {
            sortedCandidateCache[raw] = emptyList()
            return emptyList()
        }
        val learnedCounts = runCatching {
            userDictionaryStore.getLearningCounts(lookupVariants(raw))
        }.onFailure {
            Log.w(TAG, "Unable to load candidate learning", it)
        }.getOrDefault(emptyMap())
        return CandidateRanking.order(userCandidates, dictionaryCandidates, learnedCounts).also {
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
        view.bopomofoTypeface = bopomofoTypeface ?: Typeface.create("sans-serif", Typeface.NORMAL)

        view.onKeyPress = { key -> onZhuyinKeyPressed(key) }
        view.onBackspace = { handleBackspaceDown() }
        view.onBackspaceRelease = { handleBackspaceUp() }
        view.onSpace = { handleSpace() }
        view.onReturn = { handleReturn() }
        view.onCandidateConfirm = { handleCandidateConfirm() }
        view.onCandidatePress = { candidate -> handleCandidatePress(candidate) }
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
        return view
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
            allCandidates = emptyList()
            selectedCandidateIndex = -1
            candidatePage = 0
            candidatesExpanded = false
            activeSegmentStart = 0
            activeSegmentEnd = 0
            activeCandidatesProvisional = false
            showingPunctuationSuggestions = false
            val editorAccepted = !syncEditorComposition ||
                syncEditorComposing(clearEmptyComposition = clearEmptyEditorComposition)
            syncKeyboardView()
            return editorAccepted
        }

        showingPunctuationSuggestions = false

        val match = ZhuyinComposition.resolveLeadingCandidates(
            raw = raw,
            segments = splitSegments(raw),
            preferredPrefixCandidatesForReading = ::userCandidatesForKey,
            prefixCandidatesForReading = ::prefixCandidatesForKey,
            candidatesForReading = ::getSortedCandidates
        )
        allCandidates = match?.candidates.orEmpty()
        activeSegmentStart = match?.start ?: 0
        activeSegmentEnd = match?.end ?: raw.length
        activeCandidatesProvisional = match?.isProvisional == true

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
            expanded = candidatesExpanded
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

    private fun handleCandidatePress(candidate: String) {
        if (showingPunctuationSuggestions && composingText.isEmpty()) {
            commitPunctuationSuggestion(candidate)
        } else {
            commitSelectedCandidate(candidate)
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
        candidateOverride: String? = null,
        provideFeedback: Boolean = true,
        recordLearning: Boolean = true
    ): CandidateCommitResult {
        if (editorCompositionPending && !syncEditorComposing()) {
            return CandidateCommitResult.EDITOR_REJECTED
        }
        val ic = currentInputConnection ?: return CandidateCommitResult.EDITOR_REJECTED
        if (allCandidates.isEmpty()) return CandidateCommitResult.NO_CANDIDATE

        val candidate = candidateOverride
            ?: allCandidates.getOrNull(selectedCandidateIndex)
            ?: return CandidateCommitResult.NO_CANDIDATE
        val start = activeSegmentStart.coerceIn(0, composingText.length)
        val end = activeSegmentEnd.coerceIn(start, composingText.length)
        val raw = composingText.toString()
        val edit = CompositionEditing.candidateSelection(
            raw = raw,
            start = start,
            end = end,
            candidate = candidate
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
                if (recordLearning && !activeCandidatesProvisional) {
                    wordSelected(edit.reading, edit.committedText)
                }
                recomputePageFromComposing()
                refreshCandidates(
                    resetSelection = true,
                    syncEditorComposition = false
                )
                if (composingText.isEmpty()) showPunctuationSuggestions()
                if (provideFeedback) vibrateLight()
                CandidateCommitResult.SUCCESS
            }
            CompositionEditing.SuffixState.PLAIN -> {
                Log.w(TAG, "Remaining composition was preserved as plain text")
                candidateCursor?.plus(edit.remainingText.length)?.let { cursor ->
                    editorSelectionStart = cursor
                    editorSelectionEnd = cursor
                }
                if (recordLearning && !activeCandidatesProvisional) {
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
        backspaceRepeater.press()
    }

    private fun handleBackspaceUp() {
        stopBackspaceRepeat()
    }

    private fun stopBackspaceRepeat() {
        backspaceRepeater.release()
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
        if (!drainComposing(recordLearning = true)) return
        keyboardView?.setMode(ZhuyinKeyboardView.Mode.ENGLISH)
        refreshPunctuationSuggestionsForCurrentMode()
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
        refreshPunctuationSuggestionsForCurrentMode()
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
        refreshPunctuationSuggestionsForCurrentMode()
        vibrateLight()
    }

    private fun handleSymbolChar(ch: String) {
        if (!drainComposing(recordLearning = true)) return
        val committed = safeEditorOperation("symbol commit") {
            currentInputConnection?.commitText(ch, 1) == true
        }
        if (!committed) return
        showPunctuationSuggestions()
        vibrateLight()
    }

    private fun commitPunctuationSuggestion(punctuation: String) {
        val committed = safeEditorOperation("punctuation suggestion commit") {
            currentInputConnection?.commitText(punctuation, 1) == true
        }
        if (!committed) return
        showPunctuationSuggestions()
        vibrateLight()
    }

    private fun showPunctuationSuggestions() {
        if (composingText.isNotEmpty()) return
        val mode = keyboardView?.getMode() ?: return
        allCandidates = if (
            mode == ZhuyinKeyboardView.Mode.ENGLISH ||
            mode == ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER ||
            mode == ZhuyinKeyboardView.Mode.HALF_WIDTH_SYMBOL
        ) {
            PunctuationSuggestions.HALF_WIDTH
        } else {
            PunctuationSuggestions.FULL_WIDTH
        }
        showingPunctuationSuggestions = true
        selectedCandidateIndex = -1
        candidatePage = 0
        candidatesExpanded = false
        activeSegmentStart = 0
        activeSegmentEnd = 0
        activeCandidatesProvisional = false
        syncKeyboardView()
    }

    private fun refreshPunctuationSuggestionsForCurrentMode() {
        if (showingPunctuationSuggestions) showPunctuationSuggestions()
    }

    private fun handleToggleToZhuyin() {
        keyboardView?.setMode(ZhuyinKeyboardView.Mode.ZHUYIN)
        if (showingPunctuationSuggestions) {
            showPunctuationSuggestions()
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
                EditorKeyboardMode.NUMBER -> ZhuyinKeyboardView.Mode.NUMBER
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
        showPunctuationSuggestions()
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
            if (allCandidates.isNotEmpty() && !activeCandidatesProvisional) {
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
        editorCompositionPending = false
        clearExpectedCompositionUpdates()
        editorComposingStart = -1
        editorComposingEnd = -1
        allCandidates = emptyList()
        selectedCandidateIndex = -1
        candidatePage = 0
        candidatesExpanded = false
        activeSegmentStart = 0
        activeSegmentEnd = 0
        activeCandidatesProvisional = false
        showingPunctuationSuggestions = false
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
        stopBackspaceRepeat()
        super.onStartInputView(info, restarting)
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
        if (performingEditorEdit || composingText.isEmpty()) return

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
        stopBackspaceRepeat()
        val drained = finishComposingForLifecycle()
        if (drained || finishingInput) resetToInitial()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopBackspaceRepeat()
        finishComposingForLifecycle()
        resetToInitial()
        personalizationAllowed = false
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        stopBackspaceRepeat()
        if (finishComposingForLifecycle()) resetToInitial()
        super.onWindowHidden()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean = when (keyCode) {
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
