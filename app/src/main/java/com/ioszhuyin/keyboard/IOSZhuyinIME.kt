package com.ioszhuyin.keyboard

import android.content.Context
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.LinearLayout
import java.io.File

class IOSZhuyinIME : InputMethodService() {

    private var keyboardView: ZhuyinKeyboardView? = null
    private var bopomofoTypeface: Typeface? = null
    private var vibrator: android.os.Vibrator? = null

    private val composingText = StringBuilder()
    private var allCandidates: List<String> = emptyList()
    private var selectedCandidateIndex: Int = -1
    private var candidatePage: Int = 0
    private var activeSegmentStart: Int = 0
    private var activeSegmentEnd: Int = 0
    private var showFinalPage: Boolean = false

    private val freq = mutableMapOf<String, Int>()
    private val PREFS_NAME = "zhuyin_freq"
    private val FREQ_KEY = "freq_data"

    private data class ZhuyinSegment(
        val text: String,
        val start: Int,
        val end: Int,
        val hasTone: Boolean
    )

    private fun loadFreq() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(FREQ_KEY, "") ?: ""
        if (raw.isNotEmpty()) {
            raw.split("|").forEach { entry ->
                val parts = entry.split(":", limit = 2)
                if (parts.size == 2) freq[parts[0]] = parts[1].toIntOrNull() ?: 0
            }
        }
    }

    private fun saveFreq() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(FREQ_KEY, freq.entries.joinToString("|") { "${it.key}:${it.value}" })
            .apply()
    }

    private fun wordSelected(word: String) {
        freq[word] = (freq[word] ?: 0) + 1
        saveFreq()
    }

    private fun getSortedCandidates(raw: String): List<String> {
        val dict = candidatesForKey(raw)
        return dict.sortedWith(compareByDescending<String> { freq[it] ?: 0 })
    }

    private fun candidatesForKey(raw: String): List<String> {
        for (key in lookupVariants(raw)) {
            val candidates = ZhuyinDictionary.getCandidates(key)
            if (!candidates.isNullOrEmpty()) return candidates
        }
        return emptyList()
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var backspaceRunnable: Runnable? = null
    private var isBackspaceHeld = false

    override fun onCreate() {
        super.onCreate()
        @Suppress("DEPRECATION")
        vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
        loadFreq()
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
        super.onDestroy()
        saveFreq()
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
        view.onNextCandidatesPage = { nextCandidatePage() }
        view.onEnglishMode = { handleEnglishMode() }
        view.onNumberMode = { handleNumberMode() }
        view.onSymbolMode = { handleSymbolMode() }
        view.onSymbolChar = { ch -> handleSymbolChar(ch) }
        view.onToggleToZhuyin = { handleToggleToZhuyin() }
        view.onEmojiMode = { handleEmojiMode() }
        view.onToneSelected = { tone -> onToneSelected(tone) }

        keyboardView = view
        syncKeyboardView()
        return view
    }

    private fun onZhuyinKeyPressed(key: String) {
        if (key == "⇧") {
            showFinalPage = false
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
        val raw = composingText.toString()
        if (raw.isEmpty()) {
            allCandidates = emptyList()
            selectedCandidateIndex = -1
            candidatePage = 0
            activeSegmentStart = 0
            activeSegmentEnd = 0
            syncKeyboardView()
            return
        }

        val fullCandidates = getSortedCandidates(raw)
        if (fullCandidates.isNotEmpty()) {
            allCandidates = fullCandidates
            activeSegmentStart = 0
            activeSegmentEnd = raw.length
        } else {
            val first = splitSegments(raw).firstOrNull()
            val lookup = first?.text ?: raw
            allCandidates = getSortedCandidates(lookup)
            activeSegmentStart = first?.start ?: 0
            activeSegmentEnd = first?.end ?: raw.length
        }

        selectedCandidateIndex = when {
            allCandidates.isEmpty() -> -1
            resetSelection || selectedCandidateIndex !in allCandidates.indices -> 0
            else -> selectedCandidateIndex
        }
        candidatePage = if (selectedCandidateIndex >= 0) selectedCandidateIndex / PAGE_SIZE else 0
        syncKeyboardView()
    }

    private fun syncKeyboardView() {
        val view = keyboardView ?: return
        val start = candidatePage * PAGE_SIZE
        val pageCandidates = allCandidates.drop(start).take(PAGE_SIZE)
        view.candidates = pageCandidates
        view.hasMoreCandidates = allCandidates.size > PAGE_SIZE
        view.selectedCandidateIndex =
            if (selectedCandidateIndex in start until start + pageCandidates.size) {
                selectedCandidateIndex - start
            } else {
                -1
            }
        view.setFinalPage(showFinalPage)
        view.refresh()
    }

    private fun nextCandidatePage() {
        if (allCandidates.isEmpty()) return
        val pages = (allCandidates.size + PAGE_SIZE - 1) / PAGE_SIZE
        candidatePage = (candidatePage + 1) % pages
        selectedCandidateIndex = candidatePage * PAGE_SIZE
        syncKeyboardView()
        vibrateLight()
    }

    private fun cycleCandidate() {
        if (allCandidates.isEmpty()) return
        selectedCandidateIndex = (selectedCandidateIndex + 1).floorMod(allCandidates.size)
        candidatePage = selectedCandidateIndex / PAGE_SIZE
        syncKeyboardView()
        vibrateLight()
    }

    private fun commitSelectedCandidate(candidateOverride: String? = null): Boolean {
        val ic = currentInputConnection ?: return false
        if (allCandidates.isEmpty()) return false

        val candidate = candidateOverride
            ?: allCandidates.getOrNull(selectedCandidateIndex)
            ?: return false
        ic.commitText(candidate, 1)
        wordSelected(candidate)

        val start = activeSegmentStart.coerceIn(0, composingText.length)
        val end = activeSegmentEnd.coerceIn(start, composingText.length)
        composingText.delete(start, end)
        recomputePageFromComposing()
        refreshCandidates(resetSelection = true)
        vibrateLight()
        return true
    }

    private fun splitSegments(text: String): List<ZhuyinSegment> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<ZhuyinSegment>()
        var i = 0
        while (i < text.length) {
            if (text[i] in TONE_CHARS) {
                result.add(ZhuyinSegment(text.substring(i, i + 1), i, i + 1, true))
                i++
                continue
            }

            val runStart = i
            while (i < text.length && text[i] !in TONE_CHARS) i++
            splitBaseRun(text, runStart, i, result)

            if (i < text.length && text[i] in TONE_CHARS && result.isNotEmpty()) {
                val last = result.removeAt(result.lastIndex)
                result.add(
                    ZhuyinSegment(
                        text.substring(last.start, i + 1),
                        last.start,
                        i + 1,
                        hasTone = true
                    )
                )
                i++
            }
        }
        return result
    }

    private fun splitBaseRun(
        text: String,
        start: Int,
        end: Int,
        result: MutableList<ZhuyinSegment>
    ) {
        var pos = start
        while (pos < end) {
            var foundEnd = -1
            for (len in minOf(MAX_SYLLABLE_LEN, end - pos) downTo 1) {
                val candidate = text.substring(pos, pos + len)
                if (isKnownUntonedSyllable(candidate)) {
                    foundEnd = pos + len
                    break
                }
            }
            if (foundEnd < 0) foundEnd = pos + 1
            result.add(ZhuyinSegment(text.substring(pos, foundEnd), pos, foundEnd, hasTone = false))
            pos = foundEnd
        }
    }

    private fun isKnownUntonedSyllable(value: String): Boolean =
        value in STANDALONE_FINALS || ZhuyinDictionary.getCandidates(value) != null

    private fun recomputePageFromComposing() {
        val last = splitSegments(composingText.toString()).lastOrNull()
        showFinalPage = composingText.isNotEmpty() && last?.hasTone != true
    }

    private fun handleBackspaceDown() {
        isBackspaceHeld = true
        handleBackspace()
        backspaceRunnable?.let { mainHandler.removeCallbacks(it) }
        backspaceRunnable = object : Runnable {
            override fun run() {
                if (!isBackspaceHeld) return
                handleBackspace()
                mainHandler.postDelayed(this, 80L)
            }
        }
        mainHandler.postDelayed(backspaceRunnable!!, 400L)
    }

    private fun handleBackspaceUp() {
        isBackspaceHeld = false
        backspaceRunnable?.let { mainHandler.removeCallbacks(it) }
        backspaceRunnable = null
    }

    private fun handleBackspace() {
        if (composingText.isNotEmpty()) {
            composingText.deleteCharAt(composingText.length - 1)
            recomputePageFromComposing()
            refreshCandidates(resetSelection = true)
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
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
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
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
        activeSegmentStart = 0
        activeSegmentEnd = 0
        showFinalPage = false
        syncKeyboardView()
    }

    private fun vibrateLight() {
        try {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(android.os.VibrationEffect.createOneShot(8, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (e: Exception) {
        }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        loadFreq()
        resetToInitial()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        saveFreq()
        resetToInitial()
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
        variants.add(stripTones(raw))
        variants.add(markUntonedSegmentsAsFirstTone(raw))
        return variants.filter { it.isNotEmpty() }
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

    companion object {
        private const val PAGE_SIZE = 9
        private const val MAX_SYLLABLE_LEN = 3
        private const val FIRST_TONE = "ˉ"
        private val TONE_CHARS = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')
        private val STANDALONE_FINALS = setOf(
            "ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ",
            "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ", "ㄧ", "ㄨ", "ㄩ"
        )
    }
}
