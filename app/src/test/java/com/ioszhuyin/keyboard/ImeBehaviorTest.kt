package com.ioszhuyin.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBehaviorTest {
    @Test
    fun candidateRankingKeepsManualEntriesFirstAndTiesStable() {
        val ordered = CandidateRanking.order(
            manualCandidates = listOf("自訂", "常用"),
            dictionaryCandidates = listOf("甲", "自訂", "乙", "丙", "乙"),
            learnedCounts = mapOf("甲" to 1, "乙" to 4, "丙" to 4, "自訂" to 99)
        )

        assertEquals(listOf("自訂", "常用", "乙", "丙", "甲"), ordered)
    }

    @Test
    fun legacyLearningParserIsLosslessForColonsAndIgnoresInvalidCounts() {
        val parsed = LegacyCandidateLearning.parse(
            "字:2|詞:5|無效|負數:-1|冒:號:7|字:3|空白:0"
        )

        assertEquals(mapOf("字" to 3, "詞" to 5, "冒:號" to 7), parsed)
    }

    @Test
    fun backspaceRepeaterStopsAcrossReleaseAndRestart() {
        var deletes = 0
        val scheduled = mutableListOf<Pair<Runnable, Long>>()
        val cancelled = mutableListOf<Runnable>()
        val repeater = BackspaceRepeater(
            deleteOnce = { deletes++ },
            schedule = { task, delay -> scheduled += task to delay },
            cancel = { cancelled += it }
        )

        repeater.press()
        assertEquals(1, deletes)
        assertEquals(400L, scheduled.single().second)

        val firstTask = scheduled.single().first
        firstTask.run()
        assertEquals(2, deletes)
        assertEquals(80L, scheduled.last().second)

        repeater.release()
        assertTrue(firstTask in cancelled)
        firstTask.run()
        assertEquals(2, deletes)

        repeater.press()
        assertEquals(3, deletes)
        repeater.release()
    }

    @Test
    fun backspaceHighlightFollowsKeyAcrossZhuyinPageChange() {
        val finalPage = ZhuyinDynamicLayout.FINAL_PAGE_ROWS.flatten()
        val initialPage = ZhuyinDynamicLayout.INITIAL_PAGE_ROWS.flatten()
        val oldBackspaceIndex = finalPage.indexOf("⌫")

        assertEquals("ㄖ", initialPage[oldBackspaceIndex])

        val remappedIndex = PressedKeyRemapping.remapIndex(
            previousLabels = finalPage,
            nextLabels = initialPage,
            previousIndex = oldBackspaceIndex
        )

        assertEquals("⌫", initialPage[remappedIndex])
    }

    @Test
    fun learningIsDisabledForSensitiveAndOptOutEditors() {
        val text = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visiblePassword =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val webPassword = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        assertTrue(ImeBehavior.allowsPersonalizedLearning(text, EditorInfo.IME_ACTION_DONE))
        assertFalse(ImeBehavior.allowsPersonalizedLearning(password, EditorInfo.IME_ACTION_DONE))
        assertFalse(ImeBehavior.allowsPersonalizedLearning(visiblePassword, EditorInfo.IME_ACTION_DONE))
        assertFalse(ImeBehavior.allowsPersonalizedLearning(webPassword, EditorInfo.IME_ACTION_DONE))
        assertFalse(
            ImeBehavior.allowsPersonalizedLearning(
                text,
                EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
            )
        )
        assertFalse(ImeBehavior.allowsPersonalizedLearning(null, null))
    }

    @Test
    fun editorActionPlanRespectsCustomDefaultAndNoEnterAction() {
        assertEquals(
            EditorActionPlan.CUSTOM_ACTION,
            ImeBehavior.editorActionPlan(true, EditorInfo.IME_ACTION_GO)
        )
        assertEquals(
            EditorActionPlan.DEFAULT_ACTION,
            ImeBehavior.editorActionPlan(false, EditorInfo.IME_ACTION_SEARCH)
        )
        assertEquals(
            EditorActionPlan.INSERT_NEWLINE,
            ImeBehavior.editorActionPlan(false, EditorInfo.IME_ACTION_NONE)
        )
        assertEquals(
            EditorActionPlan.INSERT_NEWLINE,
            ImeBehavior.editorActionPlan(
                true,
                EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            )
        )
    }

    @Test
    fun returnKeyLabelMatchesEditorActionAndCustomLabel() {
        assertEquals("換行", ImeBehavior.returnKeyLabel(null, EditorInfo.IME_ACTION_NONE))
        assertEquals("前往", ImeBehavior.returnKeyLabel(null, EditorInfo.IME_ACTION_GO))
        assertEquals("搜尋", ImeBehavior.returnKeyLabel(null, EditorInfo.IME_ACTION_SEARCH))
        assertEquals("傳送", ImeBehavior.returnKeyLabel(null, EditorInfo.IME_ACTION_SEND))
        assertEquals("下一步", ImeBehavior.returnKeyLabel(null, EditorInfo.IME_ACTION_NEXT))
        assertEquals("完成", ImeBehavior.returnKeyLabel(null, EditorInfo.IME_ACTION_DONE))
        assertEquals("上一個", ImeBehavior.returnKeyLabel(null, EditorInfo.IME_ACTION_PREVIOUS))
        assertEquals("登入", ImeBehavior.returnKeyLabel("登入", EditorInfo.IME_ACTION_GO))
        assertEquals(
            "換行",
            ImeBehavior.returnKeyLabel(
                "登入",
                EditorInfo.IME_ACTION_GO or EditorInfo.IME_FLAG_NO_ENTER_ACTION
            )
        )
    }

    @Test
    fun passwordEditorsSelectAndLockToNonZhuyinModes() {
        val normalText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visiblePassword =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val webPassword =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        val pin = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD

        assertEquals(EditorKeyboardMode.ZHUYIN, ImeBehavior.keyboardMode(normalText))
        assertEquals(EditorKeyboardMode.ENGLISH, ImeBehavior.keyboardMode(password))
        assertEquals(EditorKeyboardMode.ENGLISH, ImeBehavior.keyboardMode(visiblePassword))
        assertEquals(EditorKeyboardMode.ENGLISH, ImeBehavior.keyboardMode(webPassword))
        assertEquals(EditorKeyboardMode.NUMBER, ImeBehavior.keyboardMode(pin))
        assertEquals(EditorKeyboardMode.ZHUYIN, ImeBehavior.keyboardMode(null))
    }

    @Test
    fun candidatePageSizeAdaptsToPhraseLength() {
        assertEquals(9, ImeBehavior.candidatePageSize(listOf("這", "那")))
        assertEquals(4, ImeBehavior.candidatePageSize(listOf("所以", "但是")))
        assertEquals(3, ImeBehavior.candidatePageSize(listOf("這一版", "這一板")))
        assertEquals(2, ImeBehavior.candidatePageSize(listOf("候選片語")))
        assertEquals(1, ImeBehavior.candidatePageSize(listOf("很長的候選片語")))
        assertEquals(9, ImeBehavior.candidatePageSize(emptyList()))
    }

    @Test
    fun zhuyinControlRowMatchesIosDynamicPages() {
        assertEquals(
            listOf("123", "☺", "空白", "換行"),
            ImeBehavior.zhuyinControlLabels(showFinalPage = false)
        )
        assertEquals(
            listOf("123", "☺", "空白", "搜尋"),
            ImeBehavior.zhuyinControlLabels(showFinalPage = false, returnLabel = "搜尋")
        )
        assertEquals(
            listOf("123", "☺", "一聲", "選定"),
            ImeBehavior.zhuyinControlLabels(showFinalPage = true)
        )
    }

    @Test
    fun expandedCandidatePanelCoversEveryCandidateAndClampsScrolling() {
        assertEquals(0, CandidatePanelBehavior.rowCount(candidateCount = 0, columnCount = 9))
        assertEquals(1, CandidatePanelBehavior.rowCount(candidateCount = 9, columnCount = 9))
        assertEquals(3, CandidatePanelBehavior.rowCount(candidateCount = 20, columnCount = 9))
        assertEquals(20f, CandidatePanelBehavior.clampScroll(50f, 120f, 100f))
        assertEquals(0f, CandidatePanelBehavior.clampScroll(-10f, 120f, 100f))
        assertEquals(0f, CandidatePanelBehavior.clampScroll(10f, 80f, 100f))
        assertEquals(1, CandidatePanelBehavior.pageAfterSwipe(0, 1, 3))
        assertEquals(2, CandidatePanelBehavior.pageAfterSwipe(2, 1, 3))
        assertEquals(0, CandidatePanelBehavior.pageAfterSwipe(0, -1, 3))
        assertEquals(1, CandidatePanelBehavior.pageAfterSwipe(2, -1, 3))
    }

    @Test
    fun candidateSelectionKeepsUnconvertedZhuyinSuffix() {
        val raw = "ㄓㄨㄥˉㄏㄨㄚˊㄖㄣˊ"
        val convertedEnd = "ㄓㄨㄥˉㄏㄨㄚˊ".length

        assertEquals(
            "ㄖㄣˊ",
            CompositionEditing.remainingAfterSelection(raw, start = 0, end = convertedEnd)
        )
        assertEquals("", CompositionEditing.remainingAfterSelection(raw, 0, raw.length))
    }

    @Test
    fun auxiliaryPagesMatchIosNumberAndSymbolRows() {
        assertEquals(
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
                listOf("#+=", ".", ",", "?", "!", "'", "⌫")
            ),
            IosAuxiliaryLayout.NUMBER_ROWS
        )
        assertEquals(
            listOf(
                listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
                listOf("_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"),
                listOf("123", ".", ",", "?", "!", "'", "⌫")
            ),
            IosAuxiliaryLayout.SYMBOL_ROWS
        )
    }

    @Test
    fun unicodeBackspaceDeletesWholeUserPerceivedCharacter() {
        assertEquals(1, UnicodeBackspace.codeUnitsToDelete("注"))
        assertEquals(2, UnicodeBackspace.codeUnitsToDelete("A😀"))
        assertEquals("👍🏽".length, UnicodeBackspace.codeUnitsToDelete("A👍🏽"))
        assertEquals("e\u0301".length, UnicodeBackspace.codeUnitsToDelete("Ae\u0301"))
        assertEquals("👨‍👩‍👧‍👦".length, UnicodeBackspace.codeUnitsToDelete("A👨‍👩‍👧‍👦"))
        assertEquals("🇹🇼".length, UnicodeBackspace.codeUnitsToDelete("A🇹🇼"))
        assertEquals("🇹".length, UnicodeBackspace.codeUnitsToDelete("A🇺🇸🇹"))
        assertEquals(2, UnicodeBackspace.codeUnitsToDelete("line\r\n"))
        assertEquals(0, UnicodeBackspace.codeUnitsToDelete(""))
    }
}
