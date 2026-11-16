package com.ioszhuyin.keyboard

import android.content.Context
import android.content.SharedPreferences

/**
 * How Return behaves while Zhuyin is still composing. By default it only confirms
 * the reading (選定); the opt-in switch restores "confirm, then break the line".
 */
object ReturnKeyOptions {
    const val PREFS_NAME = "return_key"
    const val KEY_CONFIRM_THEN_NEWLINE = "confirm_then_newline"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun confirmThenNewline(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CONFIRM_THEN_NEWLINE, false)

    fun setConfirmThenNewline(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_CONFIRM_THEN_NEWLINE, enabled).apply()
    }
}
