package com.ioszhuyin.keyboard

import android.content.Intent
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.ViewGroup
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

class SpaceCursorDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    private fun withKeyboard(test: (ZhuyinKeyboardView, RectF) -> Unit) {
        val activity = instrumentation.startActivitySync(Intent(instrumentation.targetContext,
            EditorProbeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        lateinit var view: ZhuyinKeyboardView
        lateinit var space: RectF
        try {
            instrumentation.runOnMainSync {
                view = ZhuyinKeyboardView(activity)
                activity.addContentView(view, ViewGroup.LayoutParams(-1, -1))
            }
            instrumentation.waitForIdleSync()
            instrumentation.runOnMainSync {
                val controls = view.javaClass.getDeclaredField("controlKeys").apply { isAccessible = true }
                    .get(view) as List<*>
                val key = controls.first { entry ->
                    entry!!.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(entry) == "空白"
                }!!
                space = RectF(key.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(key) as RectF)
            }
            test(view, space)
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
        }
    }

    private fun touch(view: ZhuyinKeyboardView, action: Int, x: Float, y: Float) {
        instrumentation.runOnMainSync {
            val now = SystemClock.uptimeMillis()
            val event = MotionEvent.obtain(now, now, action, x, y, 0)
            try { assertTrue(view.onTouchEvent(event)) } finally { event.recycle() }
        }
    }

    @Test fun actualSpaceHitRegionDistinguishesTapHoldAndHorizontalDrag() = withKeyboard { view, space ->
        var spaces = 0
        var starts = 0
        val moves = mutableListOf<Int>()
        instrumentation.runOnMainSync {
            view.onSpace = { spaces++ }
            view.onCursorGestureStart = { starts++; true }
            view.onCursorMove = { moves += it }
        }
        val x = space.centerX()
        val y = space.centerY()
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        touch(view, MotionEvent.ACTION_UP, x, y)
        touch(view, MotionEvent.ACTION_DOWN, x, y)
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        instrumentation.waitForIdleSync()
        val step = 12f * view.resources.displayMetrics.density
        touch(view, MotionEvent.ACTION_MOVE, x + step * 2.1f, y)
        touch(view, MotionEvent.ACTION_MOVE, x - step * 1.1f, y)
        touch(view, MotionEvent.ACTION_UP, x - step * 1.1f, y)
        assertEquals(1, spaces)
        assertEquals(1, starts)
        assertEquals(listOf(2, -3), moves)
    }

    @Test fun hidingKeyboardBeforeTimeoutCancelsActivationAndSpace() = withKeyboard { view, space ->
        var starts = 0
        var spaces = 0
        instrumentation.runOnMainSync {
            view.onCursorGestureStart = { starts++; true }
            view.onSpace = { spaces++ }
        }
        touch(view, MotionEvent.ACTION_DOWN, space.centerX(), space.centerY())
        instrumentation.runOnMainSync { view.cancelCursorGesture() }
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout().toLong() + 100)
        touch(view, MotionEvent.ACTION_UP, space.centerX(), space.centerY())
        assertEquals(0, starts)
        assertEquals(0, spaces)
    }
}
