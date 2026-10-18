package com.ioszhuyin.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager

/**
 * The keyboard's own key-click setting. It is independent of the system
 * "touch sounds" switch, so it is off until the user enables it here.
 * Silent and vibrate ringer modes always mute it.
 */
object KeyboardSound {
    const val PREFS_NAME = "keyboard_sound"
    const val KEY_ENABLED = "key_sound_enabled"
    const val KEY_VOLUME = "key_sound_volume"
    const val DEFAULT_VOLUME = 50

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, false)

    /** Percent of the system sound-effect level, 0..100. */
    fun volume(context: Context): Int =
        prefs(context).getInt(KEY_VOLUME, DEFAULT_VOLUME).coerceIn(0, 100)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setVolume(context: Context, volume: Int) {
        prefs(context).edit().putInt(KEY_VOLUME, volume.coerceIn(0, 100)).apply()
    }

    fun play(context: Context, effect: Int, force: Boolean = false) {
        if (!force && !enabled(context)) return
        val volume = volume(context)
        if (volume == 0) return
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            if (audio.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            // The volume overload plays regardless of the system touch-sound switch.
            audio.playSoundEffect(effect, volume / 100f)
        } catch (_: RuntimeException) {
        }
    }
}
