package com.ioszhuyin.keyboard

import android.text.InputType
import android.view.inputmethod.EditorInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImeBehaviorTest {
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
