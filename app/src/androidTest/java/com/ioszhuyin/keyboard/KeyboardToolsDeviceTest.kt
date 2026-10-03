package com.ioszhuyin.keyboard

import android.content.Intent
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class KeyboardToolsDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun keys(view: ZhuyinKeyboardView, field: String): List<Pair<String, RectF>> =
        (view.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(view) as List<*>).map {
            val key = requireNotNull(it)
            val label = key.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(key) as String
            val rect = key.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(key) as RectF
            label to RectF(rect)
        }

    private fun tap(view: ZhuyinKeyboardView, label: String, field: String = "zhuyinKeys") {
        val rect = keys(view, field).single { it.first == label }.second
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now + 20, action, rect.centerX(), rect.centerY(), 0)
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
    }

    @Test fun symbolsEmojiAndToolbarDispatchThroughMeasuredTouchRegions() {
        instrumentation.runOnMainSync {
            val view = ZhuyinKeyboardView(instrumentation.targetContext)
            view.layout(0, 0, 1080, 2200)
            val typed = StringBuilder()
            view.onSymbolChar = { typed.append(it) }
            view.setMode(ZhuyinKeyboardView.Mode.NUMBER)
            tap(view, "「"); tap(view, "」")
            view.setMode(ZhuyinKeyboardView.Mode.SYMBOL)
            for (symbol in listOf("『", "』", "【", "】")) tap(view, symbol)
            view.setMode(ZhuyinKeyboardView.Mode.HALF_WIDTH_NUMBER)
            tap(view, "?"); tap(view, "!")
            view.setMode(ZhuyinKeyboardView.Mode.EMOJI)
            tap(view, "😀"); tap(view, "❤️")
            assertEquals("「」『』【】?!😀❤️", typed.toString())
            var width = 0; var emoji = 0; var paste = 0; var settings = 0
            view.onWidthToggle = { width++ }
            view.onEmoji = { emoji++ }
            view.onPaste = { paste++ }
            view.onSettings = { settings++ }
            for (label in listOf("全形", "返回", "剪貼簿", "設定")) tap(view, label, "toolKeys")
            assertEquals(listOf(1, 1, 1, 1), listOf(width, emoji, paste, settings))
            assertTrue(keys(view, "toolKeys").all { it.second.top >= view.keyboardContentTop })
        }
    }

    @Test fun toolsCompositionAndPredictionShareOneHeaderWithoutMovingKeys() {
        instrumentation.runOnMainSync {
            val view = ZhuyinKeyboardView(instrumentation.targetContext)
            view.layout(0, 0, 1080, 2200)
            val initialKeys = keys(view, "zhuyinKeys")
            val initialTop = view.keyboardContentTop
            assertEquals(listOf("全形", "😊", "剪貼簿", "設定"), keys(view, "toolKeys").map { it.first })
            for ((words, composing) in listOf(listOf("我", "握") to true,
                emptyList<String>() to true, listOf("想", "是", "要", "的") to false)) {
                view.updateCandidateState(words, 0, -1, false, false, composing)
                view.refresh()
                assertTrue(keys(view, "toolKeys").isEmpty())
                assertEquals(initialTop, view.keyboardContentTop, 0.01f)
                assertEquals(initialKeys, keys(view, "zhuyinKeys"))
            }
            view.updateCandidateState(emptyList(), 0, -1, false, false)
            view.refresh()
            assertEquals(4, keys(view, "toolKeys").size)
            assertEquals(initialKeys, keys(view, "zhuyinKeys"))
        }
    }

    @Test fun firstBackspaceDismissesPredictionAndConsumesHoldUntilRelease() {
        instrumentation.runOnMainSync {
            // Exercise the IME's actual down/up handlers without attaching an editor:
            // dismissing suggestions must not require an InputConnection at all.
            val ime = IOSZhuyinIME()
            val view = ZhuyinKeyboardView(instrumentation.targetContext)
            view.layout(0, 0, 1080, 2200)
            fun field(name: String) = ime.javaClass.getDeclaredField(name).apply { isAccessible = true }
            fun call(name: String) = ime.javaClass.getDeclaredMethod(name).apply { isAccessible = true }.invoke(ime)
            field("keyboardView").set(ime, view)
            field("showingNextWordSuggestions").setBoolean(ime, true)
            field("nextWordCandidates").set(ime, setOf("想"))
            field("allCandidates").set(ime, listOf("想"))
            field("lastCommittedWord").set(ime, "我")
            call("syncKeyboardView")
            val repeater = field("backspaceRepeater").get(ime)
            val held = repeater.javaClass.getDeclaredField("held").apply { isAccessible = true }
            try {
                call("handleBackspaceDown")
                assertFalse(field("showingNextWordSuggestions").getBoolean(ime))
                assertTrue((field("allCandidates").get(ime) as List<*>).isEmpty())
                assertEquals(4, keys(view, "toolKeys").size)
                assertFalse(held.getBoolean(repeater))
                call("handleBackspaceDown") // Physical-key repeat from the same contact.
                assertFalse(held.getBoolean(repeater))
                call("handleBackspaceUp")
                call("handleBackspaceDown")
                assertTrue(held.getBoolean(repeater))
            } finally { call("handleBackspaceUp") }
        }
    }

    @Test fun restrictedEditorsCannotEnterEmojiOrUseClipboardTools() {
        instrumentation.runOnMainSync {
            val view = ZhuyinKeyboardView(instrumentation.targetContext)
            view.layout(0, 0, 1080, 2200)
            view.setMode(ZhuyinKeyboardView.Mode.EMOJI)
            view.setZhuyinModeAllowed(false)
            assertEquals(ZhuyinKeyboardView.Mode.ENGLISH, view.getMode())
            assertEquals(listOf("設定"), keys(view, "toolKeys").map { it.first })
            view.setMode(ZhuyinKeyboardView.Mode.EMOJI)
            assertEquals(ZhuyinKeyboardView.Mode.ENGLISH, view.getMode())
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    @Test fun publicSettingsPresetsRefreshSlidersAndChangeKeyboardGeometry() {
        val context = instrumentation.targetContext
        val prefs = KeyboardMetrics.prefs(context)
        val previous = prefs.all.toMap()
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        try {
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                views.filterIsInstance<Button>().single { it.text == "鍵盤高度與外觀" }.performClick()
                val label = views.filterIsInstance<TextView>().single { it.text.startsWith("按鍵高度:") }
                val slider = (label.parent as ViewGroup).getChildAt(1) as SeekBar
                assertEquals(View.VISIBLE, (label.parent.parent as View).visibility)
                val keyboard = ZhuyinKeyboardView(activity)
                keyboard.layout(0, 0, 1080, 2200)
                views.filterIsInstance<Button>().single { it.text == "Pixel 預設" }.performClick()
                keyboard.refresh()
                val pixel = keys(keyboard, "zhuyinKeys").first().second.height()
                assertEquals("按鍵高度: 45.0", label.text.toString())
                assertEquals(110, slider.progress)
                views.filterIsInstance<Button>().single { it.text == "iOS 預設" }.performClick()
                keyboard.refresh()
                val ios = keys(keyboard, "zhuyinKeys").first().second.height()
                assertEquals("按鍵高度: 47.0", label.text.toString())
                assertTrue(ios > pixel)
                val bottomBefore = keys(keyboard, "controlKeys").first().second.bottom
                prefs.edit().putFloat(KeyboardMetrics.KEY_BOTTOM_PADDING, 30f).commit()
                keyboard.refresh()
                assertTrue(keys(keyboard, "controlKeys").first().second.bottom < bottomBefore)
                prefs.edit().putFloat(KeyboardMetrics.KEY_KEY_HEIGHT, 70f)
                    .putFloat(KeyboardMetrics.KEY_BOTTOM_PADDING, 50f)
                    .putFloat(KeyboardMetrics.KEY_VERTICAL_GAP, 14f)
                    .putFloat(KeyboardMetrics.KEY_CANDIDATE_BAR_HEIGHT, 64f).commit()
                keyboard.updateCandidateState(listOf("我", "我們"), 0, -1, false, false)
                keyboard.layout(0, 0, 2400, 900)
                keyboard.refresh()
                assertTrue("Keep the editor visible", keyboard.keyboardContentTop >= 269f)
                for (field in listOf("zhuyinKeys", "controlKeys", "toolKeys")) {
                    assertTrue("$field must fit a short landscape window", keys(keyboard, field).all {
                        it.second.top >= 0f && it.second.bottom <= 900.1f && it.second.height() > 0f
                    })
                }
            }
        } finally {
            instrumentation.runOnMainSync {
                activity.finish()
                val edit = prefs.edit().clear()
                for ((key, value) in previous) when (value) {
                    is Float -> edit.putFloat(key, value)
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                }
                edit.commit()
            }
        }
    }
}
