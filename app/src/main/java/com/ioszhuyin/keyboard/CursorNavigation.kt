package com.ioszhuyin.keyboard

import android.icu.text.BreakIterator
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.util.Locale
import kotlin.math.abs

internal object CursorNavigation {
    fun move(connection: InputConnection, steps: Int): Boolean {
        if (steps == 0) return true
        val count = abs(steps.coerceIn(-64, 64))
        val right = steps > 0
        val snapshot = connection.getExtractedText(ExtractedTextRequest().apply {
            hintMaxChars = 2048
        }, 0)
        val text = snapshot?.text
        if (snapshot != null && text != null && snapshot.startOffset >= 0 &&
            snapshot.partialStartOffset < 0 &&
            snapshot.selectionStart in 0..text.length && snapshot.selectionEnd in 0..text.length
        ) {
            // Let ICU handle surrogate pairs, combining marks and joined emoji.
            val boundaries = BreakIterator.getCharacterInstance(Locale.ROOT)
            boundaries.setText(text.toString())
            val selected = snapshot.selectionStart != snapshot.selectionEnd
            var cursor = if (right) maxOf(snapshot.selectionStart, snapshot.selectionEnd)
                else minOf(snapshot.selectionStart, snapshot.selectionEnd)
            repeat(count - if (selected) 1 else 0) {
                val next = if (right) boundaries.following(cursor) else boundaries.preceding(cursor)
                if (next != BreakIterator.DONE) cursor = next
            }
            if (connection.setSelection(snapshot.startOffset + cursor, snapshot.startOffset + cursor)) {
                return true
            }
        }
        // Editors that cannot expose text (including some password/custom fields)
        // can still implement their own arrow-key navigation.
        val keyCode = if (right) KeyEvent.KEYCODE_DPAD_RIGHT else KeyEvent.KEYCODE_DPAD_LEFT
        repeat(count) {
            val now = SystemClock.uptimeMillis()
            val down = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_SOFT_KEYBOARD, InputDevice.SOURCE_KEYBOARD)
            val accepted = connection.sendKeyEvent(down)
            val released = connection.sendKeyEvent(KeyEvent.changeAction(down, KeyEvent.ACTION_UP))
            if (!accepted || !released) return false
        }
        return true
    }
}
