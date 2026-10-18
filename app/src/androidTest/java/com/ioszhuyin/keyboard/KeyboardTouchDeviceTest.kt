package com.ioszhuyin.keyboard

import android.graphics.RectF
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test

/** Regression checks through the real view, layout and multi-pointer dispatch. */
class KeyboardTouchDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private var downTime = 0L

    private fun withKeyboard(test: (ZhuyinKeyboardView) -> Unit) {
        instrumentation.runOnMainSync {
            val view = ZhuyinKeyboardView(instrumentation.targetContext)
            view.layout(0, 0, 1080, 1800)
            try { test(view) } finally {
                touch(view, MotionEvent.ACTION_CANCEL, Finger(0, 0f, 0f))
            }
        }
    }

    private data class Finger(val id: Int, val x: Float, val y: Float)

    private fun key(view: ZhuyinKeyboardView, label: String, controls: Boolean = false): RectF {
        val entries = view.javaClass.getDeclaredField(if (controls) "controlKeys" else "zhuyinKeys")
            .apply { isAccessible = true }.get(view) as List<*>
        val entry = entries.single {
            it!!.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(it) == label
        }!!
        return RectF(entry.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(entry) as RectF)
    }

    private fun finger(id: Int, rect: RectF) = Finger(id, rect.centerX(), rect.centerY())

    private fun touch(view: ZhuyinKeyboardView, action: Int, vararg fingers: Finger) {
        val now = SystemClock.uptimeMillis()
        if (action == MotionEvent.ACTION_DOWN) downTime = now
        val properties = fingers.map { finger ->
            MotionEvent.PointerProperties().apply { id = finger.id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coordinates = fingers.map { finger ->
            MotionEvent.PointerCoords().apply { x = finger.x; y = finger.y; pressure = 1f; size = 1f }
        }.toTypedArray()
        val event = MotionEvent.obtain(downTime, now, action, fingers.size, properties, coordinates,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        try { view.onTouchEvent(event) } finally { event.recycle() }
    }

    @Test fun rollingFromFirstToneResolvesTheNextKeyAfterChangingPage() {
        for (spaceLiftsFirst in listOf(true, false)) withKeyboard { view ->
            val events = mutableListOf<String>()
            val next = finger(1, key(view, "ㄅ"))
            view.setFinalPage(true)
            view.refresh()
            view.onSpace = {
                events += "一聲"
                view.setFinalPage(false)
                view.refresh()
            }
            view.onKeyPress = { events += it }
            val space = finger(0, key(view, "一聲", controls = true))
            touch(view, MotionEvent.ACTION_DOWN, space)
            touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), space, next)
            assertEquals(listOf("一聲"), events)
            if (spaceLiftsFirst) {
                touch(view, MotionEvent.ACTION_POINTER_UP, space, next)
                touch(view, MotionEvent.ACTION_UP, next)
            } else {
                touch(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), space, next)
                touch(view, MotionEvent.ACTION_UP, space)
            }
            assertEquals("Space must switch pages before resolving the next finger", listOf("一聲", "ㄅ"), events)
        }
    }

    @Test fun slidingAboveTheKeyboardCancelsInsteadOfClampingToTheFirstRow() = withKeyboard { view ->
        val typed = mutableListOf<String>()
        view.onKeyPress = { typed += it }
        val down = finger(0, key(view, "ㄅ"))
        val toolbar = view.javaClass.getDeclaredField("toolbarRect").apply { isAccessible = true }.get(view) as RectF
        // Cover both blank space inside the view and coordinates outside the view.
        for (upY in listOf(toolbar.top - 20f, -60f)) {
            assertTrue(upY < toolbar.top)
            val up = down.copy(y = upY)
            touch(view, MotionEvent.ACTION_DOWN, down)
            touch(view, MotionEvent.ACTION_MOVE, up)
            touch(view, MotionEvent.ACTION_UP, up)
        }
        assertTrue("Dragging into the editor must not type a first-row key", typed.isEmpty())
    }

    @Test fun shortRollOutsideTheViewCancelsEvenInsideTheHoldRadius() = withKeyboard { view ->
        val typed = mutableListOf<String>()
        view.onKeyPress = { typed += it }
        val down = finger(0, key(view, "ㄅ")).copy(x = 2f)
        touch(view, MotionEvent.ACTION_DOWN, down)
        touch(view, MotionEvent.ACTION_UP, down.copy(x = -1f))
        assertTrue("The hold radius must not override the keyboard boundary", typed.isEmpty())
    }

    @Test fun ordinaryRollSlideAndOverlappingLettersStillType() = withKeyboard { view ->
        view.setMode(ZhuyinKeyboardView.Mode.ENGLISH)
        val typed = mutableListOf<String>()
        view.onSymbolChar = { typed += it }
        val q = finger(0, key(view, "q"))
        val t = finger(1, key(view, "t"))
        touch(view, MotionEvent.ACTION_DOWN, q)
        touch(view, MotionEvent.ACTION_UP, q.copy(x = q.x + 2f))
        touch(view, MotionEvent.ACTION_DOWN, q)
        touch(view, MotionEvent.ACTION_MOVE, t.copy(id = 0))
        touch(view, MotionEvent.ACTION_UP, t.copy(id = 0))
        touch(view, MotionEvent.ACTION_DOWN, q)
        touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, t)
        touch(view, MotionEvent.ACTION_POINTER_UP, q, t)
        touch(view, MotionEvent.ACTION_UP, t)
        assertEquals(listOf("q", "t", "q", "t"), typed)
    }

    @Test fun overlappingLettersKeepDownOrderWithEitherLiftOrder() {
        for (firstLiftsFirst in listOf(true, false)) withKeyboard { view ->
            view.setMode(ZhuyinKeyboardView.Mode.ENGLISH)
            val typed = mutableListOf<String>()
            var feedback = 0
            view.onSymbolChar = { typed += it }
            view.onKeyFeedback = { feedback++ }
            val q = finger(0, key(view, "q"))
            val t = finger(1, key(view, "t"))
            touch(view, MotionEvent.ACTION_DOWN, q)
            touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, t)
            if (firstLiftsFirst) {
                touch(view, MotionEvent.ACTION_POINTER_UP, q, t)
                touch(view, MotionEvent.ACTION_UP, t)
            } else {
                touch(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, t)
                touch(view, MotionEvent.ACTION_UP, q)
            }
            assertEquals("Down order must survive reversed releases", listOf("q", "t"), typed)
            assertEquals("One feedback per finger down", 2, feedback)
        }
    }

    @Test fun rollingConsonantSwitchesPageBeforeResolvingTheNextFinger() {
        for (firstLiftsFirst in listOf(true, false)) withKeyboard { view ->
            val typed = mutableListOf<String>()
            val first = finger(0, key(view, "ㄅ"))
            view.setFinalPage(true)
            view.refresh()
            val next = finger(1, key(view, "ㄚ"))
            view.setFinalPage(false)
            view.refresh()
            view.onKeyPress = {
                typed += it
                view.setFinalPage(true)
                view.refresh()
            }
            touch(view, MotionEvent.ACTION_DOWN, first)
            touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, next)
            if (firstLiftsFirst) {
                touch(view, MotionEvent.ACTION_POINTER_UP, first, next)
                touch(view, MotionEvent.ACTION_UP, next)
            } else {
                touch(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), first, next)
                touch(view, MotionEvent.ACTION_UP, first)
            }
            assertEquals("New finger must target the updated final page", listOf("ㄅ", "ㄚ"), typed)
        }
    }

    @Test fun rolloverUsesLatestPositionAndDoesNotReviveCancelledTouches() {
        for (slideOutside in listOf(false, true)) withKeyboard { view ->
            view.setMode(ZhuyinKeyboardView.Mode.ENGLISH)
            val typed = mutableListOf<String>()
            view.onSymbolChar = { typed += it }
            val q = finger(0, key(view, "q"))
            val moved = if (slideOutside) q.copy(y = -10f) else finger(0, key(view, "e"))
            val t = finger(1, key(view, "t"))
            touch(view, MotionEvent.ACTION_DOWN, q)
            // Deliberately no MOVE event: POINTER_DOWN carries the newest old-finger coordinates.
            touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), moved, t)
            touch(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), moved, t)
            touch(view, MotionEvent.ACTION_UP, moved)
            assertEquals(if (slideOutside) listOf("t") else listOf("e", "t"), typed)
        }
    }

    @Test fun threeFingerRolloverAndBackspaceNeverResubmitResolvedLetters() = withKeyboard { view ->
        view.setMode(ZhuyinKeyboardView.Mode.ENGLISH)
        val typed = StringBuilder()
        var deletes = 0
        view.onSymbolChar = { typed.append(it) }
        view.onBackspace = { deletes++; if (typed.isNotEmpty()) typed.deleteCharAt(typed.lastIndex) }
        val q = finger(0, key(view, "q"))
        val t = finger(1, key(view, "t"))
        val p = finger(2, key(view, "p"))
        touch(view, MotionEvent.ACTION_DOWN, q)
        touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, t)
        touch(view, MotionEvent.ACTION_POINTER_DOWN or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, t, p)
        touch(view, MotionEvent.ACTION_POINTER_UP or (2 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, t, p)
        touch(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, t)
        touch(view, MotionEvent.ACTION_UP, q)
        assertEquals("qtp", typed.toString())
        val backspace = finger(1, key(view, "⌫"))
        touch(view, MotionEvent.ACTION_DOWN, q)
        touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, backspace)
        touch(view, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, backspace)
        touch(view, MotionEvent.ACTION_UP, q)
        assertEquals("qtp", typed.toString())
        assertEquals(1, deletes)
    }

    @Test fun unrelatedSecondFingerDoesNotCommitAnUnfinishedLetter() = withKeyboard { view ->
        view.setMode(ZhuyinKeyboardView.Mode.ENGLISH)
        val typed = mutableListOf<String>()
        view.onSymbolChar = { typed += it }
        val q = finger(0, key(view, "q"))
        val outside = Finger(1, -30f, -30f)
        touch(view, MotionEvent.ACTION_DOWN, q)
        touch(view, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), q, outside)
        assertTrue(typed.isEmpty())
        touch(view, MotionEvent.ACTION_CANCEL, q, outside)
        assertTrue(typed.isEmpty())
    }

    @Test fun blankAboveStaggeredGKeyChoosesTheClosestVisibleKey() = withKeyboard { view ->
        val typed = mutableListOf<String>()
        view.onKeyPress = { typed += it }
        val g = key(view, "ㄍ")
        val point = Finger(0, g.centerX(), g.top - 1f)
        touch(view, MotionEvent.ACTION_DOWN, point)
        touch(view, MotionEvent.ACTION_UP, point)
        assertEquals("The blank just above ㄍ should belong to ㄍ, not the offset first row", listOf("ㄍ"), typed)
    }

    @Test fun topEdgeInsideBackspaceAlwaysDeletes() = withKeyboard { view ->
        var deletes = 0
        val typed = mutableListOf<String>()
        view.onBackspace = { deletes++ }
        view.onKeyPress = { typed += it }
        val backspace = key(view, "⌫")
        val point = Finger(0, backspace.centerX(), backspace.top + 1f)
        touch(view, MotionEvent.ACTION_DOWN, point)
        touch(view, MotionEvent.ACTION_UP, point)
        assertEquals("A touch inside the drawn backspace must delete", 1, deletes)
        assertTrue(typed.isEmpty())
    }

    @Test fun gapsBetweenCandidateCardsSelectANeighbor() = withKeyboard { view ->
        val selected = mutableListOf<Int>()
        view.onCandidatePress = { selected += it }
        view.updateCandidateState(listOf("手感", "手機", "手指"), 0, 0, false, false)
        view.refresh()
        val cells = view.javaClass.getDeclaredField("candidateCells").apply { isAccessible = true }.get(view) as List<*>
        val rects = cells.map { cell ->
            cell!!.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(cell) as RectF
        }
        val point = Finger(0, (rects[0].right + rects[1].left) / 2f, rects[0].centerY())
        touch(view, MotionEvent.ACTION_DOWN, point)
        touch(view, MotionEvent.ACTION_UP, point)
        assertEquals("A divider tap should select exactly one adjacent candidate", 1, selected.size)
        assertTrue(selected.single() in 0..1)
    }

    @Test fun visibleKeyEdgesAndDividersAreCoveredInEveryMode() = withKeyboard { view ->
        val hit = view.javaClass.getDeclaredMethod("findTarget", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        for (mode in ZhuyinKeyboardView.Mode.values()) {
            for (finalPage in if (mode == ZhuyinKeyboardView.Mode.ZHUYIN) listOf(false, true) else listOf(false)) {
                view.setMode(mode)
                view.setFinalPage(finalPage)
                view.refresh()
                val entries = view.javaClass.getDeclaredField("zhuyinKeys").apply { isAccessible = true }.get(view) as List<*>
                val keys = entries.map { item ->
                    val entry = requireNotNull(item)
                    val label = entry.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(entry) as String
                    val rect = entry.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(entry) as RectF
                    label to RectF(rect)
                }
                for ((label, rect) in keys.filter { it.first.isNotEmpty() }) {
                    for (x in listOf(rect.left + 1f, rect.centerX(), rect.right - 1f)) {
                        for (y in listOf(rect.top + 1f, rect.centerY(), rect.bottom - 1f)) {
                            val target = requireNotNull(hit.invoke(view, x, y)) { "$mode $label missed at $x/$y" }
                            assertEquals("$mode $label must own every point inside its visible box", label,
                                target.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(target))
                        }
                    }
                }
                for (row in keys.groupBy { it.second.top }.values) {
                    for ((left, right) in row.zipWithNext()) {
                        if (left.first.isEmpty() || right.first.isEmpty()) continue
                        val target = requireNotNull(hit.invoke(view, (left.second.right + right.second.left) / 2f, left.second.centerY()))
                        val label = target.javaClass.getDeclaredField("label").apply { isAccessible = true }.get(target)
                        assertTrue("Divider must belong to one adjacent key", label == left.first || label == right.first)
                    }
                }
            }
        }
    }

    @Test fun candidateDividersKeepSwipeBehaviorAndLargeBlankPanelStaysEmpty() = withKeyboard { view ->
        var selected = 0
        var swipes = 0
        view.onCandidatePress = { selected++ }
        view.onCandidatePageSwipe = { swipes++ }
        view.updateCandidateState(listOf("手感", "手機", "手指"), 0, 0, false, false)
        view.refresh()
        val cells = view.javaClass.getDeclaredField("candidateCells").apply { isAccessible = true }.get(view) as List<*>
        val rects = cells.map { cell -> cell!!.javaClass.getDeclaredField("rect").apply { isAccessible = true }.get(cell) as RectF }
        val down = Finger(0, (rects[0].right + rects[1].left) / 2f, rects[0].centerY())
        val moved = down.copy(x = down.x + 100f)
        touch(view, MotionEvent.ACTION_DOWN, down)
        touch(view, MotionEvent.ACTION_MOVE, moved)
        touch(view, MotionEvent.ACTION_UP, moved)
        assertEquals(1, swipes)
        assertEquals(0, selected)
        view.updateCandidateState(listOf("手感", "手機", "手指"), 0, 0, true, true)
        view.refresh()
        val bar = view.javaClass.getDeclaredField("candidateBarRect").apply { isAccessible = true }.get(view) as RectF
        val blank = Finger(0, bar.centerX(), bar.bottom - 4f)
        touch(view, MotionEvent.ACTION_DOWN, blank)
        touch(view, MotionEvent.ACTION_UP, blank)
        assertEquals("Unused expanded panel must not guess a candidate", 0, selected)
    }

    @Test fun candidateLowerMarginDoesNotTypePButDrawnPStillWorks() = withKeyboard { view ->
        val selected = mutableListOf<Int>()
        val typed = mutableListOf<String>()
        view.onCandidatePress = { selected += it }
        view.onKeyPress = { typed += it }
        view.updateCandidateState(listOf("手感", "手", "首", "守"), 0, 0, true, false)
        view.refresh()
        val bar = view.javaClass.getDeclaredField("candidateBarRect").apply { isAccessible = true }.get(view) as RectF
        val p = key(view, "ㄆ")
        val hit = view.javaClass.getDeclaredMethod("findTarget", Float::class.javaPrimitiveType, Float::class.javaPrimitiveType)
            .apply { isAccessible = true }
        val above = requireNotNull(hit.invoke(view, p.centerX(), bar.centerY()))
        val expectedCandidate = above.javaClass.getDeclaredField("idx").apply { isAccessible = true }.getInt(above)
        for (y in listOf(bar.bottom + 1f, p.top - 1f)) {
            val point = Finger(0, p.centerX(), y)
            touch(view, MotionEvent.ACTION_DOWN, point)
            touch(view, MotionEvent.ACTION_UP, point)
        }
        assertTrue("Candidate lower margin must never type ㄆ", typed.isEmpty())
        assertEquals(listOf(expectedCandidate, expectedCandidate), selected)
        val edgeDown = Finger(0, p.centerX(), bar.bottom - 1f)
        val slightRoll = Finger(0, p.centerX(), p.top + 1f)
        touch(view, MotionEvent.ACTION_DOWN, edgeDown)
        touch(view, MotionEvent.ACTION_MOVE, slightRoll)
        touch(view, MotionEvent.ACTION_UP, slightRoll)
        assertEquals("A short roll after touching the candidate stays a candidate", 3, selected.size)
        assertTrue(typed.isEmpty())
        val insideP = Finger(0, p.centerX(), p.top + 1f)
        touch(view, MotionEvent.ACTION_DOWN, insideP)
        touch(view, MotionEvent.ACTION_UP, insideP)
        assertEquals("The visible ㄆ key must remain usable", listOf("ㄆ"), typed)
    }
}
