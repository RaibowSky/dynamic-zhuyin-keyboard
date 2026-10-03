package com.ioszhuyin.keyboard

import kotlin.math.abs

/** Owns one space-key contact, including cancelled drags that must not type a space. */
internal class SpaceCursorGesture(
    private val holdMillis: Long,
    private val touchSlop: Float,
    private val stepPixels: Float,
    private val schedule: (Runnable, Long) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val activate: () -> Boolean,
    private val moveCursor: (Int) -> Unit,
    private val changed: () -> Unit
) {
    var ownsTouch = false
        private set
    var active = false
        private set
    private var cancelled = false
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var remainder = 0f
    private var pending: Runnable? = null

    fun press(x: Float, y: Float) {
        cancel()
        ownsTouch = true
        startX = x
        startY = y
        lastX = x
        val task = Runnable {
            pending = null
            if (ownsTouch && !cancelled) {
                active = activate()
                if (!active) cancelled = true
                remainder = 0f
                changed()
            }
        }
        pending = task
        schedule(task, holdMillis)
    }

    fun move(x: Float, y: Float) {
        if (!ownsTouch || cancelled) return
        if (!active) {
            // Early drift only rules out the long press; the caller still decides
            // whether the release lands on the space key and counts as a tap.
            if (pending != null && (abs(x - startX) > touchSlop || abs(y - startY) > touchSlop)) {
                pending?.let(unschedule)
                pending = null
                changed()
            }
            lastX = x
            return
        }
        remainder += x - lastX
        lastX = x
        val steps = (remainder / stepPixels).toInt()
        if (steps != 0) {
            remainder -= steps * stepPixels
            moveCursor(steps)
        }
    }

    /** Only an ordinary short tap may trigger the existing space/first-tone action. */
    fun release(): Boolean {
        val tap = ownsTouch && !active && !cancelled
        cancel()
        return tap
    }

    fun cancel() {
        pending?.let(unschedule)
        pending = null
        ownsTouch = false
        active = false
        cancelled = false
        remainder = 0f
        changed()
    }
}
