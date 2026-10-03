package com.ioszhuyin.keyboard

import org.junit.Assert.*
import org.junit.Test

class SpaceCursorGestureTest {
    private class Rig {
        var timer: Runnable? = null
        var delay = 0L
        var starts = 0
        var available = true
        val moves = mutableListOf<Int>()
        val gesture = SpaceCursorGesture(500, 8f, 12f,
            schedule = { task, millis -> timer = task; delay = millis },
            unschedule = { task -> if (timer == task) timer = null },
            activate = { starts++; available }, moveCursor = moves::add, changed = {})
        fun hold() { val task = timer; timer = null; task?.run() }
    }

    @Test fun shortTapPreservesSpaceAndRemovesTimer() {
        val r = Rig()
        r.gesture.press(100f, 50f)
        assertEquals(500L, r.delay)
        assertTrue(r.gesture.release())
        assertNull(r.timer)
        assertEquals(0, r.starts)
    }

    @Test fun holdWithoutMovementDoesNotTypeOrMove() {
        val r = Rig()
        r.gesture.press(100f, 50f)
        r.hold()
        assertTrue(r.gesture.active)
        assertFalse(r.gesture.release())
        assertTrue(r.moves.isEmpty())
        assertEquals(1, r.starts)
    }

    @Test fun activeDragAccumulatesSubstepsAndReversesOutsideSpaceKey() {
        val r = Rig()
        r.gesture.press(100f, 50f)
        r.hold()
        r.gesture.move(105f, 50f)
        assertTrue(r.moves.isEmpty())
        r.gesture.move(125f, 90f)
        r.gesture.move(88f, 90f)
        assertEquals(listOf(2, -3), r.moves)
        assertFalse(r.gesture.release())
    }

    @Test fun prematureOrVerticalSwipeCannotActivateButStillCountsAsTap() {
        // The view only types the space when the release also lands on the space key.
        for ((x, y) in listOf(120f to 50f, 100f to 70f)) {
            val r = Rig()
            r.gesture.press(100f, 50f)
            r.gesture.move(x, y)
            assertNull(r.timer)
            r.hold()
            r.gesture.move(100f, 50f)
            assertTrue(r.gesture.release())
            assertEquals(0, r.starts)
            assertTrue(r.moves.isEmpty())
        }
    }

    @Test fun smallPreHoldJitterDoesNotMoveCursorOnActivation() {
        val r = Rig()
        r.gesture.press(100f, 50f)
        r.gesture.move(107f, 50f)
        r.hold()
        r.gesture.move(118f, 50f)
        assertTrue(r.moves.isEmpty())
        r.gesture.move(119f, 50f)
        assertEquals(listOf(1), r.moves)
    }

    @Test fun cancelledGestureCannotFireAfterEditorChange() {
        val r = Rig()
        r.gesture.press(100f, 50f)
        val stale = r.timer!!
        r.gesture.cancel()
        stale.run()
        assertEquals(0, r.starts)
        assertFalse(r.gesture.release())
        r.gesture.press(100f, 50f)
        r.hold()
        r.gesture.cancel()
        r.gesture.move(150f, 50f)
        assertTrue(r.moves.isEmpty())
    }

    @Test fun rejectedActivationDoesNotFallBackToSpace() {
        val r = Rig()
        r.available = false
        r.gesture.press(100f, 50f)
        r.hold()
        assertFalse(r.gesture.active)
        assertFalse(r.gesture.release())
    }
}
