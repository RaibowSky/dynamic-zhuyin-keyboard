package com.ioszhuyin.keyboard

import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Exercises the custom canvas keyboard's real hit regions and touch dispatch. */
class EnglishKeyboardDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withKeyboard(test: (ZhuyinKeyboardView) -> Unit) {
        instrumentation.runOnMainSync {
            val view = ZhuyinKeyboardView(instrumentation.targetContext)
            view.layout(0, 0, 1080, 2200)
            view.onEnglishMode = { view.setMode(ZhuyinKeyboardView.Mode.ENGLISH) }
            view.onToggleToZhuyin = { view.setMode(ZhuyinKeyboardView.Mode.ZHUYIN) }
            test(view)
        }
    }

    private fun keys(view: ZhuyinKeyboardView, field: String): List<Pair<String, RectF>> {
        // The canvas view has no child buttons: inspect its measured hit regions.
        val entries = view.javaClass.getDeclaredField(field).apply { isAccessible = true }
            .get(view) as List<*>
        return entries.map { entry ->
            val key = requireNotNull(entry)
            val label = key.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(key) as String
            val rect = key.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(key) as RectF
            label to RectF(rect)
        }
    }

    private fun tap(view: ZhuyinKeyboardView, label: String, controls: Boolean = false) {
        val rect = keys(view, if (controls) "controlKeys" else "zhuyinKeys")
            .single { it.first == label }.second
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + 20, action, rect.centerX(), rect.centerY(), 0)
            try { assertTrue("Touch must hit $label", view.onTouchEvent(event)) }
            finally { event.recycle() }
        }
    }

    @Test fun englishLettersShiftAndRoundTripUseTheActualTouchTargets() = withKeyboard { view ->
        val typed = StringBuilder()
        view.onSymbolChar = { typed.append(it) }
        tap(view, "ABC", controls = true)
        assertEquals(ZhuyinKeyboardView.Mode.ENGLISH, view.getMode())
        val letters = keys(view, "zhuyinKeys").filter { it.first.length == 1 && it.first[0] in 'a'..'z' }
        assertEquals(('a'..'z').toSet(), letters.map { it.first.single() }.toSet())
        assertTrue(letters.all { it.second.left >= 0 && it.second.right <= view.width })
        tap(view, "q")
        tap(view, "⇧")
        tap(view, "A")
        tap(view, "z") // Shift is consumed after one letter.
        assertEquals("qAz", typed.toString())
        tap(view, "注", controls = true)
        assertEquals(ZhuyinKeyboardView.Mode.ZHUYIN, view.getMode())
        tap(view, "ABC", controls = true)
        tap(view, "a")
        assertEquals("qAza", typed.toString())
    }

    @Test fun asciiOnlyEditorsKeepEnglishAndProvideReturnFromNumbers() = withKeyboard { view ->
        view.setZhuyinModeAllowed(false)
        view.setMode(ZhuyinKeyboardView.Mode.ZHUYIN)
        assertEquals(ZhuyinKeyboardView.Mode.ENGLISH, view.getMode())
        assertFalse(keys(view, "controlKeys").any { it.first == "注" })
        view.onNumberMode = { view.setMode(ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER) }
        tap(view, "123", controls = true)
        assertEquals(ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER, view.getMode())
        tap(view, "ABC", controls = true)
        assertEquals(ZhuyinKeyboardView.Mode.ENGLISH, view.getMode())
        assertFalse(keys(view, "controlKeys").any { it.first == "注" })
    }
}
