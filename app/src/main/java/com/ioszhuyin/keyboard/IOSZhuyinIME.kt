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
    private var showFinalPage: Boolean = false

    private var personalizationAllowed = false
    private var editorKeyboardMode = EditorKeyboardMode.ZHUYIN
    private var editorReturnKeyLabel = "換行"

    private fun wordSelected(reading: String, word: String) {
        if (!personalizationAllowed || !CandidateLearningSettings.isEnabled(this)) return
        runCatching {
            userDictionaryStore.recordSelection(reading, word)
            CandidateLearningSettings.notifyRecordsChanged(this)
        }.onFailure {
            Log.w(TAG, "Unable to record candidate learning", it)
        }
    }

    private fun getSortedCandidates(raw: String): List<String> {
        val dictionaryCandidates = candidatesForKey(raw)
        if (!personalizationAllowed) return dictionaryCandidates

        val userCandidates = userCandidatesForKey(raw)
        val learnedCounts = runCatching {
            userDictionaryStore.getLearningCounts(lookupVariants(raw))
        }.onFailure {
            Log.w(TAG, "Unable to load candidate learning", it)
        }.getOrDefault(emptyMap())
        return CandidateRanking.order(userCandidates, dictionaryCandidates, learnedCounts)
    }

    private fun userCandidatesForKey(raw: String): List<String> {
        if (!personalizationAllowed || !::userDictionaryStore.isInitialized) return emptyList()
        return userDictionaryStore.getCandidates(lookupVariants(raw))
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
        view.composingText = composingText

        view.onKeyPress = { key -> onZhuyinKeyPressed(key) }
        view.onBackspace = { handleBackspaceDown() }
        view.onBackspaceRelease = { handleBackspaceUp() }
        view.onSpace = { handleSpace() }
        view.onReturn = { handleReturn() }
        view.onSwitchIme = { switchKeyboard() }
        view.onCandidatePress = { candidate -> commitSelectedCandidate(candidate) }
        view.onCandidateExpansionToggle = { toggleCandidateExpansion() }
        view.onCandidatePageSwipe = { delta -> moveCandidatePage(delta) }
        view.onEnglishMode = { handleEnglishMode() }
        view.onNumberMode = { handleNumberMode() }
        view.onSymbolMode = { handleSymbolMode() }
        view.onSymbolChar = { ch -> handleSymbolChar(ch) }
        view.onToggleToZhuyin = { handleToggleToZhuyin() }
        view.onEmojiMode = { handleEmojiMode() }
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
        composingText.append(key)
        showFinalPage = true
        refreshCandidates(resetSelection = true)
        vibrateLight()
    }

    private fun onToneSelected(tone: String) {
        if (composingText.isEmpty()) return
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

    private fun refreshCandidates(resetSelection: Boolean) {
        if (resetSelection) candidatesExpanded = false
        val raw = composingText.toString()
        if (raw.isEmpty()) {
            allCandidates = emptyList()
            selectedCandidateIndex = -1
            candidatePage = 0
            candidatesExpanded = false
            activeSegmentStart = 0
            activeSegmentEnd = 0
            syncKeyboardView()
            return
        }

        val match = ZhuyinComposition.resolveLeadingCandidates(
            raw = raw,
            segments = splitSegments(raw),
            candidatesForReading = ::getSortedCandidates
        )
        allCandidates = match?.candidates.orEmpty()
        activeSegmentStart = match?.start ?: 0
        activeSegmentEnd = match?.end ?: raw.length

        selectedCandidateIndex = when {
            allCandidates.isEmpty() -> -1
            resetSelection || selectedCandidateIndex !in allCandidates.indices -> 0
            else -> selectedCandidateIndex
        }
        val pageSize = ImeBehavior.candidatePageSize(allCandidates)
        candidatePage = if (selectedCandidateIndex >= 0) selectedCandidateIndex / pageSize else 0
        syncKeyboardView()
    }

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

    private fun commitSelectedCandidate(candidateOverride: String? = null): Boolean {
        val ic = currentInputConnection ?: return false
        if (allCandidates.isEmpty()) return false

        val candidate = candidateOverride
            ?: allCandidates.getOrNull(selectedCandidateIndex)
            ?: return false
        val start = activeSegmentStart.coerceIn(0, composingText.length)
        val end = activeSegmentEnd.coerceIn(start, composingText.length)
        val reading = composingText.substring(start, end)
        ic.commitText(candidate, 1)
        wordSelected(reading, candidate)

        composingText.delete(start, end)
        recomputePageFromComposing()
        refreshCandidates(resetSelection = true)
        vibrateLight()
        return true
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
        if (composingText.isNotEmpty()) {
            composingText.deleteCharAt(composingText.length - 1)
            recomputePageFromComposing()
            refreshCandidates(resetSelection = true)
        } else {
            currentInputConnection?.let { ic ->
                val beforeCursor = ic.getTextBeforeCursor(MAX_BACKSPACE_CONTEXT, 0)
                val codeUnits = UnicodeBackspace.codeUnitsToDelete(beforeCursor)
                val deleted = when {
                    codeUnits > 0 -> ic.deleteSurroundingText(codeUnits, 0)
                    else -> ic.deleteSurroundingTextInCodePoints(1, 0)
                }
                if (!deleted) {
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL))
                    ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
                }
            }
            syncKeyboardView()
        }
        vibrateLight()
    }

    private fun handleSpace() {
        if (composingText.isNotEmpty()) {
            if (showFinalPage) {
                applyToneToLastSegment(FIRST_TONE)
                showFinalPage = false
                refreshCandidates(resetSelection = true)
                vibrateLight()
            } else {
                cycleCandidate()
            }
        } else {
            currentInputConnection?.commitText(" ", 1)
            vibrateLight()
        }
    }

    private fun handleReturn() {
        if (composingText.isNotEmpty()) {
            commitSelectedCandidate()
            return
        }
        val ic = currentInputConnection ?: return
        val info = currentInputEditorInfo
        val actionPlan = ImeBehavior.editorActionPlan(
            hasCustomActionLabel = info?.actionLabel != null,
            imeOptions = info?.imeOptions ?: EditorInfo.IME_ACTION_NONE
        )
        val handled = when (actionPlan) {
            EditorActionPlan.CUSTOM_ACTION -> ic.performEditorAction(info?.actionId ?: 0)
            EditorActionPlan.DEFAULT_ACTION -> sendDefaultEditorAction(true)
            EditorActionPlan.INSERT_NEWLINE -> false
        }
        if (!handled) {
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        }
        vibrateLight()
    }

    private fun handleEnglishMode() {
        if (!commitComposingBeforeModeSwitch()) return
        keyboardView?.setMode(ZhuyinKeyboardView.Mode.ENGLISH)
        vibrateLight()
    }

    private fun handleNumberMode() {
        if (!commitComposingBeforeModeSwitch()) return
        keyboardView?.setMode(ZhuyinKeyboardView.Mode.NUMBER)
        vibrateLight()
    }

    private fun handleSymbolMode() {
        if (!commitComposingBeforeModeSwitch()) return
        keyboardView?.setMode(ZhuyinKeyboardView.Mode.SYMBOL)
        vibrateLight()
    }

    private fun handleSymbolChar(ch: String) {
        if (!commitComposingBeforeModeSwitch()) return
        currentInputConnection?.commitText(ch, 1)
        vibrateLight()
    }

    private fun handleToggleToZhuyin() {
        keyboardView?.setMode(ZhuyinKeyboardView.Mode.ZHUYIN)
        syncKeyboardView()
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

    private fun handleEmojiMode() {
        if (!commitComposingBeforeModeSwitch()) return
        switchKeyboard()
    }

    private fun commitComposingBeforeModeSwitch(): Boolean {
        if (composingText.isEmpty()) return true
        if (!commitSelectedCandidate()) return false
        return composingText.isEmpty()
    }

    private fun switchKeyboard() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
        imm.showInputMethodPicker()
    }

    private fun resetToInitial() {
        composingText.clear()
        allCandidates = emptyList()
        selectedCandidateIndex = -1
        candidatePage = 0
        candidatesExpanded = false
        activeSegmentStart = 0
        activeSegmentEnd = 0
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
        personalizationAllowed = ImeBehavior.allowsPersonalizedLearning(
            inputType = attribute?.inputType,
            imeOptions = attribute?.imeOptions
        )
        editorKeyboardMode = ImeBehavior.keyboardMode(attribute?.inputType)
        editorReturnKeyLabel = ImeBehavior.returnKeyLabel(
            actionLabel = attribute?.actionLabel,
            imeOptions = attribute?.imeOptions ?: EditorInfo.IME_ACTION_NONE
        )
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        stopBackspaceRepeat()
        super.onStartInputView(info, restarting)
        keyboardView?.setReturnKeyLabel(editorReturnKeyLabel)
        resetToInitial()
        applyEditorKeyboardMode()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        stopBackspaceRepeat()
        resetToInitial()
        super.onFinishInputView(finishingInput)
    }

    override fun onFinishInput() {
        stopBackspaceRepeat()
        personalizationAllowed = false
        super.onFinishInput()
    }

    override fun onWindowHidden() {
        stopBackspaceRepeat()
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
        private const val FIRST_TONE = "ˉ"
        private const val NEUTRAL_TONE = "˙"
        private val TONE_CHARS = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')
        private val STANDALONE_FINALS = setOf(
            "ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ",
            "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ", "ㄧ", "ㄨ", "ㄩ"
        )
    }
}
