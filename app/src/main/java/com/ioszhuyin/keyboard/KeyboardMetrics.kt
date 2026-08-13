package com.ioszhuyin.keyboard

import android.content.Context
import android.content.SharedPreferences

data class KeyboardLayoutMetrics(
    val keyHeight: Float,
    val controlHeight: Float,
    val horizontalGap: Float,
    val verticalGap: Float,
    val keyboardHorizontalPadding: Float,
    val keyboardTopPadding: Float,
    val keyboardBottomPadding: Float,
    val rowOffset1: Float,
    val rowOffset2: Float,
    val rowOffset3: Float,
    val functionKeyWidth: Float,
    val spacebarWidthRatio: Float,
    val returnKeyWidthRatio: Float,
    val candidateBarHeight: Float,
    val compositionBarHeight: Float,
    val cornerRadius: Float,
    val keyFontSize: Float,
    val controlFontSize: Float,
    val compositionFontSize: Float,
    val candidateFontSize: Float,
    val toneFontSize: Float,
    val hintFontSize: Float,
    val keyStrokeWidth: Float,
    val compositionTextLeftPadding: Float,
    val candidateInnerPadding: Float,
    val candidateGap: Float,
    val candidateMoreWidth: Float,
    val candidateCornerRadius: Float,
    val candidateMoreFontSize: Float,
    val hintBottomPadding: Float,
    val toneShiftSlot: Float,
    val toneStartSlot: Float,
    val toneKeyStep: Float,
    val toneKeyWidth: Float,
    val toneBackspaceSlot: Float
)

object KeyboardMetrics {

    const val PRESET_PIXEL = "pixel"
    const val PRESET_IOS = "ios"

    val PixelPreset = KeyboardLayoutMetrics(
        keyHeight = 45f,
        controlHeight = 46f,
        horizontalGap = 4f,
        verticalGap = 4f,
        keyboardHorizontalPadding = 3f,
        keyboardTopPadding = 0f,
        keyboardBottomPadding = 0f,
        rowOffset1 = 0f,
        rowOffset2 = 0f,
        rowOffset3 = 0f,
        functionKeyWidth = 1.3f,
        spacebarWidthRatio = 4.4f,
        returnKeyWidthRatio = 1.3f,
        candidateBarHeight = 40f,
        compositionBarHeight = 40f,
        cornerRadius = 5f,
        keyFontSize = 28f,
        controlFontSize = 20f,
        compositionFontSize = 20f,
        candidateFontSize = 17f,
        toneFontSize = 24f,
        hintFontSize = 9f,
        keyStrokeWidth = 0.5f,
        compositionTextLeftPadding = 14f,
        candidateInnerPadding = 2f,
        candidateGap = 2f,
        candidateMoreWidth = 32f,
        candidateCornerRadius = 4f,
        candidateMoreFontSize = 18f,
        hintBottomPadding = 3f,
        toneShiftSlot = 0f,
        toneStartSlot = 1.45f,
        toneKeyStep = 1.5f,
        toneKeyWidth = 1.25f,
        toneBackspaceSlot = 8f
    )

    val IOSPreset = KeyboardLayoutMetrics(
        keyHeight = 47f,
        controlHeight = 48f,
        horizontalGap = 5f,
        verticalGap = 5f,
        keyboardHorizontalPadding = 4f,
        keyboardTopPadding = 0f,
        keyboardBottomPadding = 0f,
        rowOffset1 = 0.5f,
        rowOffset2 = 0f,
        rowOffset3 = 0f,
        functionKeyWidth = 1.15f,
        spacebarWidthRatio = 4.6f,
        returnKeyWidthRatio = 1.55f,
        candidateBarHeight = 40f,
        compositionBarHeight = 40f,
        cornerRadius = 5f,
        keyFontSize = 28f,
        controlFontSize = 20f,
        compositionFontSize = 20f,
        candidateFontSize = 17f,
        toneFontSize = 24f,
        hintFontSize = 9f,
        keyStrokeWidth = 0.5f,
        compositionTextLeftPadding = 14f,
        candidateInnerPadding = 2f,
        candidateGap = 2f,
        candidateMoreWidth = 32f,
        candidateCornerRadius = 4f,
        candidateMoreFontSize = 18f,
        hintBottomPadding = 3f,
        toneShiftSlot = 0f,
        toneStartSlot = 1.45f,
        toneKeyStep = 1.5f,
        toneKeyWidth = 1.25f,
        toneBackspaceSlot = 8f
    )

    fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun current(context: Context): KeyboardLayoutMetrics {
        val prefs = prefs(context)
        val preset = if (prefs.getString(KEY_PRESET, PRESET_IOS) == PRESET_PIXEL) {
            PixelPreset
        } else {
            IOSPreset
        }
        return KeyboardLayoutMetrics(
            keyHeight = prefs.getFloat(KEY_KEY_HEIGHT, preset.keyHeight),
            controlHeight = prefs.getFloat(KEY_CONTROL_HEIGHT, preset.controlHeight),
            horizontalGap = prefs.getFloat(KEY_HORIZONTAL_GAP, preset.horizontalGap),
            verticalGap = prefs.getFloat(KEY_VERTICAL_GAP, preset.verticalGap),
            keyboardHorizontalPadding = prefs.getFloat(KEY_HORIZONTAL_PADDING, preset.keyboardHorizontalPadding),
            keyboardTopPadding = prefs.getFloat(KEY_TOP_PADDING, preset.keyboardTopPadding),
            keyboardBottomPadding = prefs.getFloat(KEY_BOTTOM_PADDING, preset.keyboardBottomPadding),
            rowOffset1 = prefs.getFloat(KEY_ROW_OFFSET_1, preset.rowOffset1),
            rowOffset2 = prefs.getFloat(KEY_ROW_OFFSET_2, preset.rowOffset2),
            rowOffset3 = prefs.getFloat(KEY_ROW_OFFSET_3, preset.rowOffset3),
            functionKeyWidth = prefs.getFloat(KEY_FUNCTION_KEY_WIDTH, preset.functionKeyWidth),
            spacebarWidthRatio = prefs.getFloat(KEY_SPACEBAR_RATIO, preset.spacebarWidthRatio),
            returnKeyWidthRatio = prefs.getFloat(KEY_RETURN_KEY_RATIO, preset.returnKeyWidthRatio),
            candidateBarHeight = prefs.getFloat(KEY_CANDIDATE_BAR_HEIGHT, preset.candidateBarHeight),
            compositionBarHeight = prefs.getFloat(KEY_COMPOSITION_BAR_HEIGHT, preset.compositionBarHeight),
            cornerRadius = prefs.getFloat(KEY_CORNER_RADIUS, preset.cornerRadius),
            keyFontSize = prefs.getFloat(KEY_KEY_FONT_SIZE, preset.keyFontSize),
            controlFontSize = prefs.getFloat(KEY_CONTROL_FONT_SIZE, preset.controlFontSize),
            compositionFontSize = prefs.getFloat(KEY_COMPOSITION_FONT_SIZE, preset.compositionFontSize),
            candidateFontSize = prefs.getFloat(KEY_CANDIDATE_FONT_SIZE, preset.candidateFontSize),
            toneFontSize = prefs.getFloat(KEY_TONE_FONT_SIZE, preset.toneFontSize),
            hintFontSize = prefs.getFloat(KEY_HINT_FONT_SIZE, preset.hintFontSize),
            keyStrokeWidth = prefs.getFloat(KEY_KEY_STROKE_WIDTH, preset.keyStrokeWidth),
            compositionTextLeftPadding = prefs.getFloat(KEY_COMPOSITION_TEXT_LEFT_PADDING, preset.compositionTextLeftPadding),
            candidateInnerPadding = prefs.getFloat(KEY_CANDIDATE_INNER_PADDING, preset.candidateInnerPadding),
            candidateGap = prefs.getFloat(KEY_CANDIDATE_GAP, preset.candidateGap),
            candidateMoreWidth = prefs.getFloat(KEY_CANDIDATE_MORE_WIDTH, preset.candidateMoreWidth),
            candidateCornerRadius = prefs.getFloat(KEY_CANDIDATE_CORNER_RADIUS, preset.candidateCornerRadius),
            candidateMoreFontSize = prefs.getFloat(KEY_CANDIDATE_MORE_FONT_SIZE, preset.candidateMoreFontSize),
            hintBottomPadding = prefs.getFloat(KEY_HINT_BOTTOM_PADDING, preset.hintBottomPadding),
            toneShiftSlot = prefs.getFloat(KEY_TONE_SHIFT_SLOT, preset.toneShiftSlot),
            toneStartSlot = prefs.getFloat(KEY_TONE_START_SLOT, preset.toneStartSlot),
            toneKeyStep = prefs.getFloat(KEY_TONE_KEY_STEP, preset.toneKeyStep),
            toneKeyWidth = prefs.getFloat(KEY_TONE_KEY_WIDTH, preset.toneKeyWidth),
            toneBackspaceSlot = prefs.getFloat(KEY_TONE_BACKSPACE_SLOT, preset.toneBackspaceSlot)
        )
    }

    fun applyPreset(context: Context, presetName: String) {
        val preset = if (presetName == PRESET_PIXEL) PixelPreset else IOSPreset
        prefs(context).edit()
            .putString(KEY_PRESET, presetName)
            .putFloat(KEY_KEY_HEIGHT, preset.keyHeight)
            .putFloat(KEY_CONTROL_HEIGHT, preset.controlHeight)
            .putFloat(KEY_HORIZONTAL_GAP, preset.horizontalGap)
            .putFloat(KEY_VERTICAL_GAP, preset.verticalGap)
            .putFloat(KEY_HORIZONTAL_PADDING, preset.keyboardHorizontalPadding)
            .putFloat(KEY_TOP_PADDING, preset.keyboardTopPadding)
            .putFloat(KEY_BOTTOM_PADDING, preset.keyboardBottomPadding)
            .putFloat(KEY_ROW_OFFSET_1, preset.rowOffset1)
            .putFloat(KEY_ROW_OFFSET_2, preset.rowOffset2)
            .putFloat(KEY_ROW_OFFSET_3, preset.rowOffset3)
            .putFloat(KEY_FUNCTION_KEY_WIDTH, preset.functionKeyWidth)
            .putFloat(KEY_SPACEBAR_RATIO, preset.spacebarWidthRatio)
            .putFloat(KEY_RETURN_KEY_RATIO, preset.returnKeyWidthRatio)
            .putFloat(KEY_CANDIDATE_BAR_HEIGHT, preset.candidateBarHeight)
            .putFloat(KEY_COMPOSITION_BAR_HEIGHT, preset.compositionBarHeight)
            .putFloat(KEY_CORNER_RADIUS, preset.cornerRadius)
            .putFloat(KEY_KEY_FONT_SIZE, preset.keyFontSize)
            .putFloat(KEY_CONTROL_FONT_SIZE, preset.controlFontSize)
            .putFloat(KEY_COMPOSITION_FONT_SIZE, preset.compositionFontSize)
            .putFloat(KEY_CANDIDATE_FONT_SIZE, preset.candidateFontSize)
            .putFloat(KEY_TONE_FONT_SIZE, preset.toneFontSize)
            .putFloat(KEY_HINT_FONT_SIZE, preset.hintFontSize)
            .putFloat(KEY_KEY_STROKE_WIDTH, preset.keyStrokeWidth)
            .putFloat(KEY_COMPOSITION_TEXT_LEFT_PADDING, preset.compositionTextLeftPadding)
            .putFloat(KEY_CANDIDATE_INNER_PADDING, preset.candidateInnerPadding)
            .putFloat(KEY_CANDIDATE_GAP, preset.candidateGap)
            .putFloat(KEY_CANDIDATE_MORE_WIDTH, preset.candidateMoreWidth)
            .putFloat(KEY_CANDIDATE_CORNER_RADIUS, preset.candidateCornerRadius)
            .putFloat(KEY_CANDIDATE_MORE_FONT_SIZE, preset.candidateMoreFontSize)
            .putFloat(KEY_HINT_BOTTOM_PADDING, preset.hintBottomPadding)
            .putFloat(KEY_TONE_SHIFT_SLOT, preset.toneShiftSlot)
            .putFloat(KEY_TONE_START_SLOT, preset.toneStartSlot)
            .putFloat(KEY_TONE_KEY_STEP, preset.toneKeyStep)
            .putFloat(KEY_TONE_KEY_WIDTH, preset.toneKeyWidth)
            .putFloat(KEY_TONE_BACKSPACE_SLOT, preset.toneBackspaceSlot)
            .apply()
    }

    fun isKeyboardMetricKey(key: String?): Boolean =
        key != null && key.startsWith("keyboard_metric_")

    const val PREFS_NAME = "keyboard_metrics"
    const val KEY_PRESET = "keyboard_metric_preset"
    const val KEY_KEY_HEIGHT = "keyboard_metric_key_height"
    const val KEY_CONTROL_HEIGHT = "keyboard_metric_control_height"
    const val KEY_HORIZONTAL_GAP = "keyboard_metric_horizontal_gap"
    const val KEY_VERTICAL_GAP = "keyboard_metric_vertical_gap"
    const val KEY_HORIZONTAL_PADDING = "keyboard_metric_horizontal_padding"
    const val KEY_TOP_PADDING = "keyboard_metric_top_padding"
    const val KEY_BOTTOM_PADDING = "keyboard_metric_bottom_padding"
    const val KEY_ROW_OFFSET_1 = "keyboard_metric_row_offset_1"
    const val KEY_ROW_OFFSET_2 = "keyboard_metric_row_offset_2"
    const val KEY_ROW_OFFSET_3 = "keyboard_metric_row_offset_3"
    const val KEY_FUNCTION_KEY_WIDTH = "keyboard_metric_function_key_width"
    const val KEY_SPACEBAR_RATIO = "keyboard_metric_spacebar_ratio"
    const val KEY_RETURN_KEY_RATIO = "keyboard_metric_return_key_ratio"
    const val KEY_CANDIDATE_BAR_HEIGHT = "keyboard_metric_candidate_bar_height"
    const val KEY_COMPOSITION_BAR_HEIGHT = "keyboard_metric_composition_bar_height"
    const val KEY_CORNER_RADIUS = "keyboard_metric_corner_radius"
    const val KEY_KEY_FONT_SIZE = "keyboard_metric_key_font_size"
    const val KEY_CONTROL_FONT_SIZE = "keyboard_metric_control_font_size"
    const val KEY_COMPOSITION_FONT_SIZE = "keyboard_metric_composition_font_size"
    const val KEY_CANDIDATE_FONT_SIZE = "keyboard_metric_candidate_font_size"
    const val KEY_TONE_FONT_SIZE = "keyboard_metric_tone_font_size"
    const val KEY_HINT_FONT_SIZE = "keyboard_metric_hint_font_size"
    const val KEY_KEY_STROKE_WIDTH = "keyboard_metric_key_stroke_width"
    const val KEY_COMPOSITION_TEXT_LEFT_PADDING = "keyboard_metric_composition_text_left_padding"
    const val KEY_CANDIDATE_INNER_PADDING = "keyboard_metric_candidate_inner_padding"
    const val KEY_CANDIDATE_GAP = "keyboard_metric_candidate_gap"
    const val KEY_CANDIDATE_MORE_WIDTH = "keyboard_metric_candidate_more_width"
    const val KEY_CANDIDATE_CORNER_RADIUS = "keyboard_metric_candidate_corner_radius"
    const val KEY_CANDIDATE_MORE_FONT_SIZE = "keyboard_metric_candidate_more_font_size"
    const val KEY_HINT_BOTTOM_PADDING = "keyboard_metric_hint_bottom_padding"
    const val KEY_TONE_SHIFT_SLOT = "keyboard_metric_tone_shift_slot"
    const val KEY_TONE_START_SLOT = "keyboard_metric_tone_start_slot"
    const val KEY_TONE_KEY_STEP = "keyboard_metric_tone_key_step"
    const val KEY_TONE_KEY_WIDTH = "keyboard_metric_tone_key_width"
    const val KEY_TONE_BACKSPACE_SLOT = "keyboard_metric_tone_backspace_slot"
}
