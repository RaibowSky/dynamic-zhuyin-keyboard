package com.ioszhuyin.keyboard

import android.content.Context
import android.content.SharedPreferences

/** Whether Zhuyin taps near a key's edge favor the likelier next key (on by default). */
object KeyPredictionOptions {
    const val PREFS_NAME = "key_prediction"
    const val KEY_ENABLED = "enabled"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }
}
