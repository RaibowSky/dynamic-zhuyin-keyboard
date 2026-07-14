package com.ioszhuyin.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo

internal enum class EditorActionPlan {
    CUSTOM_ACTION,
    DEFAULT_ACTION,
    INSERT_NEWLINE
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

    fun keyboardMode(inputType: Int?): EditorKeyboardMode {
        if (inputType == null) return EditorKeyboardMode.ZHUYIN
        val inputClass = inputType and InputType.TYPE_MASK_CLASS
        val variation = inputType and InputType.TYPE_MASK_VARIATION

        if (
            inputClass == InputType.TYPE_CLASS_NUMBER &&
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        ) {
            return EditorKeyboardMode.NUMBER
        }
        if (
            inputClass == InputType.TYPE_CLASS_TEXT &&
            variation in setOf(
                InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
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

    fun zhuyinControlLabels(showFinalPage: Boolean): List<String> =
        if (showFinalPage) {
            listOf("123", "☺", "一聲", "選定")
        } else {
            listOf("123", "☺", "空白", "換行")
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
