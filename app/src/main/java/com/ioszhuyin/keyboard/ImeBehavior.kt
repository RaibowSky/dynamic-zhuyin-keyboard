package com.ioszhuyin.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal enum class EditorActionPlan(
    val committedText: String? = null,
    val allowsKeyEventFallback: Boolean = true
) {
    CUSTOM_ACTION,
    DEFAULT_ACTION,
    INSERT_NEWLINE(committedText = "\n", allowsKeyEventFallback = false)
}

internal enum class EditorKeyboardMode {
    ZHUYIN,
    ENGLISH,
    NUMBER
}

internal object ImeBehavior {
    private const val CANDIDATE_ROW_UNITS = 9

    @Suppress("InlinedApi")
    fun allowsPersonalizedLearning(inputType: Int?, imeOptions: Int?): Boolean {
        if (inputType == null || imeOptions == null) return false
        if ((imeOptions and EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0) return false

        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        if (inputClass != InputType.TYPE_CLASS_TEXT) return false

        val variation = inputType and InputType.TYPE_MASK_VARIATION
        return variation !in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        )
    }

    fun editorActionPlan(hasCustomActionLabel: Boolean, imeOptions: Int): EditorActionPlan {
        if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) {
            return EditorActionPlan.INSERT_NEWLINE
        }
        if (hasCustomActionLabel) return EditorActionPlan.CUSTOM_ACTION

        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_NONE,
            EditorInfo.IME_ACTION_UNSPECIFIED -> EditorActionPlan.INSERT_NEWLINE
            else -> EditorActionPlan.DEFAULT_ACTION
        }
    }

    fun returnKeyLabel(actionLabel: CharSequence?, imeOptions: Int): String {
        if ((imeOptions and EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "換行"
        actionLabel?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        return when (imeOptions and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_GO -> "前往"
            EditorInfo.IME_ACTION_SEARCH -> "搜尋"
            EditorInfo.IME_ACTION_SEND -> "傳送"
            EditorInfo.IME_ACTION_NEXT -> "下一步"
            EditorInfo.IME_ACTION_DONE -> "完成"
            EditorInfo.IME_ACTION_PREVIOUS -> "上一個"
            else -> "換行"
        }
    }

    fun keyboardMode(inputType: Int?, imeOptions: Int? = null): EditorKeyboardMode {
        val forceAscii = imeOptions != null &&
            (imeOptions and EditorInfo.IME_FLAG_FORCE_ASCII) != 0
        if (inputType == null) {
            return if (forceAscii) EditorKeyboardMode.ENGLISH else EditorKeyboardMode.ZHUYIN
        }
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        when (inputClass) {
            InputType.TYPE_CLASS_NUMBER,
            InputType.TYPE_CLASS_DATETIME,
            InputType.TYPE_CLASS_PHONE -> return EditorKeyboardMode.NUMBER
        }
        if (forceAscii) return EditorKeyboardMode.ENGLISH
        if (
            inputClass == InputType.TYPE_CLASS_TEXT &&
            variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
            )
        ) {
            return EditorKeyboardMode.ENGLISH
        }
        return EditorKeyboardMode.ZHUYIN
    }

    fun candidatePageSize(candidates: List<String>): Int {
        val longest = candidates.maxOfOrNull { candidate ->
            candidate.codePointCount(0, candidate.length)
        } ?: 1
        return (CANDIDATE_ROW_UNITS / longest.coerceAtLeast(1)).coerceIn(1, CANDIDATE_ROW_UNITS)
    }

    fun zhuyinControlLabels(
        showFinalPage: Boolean,
        returnLabel: String = "換行"
    ): List<String> =
        if (showFinalPage) {
            listOf("123", "ABC", "一聲", "選定")
        } else {
            listOf("123", "ABC", "空白", returnLabel)
        }
}

internal object CandidatePanelBehavior {
    fun rowCount(candidateCount: Int, columnCount: Int): Int {
        if (candidateCount <= 0) return 0
        return (candidateCount + columnCount.coerceAtLeast(1) - 1) /
            columnCount.coerceAtLeast(1)
    }

    fun clampScroll(offset: Float, contentHeight: Float, viewportHeight: Float): Float =
        offset.coerceIn(0f, (contentHeight - viewportHeight).coerceAtLeast(0f))

    fun pageAfterSwipe(currentPage: Int, delta: Int, pageCount: Int): Int {
        if (pageCount <= 0) return 0
        return (currentPage + delta).coerceIn(0, pageCount - 1)
    }
}

internal object IosAuxiliaryLayout {
    val NUMBER_ROWS: List<List<String>> = listOf(
        listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
        listOf("-", "/", ":", ";", "(", ")", "$", "@", "「", "」"),
        listOf("#+=", "。", "，", "、", "?", "!", "’", "⌫")
    )

    val SYMBOL_ROWS: List<List<String>> = listOf(
        listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
        listOf("_", "—", "\\", "|", "~", "«", "»", "¥", "&", "·"),
        listOf("123", "…", "，", "^_^", "?", "!", "’", "⌫")
    )
}

internal object CompositionEditing {
    enum class SuffixState {
        NONE,
        COMPOSING,
        PLAIN,
        PENDING
    }

    data class CandidateSelection(
        val reading: String,
        val committedText: String,
        val remainingText: String
    )

    fun candidateSelection(
        raw: String,
        start: Int,
        end: Int,
        candidate: String
    ): CandidateSelection {
        val safeStart = start.coerceIn(0, raw.length)
        val safeEnd = end.coerceIn(safeStart, raw.length)
        return CandidateSelection(
            reading = raw.substring(safeStart, safeEnd),
            committedText = candidate,
            remainingText = raw.removeRange(safeStart, safeEnd)
        )
    }

    fun suffixState(
        hasSuffix: Boolean,
        composingAccepted: Boolean,
        plainFallbackAccepted: Boolean
    ): SuffixState = when {
        !hasSuffix -> SuffixState.NONE
        composingAccepted -> SuffixState.COMPOSING
        plainFallbackAccepted -> SuffixState.PLAIN
        else -> SuffixState.PENDING
    }
}

internal object EditorSelectionBehavior {
    fun isSynchronizedWithoutConnection(
        composingLength: Int,
        compositionPending: Boolean
    ): Boolean = composingLength == 0 && !compositionPending

    fun hasSelection(start: Int, end: Int): Boolean =
        start >= 0 && end >= 0 && start != end

    fun collapsedAfterDeletingSelection(start: Int, end: Int): Int? =
        if (hasSelection(start, end)) minOf(start, end) else null

    fun composingCursorAfterSet(
        selectionStart: Int,
        selectionEnd: Int,
        composingStart: Int,
        composingEnd: Int,
        composingLength: Int
    ): Int? {
        if (composingLength < 0) return null
        val replacementStart = when {
            composingStart >= 0 && composingEnd >= composingStart -> composingStart
            selectionStart >= 0 && selectionEnd >= 0 -> minOf(selectionStart, selectionEnd)
            else -> return null
        }
        return replacementStart + composingLength
    }

    fun matchesExpectedCompositionUpdate(
        selectionStart: Int,
        selectionEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        expectedCursor: Int,
        expectedLength: Int
    ): Boolean {
        if (
            expectedCursor < 0 ||
            expectedLength < 0 ||
            selectionStart != expectedCursor ||
            selectionEnd != expectedCursor
        ) {
            return false
        }
        return candidatesEnd < 0 || (
            candidatesStart >= 0 &&
                candidatesEnd == expectedCursor &&
                candidatesEnd - candidatesStart == expectedLength
            )
    }

    fun matchesCurrentComposition(
        selectionStart: Int,
        selectionEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int,
        composingLength: Int
    ): Boolean =
        composingLength > 0 &&
            candidatesStart >= 0 &&
            candidatesEnd >= candidatesStart &&
            candidatesEnd - candidatesStart == composingLength &&
            selectionStart == candidatesEnd &&
            selectionEnd == candidatesEnd

    fun cursorAfterReplacingComposition(
        selectionStart: Int,
        selectionEnd: Int,
        composingLength: Int,
        replacementLength: Int
    ): Int? {
        if (selectionStart < 0 || selectionStart != selectionEnd) return null
        if (composingLength < 0 || replacementLength < 0 || selectionEnd < composingLength) {
            return null
        }
        return selectionEnd - composingLength + replacementLength
    }
}

internal enum class ExpectedCompositionMatchKind {
    STALE,
    CURRENT
}

internal data class ExpectedCompositionMatch(
    val kind: ExpectedCompositionMatchKind,
    val cursor: Int,
    val length: Int
)

internal data class ExpectedCompositionToken internal constructor(
    internal val id: Long,
    internal val previousCurrentId: Long?
)

internal class ExpectedCompositionUpdateTracker(
    private val maxPending: Int = 32
) {
    private data class Update(
        val id: Long,
        val cursor: Int,
        val length: Int
    )

    private val pending = mutableListOf<Update>()
    private var nextId = 1L
    private var currentId: Long? = null

    init {
        require(maxPending > 0)
    }

    fun advance(): ExpectedCompositionToken {
        val token = ExpectedCompositionToken(nextId++, currentId)
        currentId = token.id
        return token
    }

    fun expect(cursor: Int, length: Int): ExpectedCompositionToken {
        require(cursor >= 0)
        require(length >= 0)
        val token = advance()
        pending.add(Update(token.id, cursor, length))
        while (pending.size > maxPending) pending.removeAt(0)
        return token
    }

    fun advanceWithoutExpectation(cursor: Int, length: Int): ExpectedCompositionToken {
        require(cursor >= 0)
        require(length >= 0)
        return advance()
    }

    fun cancel(token: ExpectedCompositionToken) {
        pending.removeAll { it.id == token.id }
        if (currentId == token.id) currentId = token.previousCurrentId
    }

    fun discard(token: ExpectedCompositionToken) {
        pending.removeAll { it.id == token.id }
    }

    fun isPending(token: ExpectedCompositionToken): Boolean =
        pending.any { it.id == token.id }

    fun consume(
        selectionStart: Int,
        selectionEnd: Int,
        candidatesStart: Int,
        candidatesEnd: Int
    ): ExpectedCompositionMatch? {
        val index = pending.indexOfLast { update ->
            EditorSelectionBehavior.matchesExpectedCompositionUpdate(
                selectionStart = selectionStart,
                selectionEnd = selectionEnd,
                candidatesStart = candidatesStart,
                candidatesEnd = candidatesEnd,
                expectedCursor = update.cursor,
                expectedLength = update.length
            )
        }
        if (index < 0) return null

        val update = pending.removeAt(index)
        return ExpectedCompositionMatch(
            kind = if (update.id == currentId) {
                ExpectedCompositionMatchKind.CURRENT
            } else {
                ExpectedCompositionMatchKind.STALE
            },
            cursor = update.cursor,
            length = update.length
        )
    }

    fun clear() {
        pending.clear()
        currentId = null
    }
}

internal data class CandidateEditorTransactionResult(
    val batchStarted: Boolean,
    val candidateCommitted: Boolean,
    val suffixComposed: Boolean,
    val plainSuffixCommitted: Boolean,
    val operationFailure: RuntimeException?,
    val batchCompletionAccepted: Boolean?,
    val batchCompletionFailure: RuntimeException?
)

internal object CandidateEditorTransaction {
    fun execute(
        hasSuffix: Boolean,
        beginBatchEdit: () -> Boolean,
        commitCandidate: () -> Boolean,
        setComposingSuffix: () -> Boolean,
        commitPlainSuffix: () -> Boolean,
        endBatchEdit: () -> Boolean
    ): CandidateEditorTransactionResult {
        var batchStarted = false
        var candidateCommitted = false
        var suffixComposed = !hasSuffix
        var plainSuffixCommitted = false
        var operationFailure: RuntimeException? = null
        var batchCompletionAccepted: Boolean? = null
        var batchCompletionFailure: RuntimeException? = null

        try {
            batchStarted = beginBatchEdit()
            if (batchStarted) {
                candidateCommitted = commitCandidate()
                if (candidateCommitted && hasSuffix) {
                    suffixComposed = setComposingSuffix()
                    if (!suffixComposed) plainSuffixCommitted = commitPlainSuffix()
                }
            }
        } catch (error: RuntimeException) {
            operationFailure = error
        } finally {
            if (batchStarted) {
                try {
                    batchCompletionAccepted = endBatchEdit()
                } catch (error: RuntimeException) {
                    batchCompletionFailure = error
                }
            }
        }

        return CandidateEditorTransactionResult(
            batchStarted = batchStarted,
            candidateCommitted = candidateCommitted,
            suffixComposed = suffixComposed,
            plainSuffixCommitted = plainSuffixCommitted,
            operationFailure = operationFailure,
            batchCompletionAccepted = batchCompletionAccepted,
            batchCompletionFailure = batchCompletionFailure
        )
    }
}

internal object PressedKeyRemapping {
    fun remapIndex(
        previousLabels: List<String>,
        nextLabels: List<String>,
        previousIndex: Int
    ): Int {
        val label = previousLabels.getOrNull(previousIndex) ?: return -1
        return nextLabels.indexOf(label)
    }
}

internal class BackspaceRepeater(
    private val deleteOnce: () -> Unit,
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val initialDelayMillis: Long = 400L,
    private val repeatDelayMillis: Long = 80L
) {
    private var held = false
    private var repeatTask: Runnable? = null

    fun press() {
        release()
        held = true
        deleteOnce()
        val task = object : Runnable {
            override fun run() {
                if (!held) return
                deleteOnce()
                schedule(this, repeatDelayMillis)
            }
        }
        repeatTask = task
        schedule(task, initialDelayMillis)
    }

    fun release() {
        held = false
        repeatTask?.let(cancel)
        repeatTask = null
    }
}

internal object UnicodeBackspace {
    private const val ZERO_WIDTH_JOINER = 0x200D
    private const val CARRIAGE_RETURN = 0x000D
    private const val LINE_FEED = 0x000A
    private const val REGIONAL_INDICATOR_START = 0x1F1E6
    private const val REGIONAL_INDICATOR_END = 0x1F1FF
    private const val EMOJI_MODIFIER_START = 0x1F3FB
    private const val EMOJI_MODIFIER_END = 0x1F3FF

    /** Returns the UTF-16 code units occupied by the final user-perceived character. */
    fun codeUnitsToDelete(textBeforeCursor: CharSequence?): Int {
        if (textBeforeCursor.isNullOrEmpty()) return 0
        val text = textBeforeCursor.toString()
        var clusterStart = consumeCharacterWithSuffixes(text, text.length)
        val finalCodePoint = Character.codePointAt(text, clusterStart)

        if (finalCodePoint == LINE_FEED && clusterStart > 0) {
            val previous = Character.codePointBefore(text, clusterStart)
            if (previous == CARRIAGE_RETURN) {
                clusterStart -= Character.charCount(previous)
            }
        }

        if (isRegionalIndicator(finalCodePoint) && clusterStart > 0) {
            var scan = clusterStart
            var count = 1
            while (scan > 0) {
                val previous = Character.codePointBefore(text, scan)
                if (!isRegionalIndicator(previous)) break
                scan -= Character.charCount(previous)
                count++
            }
            if (count % 2 == 0) {
                clusterStart -= Character.charCount(Character.codePointBefore(text, clusterStart))
            }
        }

        while (clusterStart > 0) {
            val previous = Character.codePointBefore(text, clusterStart)
            if (previous != ZERO_WIDTH_JOINER) break
            clusterStart -= Character.charCount(previous)
            clusterStart = consumeCharacterWithSuffixes(text, clusterStart)
        }
        return text.length - clusterStart
    }

    private fun consumeCharacterWithSuffixes(text: String, endExclusive: Int): Int {
        var start = endExclusive
        while (start > 0) {
            val codePoint = Character.codePointBefore(text, start)
            if (!isSuffix(codePoint)) break
            start -= Character.charCount(codePoint)
        }
        if (start > 0) {
            val base = Character.codePointBefore(text, start)
            start -= Character.charCount(base)
        }
        return start
    }

    private fun isSuffix(codePoint: Int): Boolean {
        val type = Character.getType(codePoint)
        return type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.COMBINING_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt() ||
            codePoint == 0xFE0E || codePoint == 0xFE0F ||
            codePoint in 0xE0100..0xE01EF ||
            codePoint in 0xE0020..0xE007F ||
            codePoint in EMOJI_MODIFIER_START..EMOJI_MODIFIER_END
    }

    private fun isRegionalIndicator(codePoint: Int): Boolean =
        codePoint in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END
}
