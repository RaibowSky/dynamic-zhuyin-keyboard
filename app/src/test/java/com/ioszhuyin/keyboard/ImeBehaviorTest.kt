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
        assertEquals("\n", EditorActionPlan.INSERT_NEWLINE.committedText)
        assertFalse(EditorActionPlan.INSERT_NEWLINE.allowsKeyEventFallback)
        assertTrue(EditorActionPlan.DEFAULT_ACTION.allowsKeyEventFallback)
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
    fun passwordEditorsSelectAndLockToEnglishMode() {
        val normalText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        val password = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val visiblePassword =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        val webPassword =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD

        assertEquals(EditorKeyboardMode.ZHUYIN, ImeBehavior.keyboardMode(normalText))
        assertEquals(EditorKeyboardMode.ENGLISH, ImeBehavior.keyboardMode(password))
        assertEquals(EditorKeyboardMode.ENGLISH, ImeBehavior.keyboardMode(visiblePassword))
        assertEquals(EditorKeyboardMode.ENGLISH, ImeBehavior.keyboardMode(webPassword))
        assertEquals(EditorKeyboardMode.ZHUYIN, ImeBehavior.keyboardMode(null))
    }

    @Test
    fun numericDateTimeAndPhoneEditorsSelectNumberMode() {
        val number = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL
        val decimalNumber =
            InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
        val pin = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        val date = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
        val time = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_TIME
        val phone = InputType.TYPE_CLASS_PHONE

        assertEquals(EditorKeyboardMode.NUMBER, ImeBehavior.keyboardMode(number))
        assertEquals(EditorKeyboardMode.NUMBER, ImeBehavior.keyboardMode(decimalNumber))
        assertEquals(EditorKeyboardMode.NUMBER, ImeBehavior.keyboardMode(pin))
        assertEquals(EditorKeyboardMode.NUMBER, ImeBehavior.keyboardMode(date))
        assertEquals(EditorKeyboardMode.NUMBER, ImeBehavior.keyboardMode(time))
        assertEquals(EditorKeyboardMode.NUMBER, ImeBehavior.keyboardMode(phone))
    }

    @Test
    fun emailEditorsSelectEnglishModeWhileUriEditorsAllowZhuyinSearch() {
        val email =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        val webEmail =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        val uri = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI

        assertEquals(EditorKeyboardMode.ENGLISH, ImeBehavior.keyboardMode(email))
        assertEquals(EditorKeyboardMode.ENGLISH, ImeBehavior.keyboardMode(webEmail))
        assertEquals(EditorKeyboardMode.ZHUYIN, ImeBehavior.keyboardMode(uri))
        assertEquals(
            EditorKeyboardMode.ZHUYIN,
            ImeBehavior.keyboardMode(uri, EditorInfo.IME_ACTION_SEARCH)
        )
        assertEquals(
            EditorKeyboardMode.ENGLISH,
            ImeBehavior.keyboardMode(uri, EditorInfo.IME_FLAG_FORCE_ASCII)
        )
    }

    @Test
    fun forceAsciiEditorsSelectEnglishWithoutOverridingNumericClasses() {
        val normalText = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        val number = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_NORMAL

        assertEquals(
            EditorKeyboardMode.ENGLISH,
            ImeBehavior.keyboardMode(normalText, EditorInfo.IME_FLAG_FORCE_ASCII)
        )
        assertEquals(
            EditorKeyboardMode.ENGLISH,
            ImeBehavior.keyboardMode(null, EditorInfo.IME_FLAG_FORCE_ASCII)
        )
        assertEquals(
            EditorKeyboardMode.NUMBER,
            ImeBehavior.keyboardMode(number, EditorInfo.IME_FLAG_FORCE_ASCII)
        )
        assertEquals(
            EditorKeyboardMode.ZHUYIN,
            ImeBehavior.keyboardMode(normalText, EditorInfo.IME_ACTION_GO)
        )
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
            listOf("123", "ABC", "空白", "換行"),
            ImeBehavior.zhuyinControlLabels(showFinalPage = false)
        )
        assertEquals(
            listOf("123", "ABC", "空白", "搜尋"),
            ImeBehavior.zhuyinControlLabels(showFinalPage = false, returnLabel = "搜尋")
        )
        assertEquals(
            listOf("123", "ABC", "一聲", "選定"),
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
        val raw = "ㄋㄧˇㄐㄧㄚˉㄖㄣˊㄅ"
        val convertedEnd = "ㄋㄧˇㄐㄧㄚˉ".length

        val edit = CompositionEditing.candidateSelection(
            raw = raw,
            start = 0,
            end = convertedEnd,
            candidate = "你家"
        )

        assertEquals("ㄋㄧˇㄐㄧㄚˉ", edit.reading)
        assertEquals("你家", edit.committedText)
        assertEquals("ㄖㄣˊㄅ", edit.remainingText)
        assertEquals(
            "",
            CompositionEditing.candidateSelection(raw, 0, raw.length, "你家人").remainingText
        )
    }

    @Test
    fun suffixStateDistinguishesComposingPlainAndPendingPreservation() {
        assertEquals(
            CompositionEditing.SuffixState.NONE,
            CompositionEditing.suffixState(
                hasSuffix = false,
                composingAccepted = false,
                plainFallbackAccepted = false
            )
        )
        assertEquals(
            CompositionEditing.SuffixState.COMPOSING,
            CompositionEditing.suffixState(
                hasSuffix = true,
                composingAccepted = true,
                plainFallbackAccepted = false
            )
        )
        assertEquals(
            CompositionEditing.SuffixState.PLAIN,
            CompositionEditing.suffixState(
                hasSuffix = true,
                composingAccepted = false,
                plainFallbackAccepted = true
            )
        )
        assertEquals(
            CompositionEditing.SuffixState.PENDING,
            CompositionEditing.suffixState(
                hasSuffix = true,
                composingAccepted = false,
                plainFallbackAccepted = false
            )
        )
    }

    @Test
    fun editorSelectionDetectsRangesAndComputesReplacementCursor() {
        assertTrue(
            EditorSelectionBehavior.isSynchronizedWithoutConnection(
                composingLength = 0,
                compositionPending = false
            )
        )
        assertFalse(
            EditorSelectionBehavior.isSynchronizedWithoutConnection(
                composingLength = 0,
                compositionPending = true
            )
        )
        assertFalse(
            EditorSelectionBehavior.isSynchronizedWithoutConnection(
                composingLength = 1,
                compositionPending = false
            )
        )
        assertFalse(EditorSelectionBehavior.hasSelection(-1, -1))
        assertFalse(EditorSelectionBehavior.hasSelection(4, 4))
        assertTrue(EditorSelectionBehavior.hasSelection(2, 5))
        assertEquals(2, EditorSelectionBehavior.collapsedAfterDeletingSelection(5, 2))
        assertEquals(null, EditorSelectionBehavior.collapsedAfterDeletingSelection(4, 4))
        assertEquals(
            13,
            EditorSelectionBehavior.composingCursorAfterSet(
                selectionStart = 10,
                selectionEnd = 10,
                composingStart = -1,
                composingEnd = -1,
                composingLength = 3
            )
        )
        assertEquals(
            11,
            EditorSelectionBehavior.composingCursorAfterSet(
                selectionStart = 12,
                selectionEnd = 12,
                composingStart = 7,
                composingEnd = 12,
                composingLength = 4
            )
        )
        assertTrue(
            EditorSelectionBehavior.matchesExpectedCompositionUpdate(
                selectionStart = 11,
                selectionEnd = 11,
                candidatesStart = 7,
                candidatesEnd = 11,
                expectedCursor = 11,
                expectedLength = 4
            )
        )
        assertTrue(
            EditorSelectionBehavior.matchesExpectedCompositionUpdate(
                selectionStart = 11,
                selectionEnd = 11,
                candidatesStart = -1,
                candidatesEnd = -1,
                expectedCursor = 11,
                expectedLength = 4
            )
        )
        assertFalse(
            EditorSelectionBehavior.matchesExpectedCompositionUpdate(
                selectionStart = 11,
                selectionEnd = 11,
                candidatesStart = 8,
                candidatesEnd = 11,
                expectedCursor = 11,
                expectedLength = 4
            )
        )
        assertTrue(
            EditorSelectionBehavior.matchesCurrentComposition(
                selectionStart = 11,
                selectionEnd = 11,
                candidatesStart = 7,
                candidatesEnd = 11,
                composingLength = 4
            )
        )
        assertFalse(
            EditorSelectionBehavior.matchesCurrentComposition(
                selectionStart = 11,
                selectionEnd = 11,
                candidatesStart = 8,
                candidatesEnd = 11,
                composingLength = 4
            )
        )
        assertFalse(
            EditorSelectionBehavior.matchesCurrentComposition(
                selectionStart = 10,
                selectionEnd = 10,
                candidatesStart = 7,
                candidatesEnd = 11,
                composingLength = 4
            )
        )
        assertEquals(
            8,
            EditorSelectionBehavior.cursorAfterReplacingComposition(
                selectionStart = 12,
                selectionEnd = 12,
                composingLength = 6,
                replacementLength = 2
            )
        )
        assertEquals(
            null,
            EditorSelectionBehavior.cursorAfterReplacingComposition(
                selectionStart = 4,
                selectionEnd = 5,
                composingLength = 2,
                replacementLength = 1
            )
        )
        assertEquals(
            null,
            EditorSelectionBehavior.cursorAfterReplacingComposition(
                selectionStart = 2,
                selectionEnd = 2,
                composingLength = 3,
                replacementLength = 1
            )
        )
    }

    @Test
    fun expectedCompositionTrackerIgnoresDelayedCallbacksFromOlderWrites() {
        val tracker = ExpectedCompositionUpdateTracker()
        tracker.expect(cursor = 9, length = 6)
        tracker.expect(cursor = 5, length = 2)

        val stale = tracker.consume(
            selectionStart = 9,
            selectionEnd = 9,
            candidatesStart = 3,
            candidatesEnd = 9
        )
        val current = tracker.consume(
            selectionStart = 5,
            selectionEnd = 5,
            candidatesStart = 3,
            candidatesEnd = 5
        )

        assertEquals(ExpectedCompositionMatchKind.STALE, stale?.kind)
        assertEquals(ExpectedCompositionMatchKind.CURRENT, current?.kind)
    }

    @Test
    fun expectedCompositionTrackerKeepsOlderCallbacksStaleAfterCurrentArrivesFirst() {
        val tracker = ExpectedCompositionUpdateTracker()
        tracker.expect(cursor = 9, length = 6)
        tracker.expect(cursor = 5, length = 2)

        val current = tracker.consume(5, 5, 3, 5)
        val delayed = tracker.consume(9, 9, 3, 9)

        assertEquals(ExpectedCompositionMatchKind.CURRENT, current?.kind)
        assertEquals(ExpectedCompositionMatchKind.STALE, delayed?.kind)
    }

    @Test
    fun expectedCompositionTrackerPrefersCurrentWriteWhenGeometryMatches() {
        val tracker = ExpectedCompositionUpdateTracker()
        tracker.expect(cursor = 9, length = 6)
        tracker.expect(cursor = 9, length = 6)

        val first = tracker.consume(9, 9, 3, 9)
        val second = tracker.consume(9, 9, 3, 9)

        assertEquals(ExpectedCompositionMatchKind.CURRENT, first?.kind)
        assertEquals(ExpectedCompositionMatchKind.STALE, second?.kind)
    }

    @Test
    fun unchangedGeometryKeepsPriorCallbackStale() {
        val tracker = ExpectedCompositionUpdateTracker()
        tracker.expect(cursor = 9, length = 6)

        tracker.advanceWithoutExpectation(cursor = 9, length = 6)

        assertEquals(
            ExpectedCompositionMatchKind.STALE,
            tracker.consume(9, 9, -1, -1)?.kind
        )
        assertEquals(null, tracker.consume(9, 9, 3, 9))
    }

    @Test
    fun discardedRejectedWriteLeavesOlderCallbacksStale() {
        val tracker = ExpectedCompositionUpdateTracker()
        tracker.expect(cursor = 9, length = 6)
        val rejected = tracker.expect(cursor = 10, length = 7)

        tracker.discard(rejected)

        assertEquals(
            ExpectedCompositionMatchKind.STALE,
            tracker.consume(9, 9, 3, 9)?.kind
        )
    }

    @Test
    fun cancellingRejectedCompositionWriteRestoresPreviousExpectation() {
        val tracker = ExpectedCompositionUpdateTracker()
        tracker.expect(cursor = 9, length = 6)
        val rejected = tracker.expect(cursor = 5, length = 2)

        tracker.cancel(rejected)

        assertEquals(
            ExpectedCompositionMatchKind.CURRENT,
            tracker.consume(9, 9, 3, 9)?.kind
        )
    }

    @Test
    fun candidateTransactionPreservesPlainSuffixBeforeEndingBatch() {
        val calls = mutableListOf<String>()

        val result = CandidateEditorTransaction.execute(
            hasSuffix = true,
            beginBatchEdit = { calls.add("begin"); true },
            commitCandidate = { calls.add("candidate"); true },
            setComposingSuffix = { calls.add("composeSuffix"); false },
            commitPlainSuffix = { calls.add("plainSuffix"); true },
            endBatchEdit = { calls.add("end"); true }
        )

        assertEquals(
            listOf("begin", "candidate", "composeSuffix", "plainSuffix", "end"),
            calls
        )
        assertTrue(result.batchStarted)
        assertTrue(result.candidateCommitted)
        assertFalse(result.suffixComposed)
        assertTrue(result.plainSuffixCommitted)
        assertEquals(null, result.operationFailure)
        assertEquals(true, result.batchCompletionAccepted)
    }

    @Test
    fun candidateTransactionEndsBatchAfterOperationFailure() {
        val calls = mutableListOf<String>()

        val result = CandidateEditorTransaction.execute(
            hasSuffix = true,
            beginBatchEdit = { calls.add("begin"); true },
            commitCandidate = {
                calls.add("candidate")
                throw IllegalStateException("stale connection")
            },
            setComposingSuffix = { calls.add("composeSuffix"); true },
            commitPlainSuffix = { calls.add("plainSuffix"); true },
            endBatchEdit = { calls.add("end"); true }
        )

        assertEquals(listOf("begin", "candidate", "end"), calls)
        assertFalse(result.candidateCommitted)
        assertTrue(result.operationFailure is IllegalStateException)
        assertEquals(true, result.batchCompletionAccepted)
    }

    @Test
    fun candidateTransactionDoesNotEndRejectedBatch() {
        val calls = mutableListOf<String>()

        val result = CandidateEditorTransaction.execute(
            hasSuffix = false,
            beginBatchEdit = { calls.add("begin"); false },
            commitCandidate = { calls.add("candidate"); true },
            setComposingSuffix = { calls.add("composeSuffix"); true },
            commitPlainSuffix = { calls.add("plainSuffix"); true },
            endBatchEdit = { calls.add("end"); true }
        )

        assertEquals(listOf("begin"), calls)
        assertFalse(result.batchStarted)
        assertFalse(result.candidateCommitted)
        assertEquals(null, result.batchCompletionAccepted)
    }

    @Test
    fun candidateTransactionReportsBatchCompletionFailureWithoutLosingCommit() {
        val result = CandidateEditorTransaction.execute(
            hasSuffix = false,
            beginBatchEdit = { true },
            commitCandidate = { true },
            setComposingSuffix = { true },
            commitPlainSuffix = { true },
            endBatchEdit = { throw IllegalStateException("connection closed") }
        )

        assertTrue(result.candidateCommitted)
        assertTrue(result.suffixComposed)
        assertTrue(result.batchCompletionFailure is IllegalStateException)
    }

    @Test
    fun dynamicZhuyinRowsPreserveConfiguredOffsetsAndFinalPageAlignment() {
        assertEquals(
            listOf(0.5f, 0f, 0f),
            ZhuyinDynamicLayout.rowStartSlots(
                showFinalPage = false,
                configuredOffsets = listOf(0.5f, 0f, 0f)
            )
        )
        assertEquals(
            listOf(0.5f, 1f, 0f),
            ZhuyinDynamicLayout.rowStartSlots(
                showFinalPage = true,
                configuredOffsets = listOf(0.5f, 0f, 0f)
            )
        )
        assertEquals(
            listOf(1f, 1f, 2f),
            ZhuyinDynamicLayout.rowStartSlots(
                showFinalPage = true,
                configuredOffsets = listOf(1f, 0.5f, 2f)
            )
        )
    }

    @Test
    fun finalToneRowFillsAllColumnsWithEqualKeys() {
        val geometry = ZhuyinDynamicLayout.evenlyFilledRow(
            ZhuyinDynamicLayout.FINAL_PAGE_ROWS[2].size
        )

        assertEquals(listOf(0f, 1.5f, 3f, 4.5f, 6f, 7.5f), geometry.map { it.slot })
        assertEquals(List(6) { 1.5f }, geometry.map { it.span })
    }

    @Test
    fun auxiliaryPagesMatchIosNumberAndSymbolRows() {
        assertEquals(
            listOf(
                listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "0"),
                listOf("-", "/", ":", ";", "(", ")", "$", "@", "「", "」"),
                listOf("#+=", "。", "，", "、", "?", "!", "’", "⌫")
            ),
            IosAuxiliaryLayout.NUMBER_ROWS
        )
        assertEquals(
            listOf(
                listOf("[", "]", "{", "}", "#", "%", "^", "*", "+", "="),
                listOf("_", "—", "\\", "|", "~", "«", "»", "¥", "&", "·"),
                listOf("123", "…", "，", "^_^", "?", "!", "’", "⌫")
            ),
            IosAuxiliaryLayout.SYMBOL_ROWS
        )
    }

    @Test
    fun auxiliaryThirdRowsUseEightEqualKeysAcrossTheFullWidth() {
        val geometry = ZhuyinDynamicLayout.evenlyFilledRow(
            keyCount = IosAuxiliaryLayout.NUMBER_ROWS[2].size,
            columnCount = 10
        )

        assertEquals(8, geometry.size)
        assertEquals(List(8) { it * 1.25f }, geometry.map { it.slot })
        assertEquals(List(8) { 1.25f }, geometry.map { it.span })
        assertEquals(
            IosAuxiliaryLayout.NUMBER_ROWS[2].size,
            IosAuxiliaryLayout.SYMBOL_ROWS[2].size
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
