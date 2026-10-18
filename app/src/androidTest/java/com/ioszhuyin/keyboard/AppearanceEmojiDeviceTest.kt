package com.ioszhuyin.keyboard

import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.TextView
import android.widget.FrameLayout
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class AppearanceEmojiDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    /** Opt-in native renderer captures for visual QA, without changing normal test runs. */
    private fun capture(view: ZhuyinKeyboardView, name: String) {
        if (InstrumentationRegistry.getArguments().getString("appearanceScreenshots") != "true") return
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val top = view.keyboardContentTop.toInt().coerceIn(0, view.height - 1)
        val cropped = Bitmap.createBitmap(bitmap, 0, top, view.width, view.height - top)
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "appearance-preview")
        directory.mkdirs()
        File(directory, "$name.png").outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
    }

    private fun keys(view: ZhuyinKeyboardView, field: String): List<Pair<String, RectF>> {
        val entries = view.javaClass.getDeclaredField(field).apply { isAccessible = true }.get(view) as List<*>
        return entries.map { item ->
            val entry = requireNotNull(item)
            val label = entry.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(entry) as String
            label to RectF(entry.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(entry) as RectF)
        }
    }

    private fun tap(view: ZhuyinKeyboardView, label: String, field: String = "zhuyinKeys") {
        val rect = keys(view, field).single { it.first == label }.second
        val time = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(time, time + 20, action, rect.centerX(), rect.centerY(), 0)
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
    }

    @Test fun allCategoriesAndPagesCanBeTypedEvenWhenPredictionsExist() {
        instrumentation.runOnMainSync {
            val view = ZhuyinKeyboardView(instrumentation.targetContext)
            view.layout(0, 0, 1080, 1800)
            view.updateCandidateState(listOf("你好", "謝謝"), 0, 0, true, true)
            view.setMode(ZhuyinKeyboardView.Mode.EMOJI)
            val typed = mutableListOf<String>()
            var deletes = 0
            var spaces = 0
            var returns = 0
            view.onSymbolChar = { typed += it }
            view.onBackspace = { deletes++ }
            view.onSpace = { spaces++ }
            view.onReturn = { returns++ }
            view.onEmoji = { view.setMode(ZhuyinKeyboardView.Mode.ZHUYIN) }
            assertEquals(EmojiCatalog.categories.map { it.label }, keys(view, "toolKeys").map { it.first })
            for (category in EmojiCatalog.categories) {
                tap(view, category.label, "toolKeys")
                for (page in 0 until category.pageCount) {
                    val displayed = keys(view, "zhuyinKeys").map { it.first }.filter { it.isNotEmpty() && it != "⌫" }
                    assertEquals(category.page(page), displayed)
                    if (page == 0) capture(view, "emoji-${EmojiCatalog.categories.indexOf(category)}")
                    for (emoji in displayed) {
                        tap(view, emoji)
                        assertEquals(emoji, typed.last())
                    }
                    for ((_, rect) in keys(view, "zhuyinKeys").filter { it.first.isEmpty() }) {
                        val typedBefore = typed.size
                        val deletesBefore = deletes
                        val time = SystemClock.uptimeMillis()
                        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
                            val event = MotionEvent.obtain(time, time + 20, action, rect.centerX(), rect.centerY(), 0)
                            try { view.onTouchEvent(event) } finally { event.recycle() }
                        }
                        assertEquals("${category.label} page $page: blank slot typed an emoji", typedBefore, typed.size)
                        assertEquals("${category.label} page $page: blank slot deleted text", deletesBefore, deletes)
                    }
                    tap(view, "▶", "controlKeys")
                }
                // Next wraps back to page one; previous wraps to the final page.
                assertEquals(category.page(0).first(), keys(view, "zhuyinKeys").first().first)
                tap(view, "◀", "controlKeys")
                assertEquals(category.page(category.pageCount - 1).first(), keys(view, "zhuyinKeys").first().first)
            }
            assertEquals(EmojiCatalog.categories.flatMap { it.entries }, typed)
            tap(view, "⌫")
            tap(view, keys(view, "controlKeys").single { it.first.startsWith("空白") }.first, "controlKeys")
            tap(view, "換行", "controlKeys")
            assertEquals(1, deletes)
            assertEquals(1, spaces)
            assertEquals(1, returns)
            tap(view, "返回", "controlKeys")
            assertEquals(ZhuyinKeyboardView.Mode.ZHUYIN, view.getMode())
        }
    }

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 10000
        while (SystemClock.uptimeMillis() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        fail(message)
    }

    @Test fun importingAndResettingFontRefreshesExistingSizePreview() {
        val context = instrumentation.targetContext
        val originalFont = File(context.filesDir, "keyboard-custom.ttf").takeIf { it.exists() }?.readBytes()
        val source = File.createTempFile("preview-test-", ".ttf", context.cacheDir)
        context.assets.open("bopomofo.ttf").use { input -> source.outputStream().use { input.copyTo(it) } }
        KeyboardFont.reset(context)
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var preview: TextView
        lateinit var initialTypeface: Typeface
        try {
            instrumentation.runOnMainSync {
                val views = descendants(activity.window.decorView)
                views.filterIsInstance<Button>().single { it.text == "字體大小、鍵盤高度與外觀" }.performClick()
                preview = views.filterIsInstance<TextView>().single { it.text.toString() == "ㄅ ㄆ ㄇ ㄈ　ㄧ ㄨ ㄩ　ˊ ˇ ˋ ˙" }
                initialTypeface = preview.typeface
                // Supply the document-picker result to the real import/confirmation flow.
                activity.javaClass.getDeclaredMethod("importFont", Uri::class.java).apply { isAccessible = true }
                    .invoke(activity, Uri.fromFile(source))
            }
            awaitCondition("Font import confirmation did not appear") {
                val apply = instrumentation.uiAutomation.rootInActiveWindow
                    ?.findAccessibilityNodeInfosByText("套用")?.firstOrNull { it.isClickable && it.text?.toString() == "套用" }
                apply?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
            }
            awaitCondition("Import did not refresh the existing preview") {
                var changed = false
                instrumentation.runOnMainSync {
                    changed = preview.typeface !== initialTypeface && preview.typeface === KeyboardFont.load(context)
                }
                changed
            }
            instrumentation.runOnMainSync {
                descendants(activity.window.decorView).filterIsInstance<Button>()
                    .single { it.text == "還原預設字型" }.performClick()
            }
            awaitCondition("Reset did not restore the existing preview") {
                var restored = false
                instrumentation.runOnMainSync {
                    restored = preview.typeface === initialTypeface && KeyboardFont.load(context) == null
                }
                restored
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            source.delete()
            if (originalFont == null) KeyboardFont.reset(context) else {
                val staged = KeyboardFont.stage(context, originalFont.inputStream())
                try { KeyboardFont.apply(context, staged) } finally { staged.delete() }
            }
        }
    }

    @Test fun zhuyinSizeChangesIndependentlyAndSurvivesViewRecreation() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val prefs = KeyboardMetrics.prefs(context)
            val key = KeyboardMetrics.KEY_ZHUYIN_FONT_SIZE
            val previous = if (prefs.contains(key)) prefs.getFloat(key, 28f) else null
            val bitmap = Bitmap.createBitmap(1080, 1800, Bitmap.Config.ARGB_8888)
            try {
                val view = ZhuyinKeyboardView(context)
                view.bopomofoTypeface = android.graphics.Typeface.createFromAsset(context.assets, "bopomofo.ttf")
                view.layout(0, 0, bitmap.width, bitmap.height)
                fun paintedSize(name: String): Float =
                    (view.javaClass.getDeclaredField(name).apply { isAccessible = true }.get(view) as Paint).textSize
                val candidateSize = KeyboardMetrics.current(context).candidateFontSize
                prefs.edit().putFloat(key, 16f).commit()
                view.refresh()
                view.draw(Canvas(bitmap))
                val small = paintedSize("paintZhuyinText")
                capture(view, "zhuyin-small")
                val englishSize = paintedSize("paintKeyText")
                val rects = keys(view, "zhuyinKeys")
                prefs.edit().putFloat(key, 40f).commit()
                view.refresh()
                view.draw(Canvas(bitmap))
                val large = paintedSize("paintZhuyinText")
                capture(view, "zhuyin-large")
                assertTrue("Zhuyin glyphs must actually become larger", large > small)
                assertEquals(englishSize, paintedSize("paintKeyText"), 0.01f)
                assertEquals(candidateSize, KeyboardMetrics.current(context).candidateFontSize, 0.01f)
                assertEquals("Changing glyph size must not move keys", rects, keys(view, "zhuyinKeys"))
                assertEquals(40f, KeyboardMetrics.current(context).zhuyinFontSize, 0.01f)
                val recreated = ZhuyinKeyboardView(context)
                recreated.bopomofoTypeface = view.bopomofoTypeface
                recreated.layout(0, 0, bitmap.width, bitmap.height)
                recreated.draw(Canvas(bitmap))
                val paint = recreated.javaClass.getDeclaredField("paintZhuyinText")
                    .apply { isAccessible = true }.get(recreated) as Paint
                assertEquals(large, paint.textSize, 0.01f)
                val ink = android.graphics.Rect()
                paint.getTextBounds("ㄙ", 0, 1, ink)
                assertTrue(ink.height() <= rects.first().second.height())
            } finally {
                val editor = prefs.edit()
                if (previous == null) editor.remove(key) else editor.putFloat(key, previous)
                editor.commit()
                bitmap.recycle()
            }
        }
    }

    @Test fun largerCandidatesStayInsideCardsWithoutMovingTheLetters() {
        instrumentation.runOnMainSync {
            val context = instrumentation.targetContext
            val prefs = KeyboardMetrics.prefs(context)
            val settingKeys = listOf(KeyboardMetrics.KEY_CANDIDATE_BAR_HEIGHT, KeyboardMetrics.KEY_CANDIDATE_FONT_SIZE)
            val old = settingKeys.associateWith { if (prefs.contains(it)) prefs.getFloat(it, 0f) else null }
            val bitmap = Bitmap.createBitmap(1440, 2800, Bitmap.Config.ARGB_8888)
            try {
                val phone = context.createConfigurationContext(Configuration(context.resources.configuration)
                    .apply { densityDpi = 600 })
                val view = ZhuyinKeyboardView(phone)
                view.bopomofoTypeface = Typeface.createFromAsset(context.assets, "bopomofo.ttf")
                prefs.edit().putFloat(settingKeys[0], 40f).putFloat(settingKeys[1], 17f).commit()
                view.layout(0, 0, bitmap.width, bitmap.height)
                val originalKeys = keys(view, "zhuyinKeys")
                prefs.edit().putFloat(settingKeys[0], 52f).putFloat(settingKeys[1], 22f).commit()
                for (labels in listOf(listOf("手感", "手", "首", "守"),
                    listOf("候選詞很長時也必須完整留在自己的卡片範圍裡"))) {
                    view.updateCandidateState(labels, 0, 0, false, false)
                    view.refresh()
                    view.draw(Canvas(bitmap))
                    assertEquals(originalKeys, keys(view, "zhuyinKeys"))
                    val cells = view.javaClass.getDeclaredField("candidateCells").apply { isAccessible = true }.get(view) as List<*>
                    val last = requireNotNull(cells.last())
                    val rect = last.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(last) as RectF
                    val paint = view.javaClass.getDeclaredField("paintCandidateText").apply { isAccessible = true }.get(view) as Paint
                    assertTrue(paint.measureText(labels.last()) <= rect.width() - 8f * phone.resources.displayMetrics.density + 0.1f)
                    assertTrue(paint.descent() - paint.ascent() <= rect.height())
                    if (labels.size > 1) capture(view, "candidates-large")
                }
            } finally {
                val edit = prefs.edit()
                for ((key, value) in old) if (value == null) edit.remove(key) else edit.putFloat(key, value)
                edit.commit()
                bitmap.recycle()
            }
        }
    }

    @Test fun visiblePhoneKeyboardRespondsToSizeChangesWithoutReopening() {
        val context = instrumentation.targetContext
        val prefs = KeyboardMetrics.prefs(context)
        val key = KeyboardMetrics.KEY_ZHUYIN_FONT_SIZE
        val previous = if (prefs.contains(key)) prefs.getFloat(key, 28f) else null
        val activity = instrumentation.startActivitySync(Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        val bitmap = Bitmap.createBitmap(1440, 2800, Bitmap.Config.ARGB_8888)
        try {
            instrumentation.runOnMainSync {
                // Same width/density as the connected S24 Ultra (384 dp wide).
                val phoneContext = context.createConfigurationContext(Configuration(context.resources.configuration)
                    .apply { densityDpi = 600 })
                val view = ZhuyinKeyboardView(phoneContext)
                view.bopomofoTypeface = Typeface.createFromAsset(context.assets, "bopomofo.ttf")
                val host = FrameLayout(activity)
                host.addView(view, FrameLayout.LayoutParams(bitmap.width, bitmap.height))
                activity.setContentView(host)
                view.layout(0, 0, bitmap.width, bitmap.height)
                assertTrue(view.isAttachedToWindow)
                val keyRect = keys(view, "zhuyinKeys").single { it.first == "ㄙ" }.second
                fun inkHeight(size: Float): Int {
                    // No refresh(): exercise the attached keyboard's preference listener.
                    prefs.edit().putFloat(key, size).commit()
                    view.draw(Canvas(bitmap))
                    val paint = view.javaClass.getDeclaredField("paintZhuyinText")
                        .apply { isAccessible = true }.get(view) as Paint
                    var minY = bitmap.height
                    var maxY = -1
                    for (y in keyRect.top.toInt() until keyRect.bottom.toInt()) {
                        for (x in keyRect.left.toInt() until keyRect.right.toInt()) {
                            if (bitmap.getPixel(x, y) == paint.color) {
                                minY = minOf(minY, y)
                                maxY = maxOf(maxY, y)
                            }
                        }
                    }
                    assertTrue("Visible glyph must fit inside the key", maxY > minY &&
                        minY > keyRect.top + 2 && maxY < keyRect.bottom - 2)
                    capture(view, "phone-size-${size.toInt()}")
                    return maxY - minY + 1
                }
                val small = inkHeight(16f)
                val normal = inkHeight(28f)
                val large = inkHeight(40f)
                assertTrue("16 to 28 must enlarge actual key ink", normal > small)
                assertTrue("28 to 40 must visibly enlarge actual key ink: $normal -> $large", large > normal * 1.25f)
                assertEquals(keyRect, keys(view, "zhuyinKeys").single { it.first == "ㄙ" }.second)
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            val edit = prefs.edit()
            if (previous == null) edit.remove(key) else edit.putFloat(key, previous)
            edit.commit()
            bitmap.recycle()
        }
    }
}
