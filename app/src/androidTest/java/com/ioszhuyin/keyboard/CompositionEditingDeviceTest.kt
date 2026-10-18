package com.ioszhuyin.keyboard

import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnectionWrapper
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Actual IME handlers and an Android EditText connection, including delayed selection reports. */
class CompositionEditingDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private inner class Fixture {
        val ime = object : IOSZhuyinIME() {
            override fun getCurrentInputConnection() = connection
        }
        val editor = EditText(instrumentation.targetContext).apply {
            setText("前")
            setSelection(1)
        }
        val reports = mutableListOf<IntArray>()
        var immediateReports = false
        var rejectSelection = false
        private val connection = object : InputConnectionWrapper(
            editor.onCreateInputConnection(EditorInfo())!!, false
        ) {
            private fun report() {
                if (immediateReports) this@Fixture.report() else reports += snapshot()
            }
            override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.setComposingText(text, newCursorPosition).also { report() }
            override fun setSelection(start: Int, end: Int): Boolean =
                if (rejectSelection) false else super.setSelection(start, end).also { report() }
            override fun setComposingRegion(start: Int, end: Int): Boolean =
                super.setComposingRegion(start, end).also { report() }
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean =
                super.commitText(text, newCursorPosition).also { report() }
        }
        init {
            field("editorSelectionStart").setInt(ime, 1)
            field("editorSelectionEnd").setInt(ime, 1)
        }
        fun field(name: String) = IOSZhuyinIME::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun call(name: String, vararg args: Any): Any? = IOSZhuyinIME::class.java.getDeclaredMethod(name,
            *args.map { if (it is Int) Int::class.javaPrimitiveType!! else it.javaClass }.toTypedArray())
            .apply { isAccessible = true }.invoke(ime, *args)
        fun raw() = requireNotNull(field("composingText").get(ime)).toString()
        fun type(text: String) { text.forEach { call("onZhuyinKeyPressed", it.toString()) } }
        fun snapshot() = intArrayOf(editor.selectionStart, editor.selectionEnd,
            BaseInputConnection.getComposingSpanStart(editor.text),
            BaseInputConnection.getComposingSpanEnd(editor.text))
        fun report(value: IntArray = snapshot()) {
            ime.onUpdateSelection(0, 0, value[0], value[1], value[2], value[3])
        }
        fun flush() {
            val queued = reports.toList()
            reports.clear()
            queued.forEach(::report)
        }
        fun select(start: Int, end: Int = start) {
            flush()
            editor.setSelection(start, end)
            report()
        }
        fun assertReading(text: String, cursor: Int) {
            assertEquals(text, raw())
            assertEquals("前$text", editor.text.toString())
            assertEquals(cursor, editor.selectionStart)
            assertEquals(cursor, editor.selectionEnd)
            if (text.isNotEmpty()) {
                assertEquals(1, BaseInputConnection.getComposingSpanStart(editor.text))
                assertEquals(1 + text.length, BaseInputConnection.getComposingSpanEnd(editor.text))
            }
        }
    }

    private fun run(test: Fixture.() -> Unit) {
        ZhuyinDictionary.initialize(instrumentation.targetContext)
        instrumentation.runOnMainSync { Fixture().test() }
    }

    @Test fun movingLeftThenDeletingAndInsertingKeepsReadingUntilExplicitSelection() = run {
        type("ㄋㄧˇㄏㄠˇ")
        flush()
        assertTrue(call("moveEditingCursor", -4) as Boolean)
        flush()
        assertReading("ㄋㄧˇㄏㄠˇ", 3)
        call("handleBackspace")
        flush()
        assertReading("ㄋˇㄏㄠˇ", 2)
        type("ㄧ")
        flush()
        assertReading("ㄋㄧˇㄏㄠˇ", 3)
        assertTrue((field("allCandidates").get(ime) as List<*>).isNotEmpty())
        call("handleCandidateConfirm")
        flush()
        assertTrue(editor.text.toString().startsWith("前你"))
        assertFalse(editor.text.toString().contains("ㄋㄧˇ"))
    }

    @Test fun tappingAndSelectingInsideReadingReplacesOnlySelectedZhuyin() = run {
        type("ㄋㄧˇㄏㄠˇ")
        select(3, 2) // reversed selection over ㄧ
        type("ㄨ")
        flush()
        assertReading("ㄋㄨˇㄏㄠˇ", 3)
        select(1, 3)
        call("handleBackspace")
        flush()
        assertReading("ˇㄏㄠˇ", 1)
        call("handleBackspace") // at start must not erase the last syllable
        flush()
        assertReading("ˇㄏㄠˇ", 1)
    }

    @Test fun toneChangesTheSyllableAtTheCaretAndArrowsDoNotConvertIt() = run {
        type("ㄋㄧˇㄏㄠˇ")
        select(3)
        call("onToneSelected", "ˋ")
        flush()
        assertReading("ㄋㄧˋㄏㄠˇ", 4)
        call("handleEditAction", "◀")
        flush()
        assertReading("ㄋㄧˋㄏㄠˇ", 3)
        call("handleEditAction", "▶")
        flush()
        assertReading("ㄋㄧˋㄏㄠˇ", 4)
    }

    @Test fun delayedCallbacksCannotResetTheCaretOrLoseRapidEdits() = run {
        type("ㄋㄧˇㄏㄠˇ")
        flush()
        call("moveEditingCursor", -4)
        call("handleBackspace")
        type("ㄧ")
        flush()
        assertReading("ㄋㄧˇㄏㄠˇ", 3)
        call("handleBackspace")
        flush()
        assertReading("ㄋˇㄏㄠˇ", 2)
    }

    @Test fun leavingReadingPreservesLiteralZhuyinAndDoesNotChooseFirstCandidate() = run {
        type("ㄋㄧˇ")
        flush()
        call("moveEditingCursor", -4)
        flush()
        assertEquals("", raw())
        assertEquals("前ㄋㄧˇ", editor.text.toString())
        assertEquals(0, editor.selectionStart)
        assertEquals(-1, BaseInputConnection.getComposingSpanStart(editor.text))
    }

    @Test fun boundslessEditorCanReattachReadingAtAnInternalCaretWithoutDuplicatingIt() = run {
        type("ㄋㄧˇ")
        flush()
        call("moveEditingCursor", -1)
        flush()
        report(intArrayOf(3, 3, -1, -1))
        call("handleBackspace")
        flush()
        assertReading("ㄋˇ", 2)
    }

    @Test fun deletingTheWholeReadingThenTypingBeforeCallbacksKeepsCorrectPosition() = run {
        type("ㄋ")
        flush()
        call("handleBackspace")
        type("ㄏㄠˇ")
        flush()
        assertReading("ㄏㄠˇ", 4)
        call("moveEditingCursor", -2)
        flush()
        assertReading("ㄏㄠˇ", 2)
    }

    @Test fun synchronousSelectionReportsKeepInteriorCaretAndCandidateCommitWorking() = run {
        immediateReports = true
        type("ㄋㄧˇㄏㄠˇ")
        call("moveEditingCursor", -4)
        call("handleBackspace")
        type("ㄧ")
        assertReading("ㄋㄧˇㄏㄠˇ", 3)
        call("handleCandidateConfirm")
        assertTrue(editor.text.toString().startsWith("前你"))
        assertFalse(editor.text.toString().contains("ㄋㄧˇ"))
    }

    @Test fun copyToolPreservesRawReadingRatherThanAutoSelectingCandidate() = run {
        type("ㄋㄧˇ")
        select(1, 4)
        call("handleEditAction", "複製")
        assertEquals("前ㄋㄧˇ", editor.text.toString())
        assertEquals("", raw())
        assertEquals(1, editor.selectionStart)
        assertEquals(4, editor.selectionEnd)
    }

    @Test fun boundslessReversedSelectionCanBeReplacedWithoutShiftingTheReading() = run {
        type("ㄋㄧˇ")
        select(3, 2)
        // Mark the existing span uncertain, as a boundsless editor report would.
        field("editorCompositionPending").setBoolean(ime, true)
        type("ㄨ")
        flush()
        assertReading("ㄋㄨˇ", 3)
    }

    @Test fun rejectedCaretRestoreFallsBackToTheActualEditorCaretForFurtherTyping() = run {
        type("ㄋㄧˇ")
        flush()
        call("moveEditingCursor", -1)
        flush()
        rejectSelection = true
        type("ㄨ")
        flush()
        assertReading("ㄋㄧㄨˇ", 5)
        rejectSelection = false
        type("ㄏ")
        flush()
        assertReading("ㄋㄧㄨˇㄏ", 6)
    }
}
