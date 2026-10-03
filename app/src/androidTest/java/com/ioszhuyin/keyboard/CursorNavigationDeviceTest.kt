package com.ioszhuyin.keyboard

import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class CursorNavigationDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    @Test fun editorMovesAcrossChineseEmojiAndCombiningMarksWithoutEditingText() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            val original = "中👨‍👩‍👧‍👦e\u0301文"
            editor.setText(original)
            editor.setSelection(original.length)
            val connection = editor.onCreateInputConnection(EditorInfo())!!
            assertTrue(CursorNavigation.move(connection, -1))
            assertEquals(original.length - 1, editor.selectionStart)
            assertTrue(CursorNavigation.move(connection, -1))
            assertEquals(12, editor.selectionStart) // after the whole family emoji
            assertTrue(CursorNavigation.move(connection, -1))
            assertEquals(1, editor.selectionStart)
            assertTrue(CursorNavigation.move(connection, 2))
            assertEquals(original.length - 1, editor.selectionStart)
            assertTrue(CursorNavigation.move(connection, 30))
            assertEquals(original.length, editor.selectionStart)
            assertTrue(CursorNavigation.move(connection, -30))
            assertEquals(0, editor.selectionStart)
            assertEquals(original, editor.text.toString())
        }
    }

    @Test fun selectionsCollapseInTheRequestedDirectionBeforeFurtherMovement() {
        instrumentation.runOnMainSync {
            val editor = EditText(instrumentation.targetContext)
            editor.setText("甲乙丙丁")
            val connection = editor.onCreateInputConnection(EditorInfo())!!
            editor.setSelection(1, 3)
            CursorNavigation.move(connection, -1)
            assertEquals(1, editor.selectionStart)
            assertEquals(1, editor.selectionEnd)
            editor.setSelection(3, 1)
            CursorNavigation.move(connection, 2)
            assertEquals(4, editor.selectionStart)
            assertEquals("甲乙丙丁", editor.text.toString())
        }
    }

    @Test fun editorsWithoutExtractedTextReceivePairedArrowEvents() {
        instrumentation.runOnMainSync {
            val events = mutableListOf<KeyEvent>()
            val connection = object : BaseInputConnection(EditText(instrumentation.targetContext), false) {
                override fun sendKeyEvent(event: KeyEvent): Boolean {
                    events += event
                    return true
                }
            }
            assertTrue(CursorNavigation.move(connection, -2))
            assertEquals(listOf(0, 1, 0, 1), events.map { it.action })
            assertTrue(events.all { it.keyCode == KeyEvent.KEYCODE_DPAD_LEFT })
        }
    }
}
