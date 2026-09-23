package com.ioszhuyin.keyboard

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.core.content.ContextCompat

data class KeyboardPalette(
    val background: Int,
    val topDivider: Int,
    val keyBackground: Int,
    val keyBackgroundDisabled: Int,
    val keyBackgroundPressed: Int,
    val keyStroke: Int,
    val keyText: Int,
    val keyTextDisabled: Int,
    val controlBackground: Int,
    val controlBackgroundPressed: Int,
    val controlText: Int,
    val spaceBackground: Int,
    val spaceBackgroundPressed: Int,
    val spaceText: Int,
    val returnBackground: Int,
    val returnBackgroundPressed: Int,
    val toneBackground: Int,
    val toneText: Int,
    val candidateBar: Int,
    val candidateBackground: Int,
    val candidateSelected: Int,
    val candidateToggle: Int
)

data class SettingsPalette(
    val background: Int,
    val title: Int,
    val subtitle: Int,
    val status: Int,
    val muted: Int,
    val inputText: Int,
    val inputHint: Int,
    val inputUnderline: Int,
    val chipBackground: Int,
    val buttonText: Int,
    val primary: Int,
    val secondary: Int,
    val learning: Int,
    val danger: Int,
    val importAccent: Int,
    val neutral: Int,
    val search: Int,
    val debugPixel: Int,
    val debugIos: Int
)

object ThemePalette {
    fun isNightMode(configuration: Configuration): Boolean =
        (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun isNightMode(resources: Resources): Boolean = isNightMode(resources.configuration)

    fun isNightMode(context: Context): Boolean = isNightMode(context.resources)

    fun keyboard(context: Context): KeyboardPalette = KeyboardPalette(
        background = color(context, R.color.keyboard_background),
        topDivider = color(context, R.color.keyboard_top_divider),
        keyBackground = color(context, R.color.keyboard_key_background),
        keyBackgroundDisabled = color(context, R.color.keyboard_key_background_disabled),
        keyBackgroundPressed = color(context, R.color.keyboard_key_background_pressed),
        keyStroke = color(context, R.color.keyboard_key_stroke),
        keyText = color(context, R.color.keyboard_key_text),
        keyTextDisabled = color(context, R.color.keyboard_key_text_disabled),
        controlBackground = color(context, R.color.keyboard_control_background),
        controlBackgroundPressed = color(context, R.color.keyboard_control_background_pressed),
        controlText = color(context, R.color.keyboard_control_text),
        spaceBackground = color(context, R.color.keyboard_space_background),
        spaceBackgroundPressed = color(context, R.color.keyboard_space_background_pressed),
        spaceText = color(context, R.color.keyboard_space_text),
        returnBackground = color(context, R.color.keyboard_return_background),
        returnBackgroundPressed = color(context, R.color.keyboard_return_background_pressed),
        toneBackground = color(context, R.color.keyboard_tone_background),
        toneText = color(context, R.color.keyboard_tone_text),
        candidateBar = color(context, R.color.keyboard_candidate_bar),
        candidateBackground = color(context, R.color.keyboard_candidate_background),
        candidateSelected = color(context, R.color.keyboard_candidate_selected),
        candidateToggle = color(context, R.color.keyboard_candidate_toggle)
    )

    fun settings(context: Context): SettingsPalette = SettingsPalette(
        background = color(context, R.color.surface_background),
        title = color(context, R.color.text_primary),
        subtitle = color(context, R.color.text_muted),
        status = color(context, R.color.text_secondary),
        muted = color(context, R.color.text_muted),
        inputText = color(context, R.color.text_primary),
        inputHint = color(context, R.color.text_hint),
        inputUnderline = color(context, R.color.input_underline),
        chipBackground = color(context, R.color.surface_chip),
        buttonText = color(context, R.color.color_on_primary),
        primary = color(context, R.color.color_primary),
        secondary = color(context, R.color.color_secondary),
        learning = color(context, R.color.color_accent_learning),
        danger = color(context, R.color.color_accent_danger),
        importAccent = color(context, R.color.color_accent_import),
        neutral = color(context, R.color.color_accent_neutral),
        search = color(context, R.color.color_accent_search),
        debugPixel = color(context, R.color.color_accent_debug_pixel),
        debugIos = color(context, R.color.color_accent_debug_ios)
    )

    private fun color(context: Context, resId: Int): Int =
        ContextCompat.getColor(context, resId)
}
