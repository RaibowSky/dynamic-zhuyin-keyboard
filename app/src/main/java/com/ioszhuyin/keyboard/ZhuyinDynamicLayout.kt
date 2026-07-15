package com.ioszhuyin.keyboard

/**
 * Static page definitions for the iOS-style dynamic Zhuyin keyboard.
 *
 * This object intentionally does not decide whether a Zhuyin sequence is legal.
 * Visible keys are always pressable; candidates are driven by the dictionary.
 */
object ZhuyinDynamicLayout {

    const val COLUMN_COUNT = 9

    data class SlotGeometry(
        val slot: Float,
        val span: Float
    )

    fun rowStartSlots(showFinalPage: Boolean, configuredOffsets: List<Float>): List<Float> {
        require(configuredOffsets.size == 3)
        return listOf(
            maxOf(configuredOffsets[0], 0.5f),
            if (showFinalPage) 1f else configuredOffsets[1],
            configuredOffsets[2]
        )
    }

    fun evenlyFilledRow(
        keyCount: Int,
        columnCount: Int = COLUMN_COUNT
    ): List<SlotGeometry> {
        require(keyCount > 0)
        require(columnCount > 0)
        val span = columnCount.toFloat() / keyCount
        return List(keyCount) { index ->
            SlotGeometry(slot = index * span, span = span)
        }
    }

    val INITIAL_PAGE_ROWS: List<List<String>> = listOf(
        listOf("ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ"),
        listOf("ㄍ", "ㄎ", "ㄏ", "ㄐ", "ㄑ", "ㄒ", "ㄧ", "ㄨ", "ㄩ"),
        listOf("⇧", "ㄓ", "ㄔ", "ㄕ", "ㄖ", "ㄗ", "ㄘ", "ㄙ", "⌫")
    )

    val FINAL_PAGE_ROWS: List<List<String>> = listOf(
        listOf("ㄚ", "ㄛ", "ㄜ", "ㄝ", "ㄞ", "ㄟ", "ㄠ", "ㄡ"),
        listOf("ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ", "ㄧ", "ㄨ", "ㄩ"),
        listOf("⇧", "ˊ", "ˇ", "ˋ", "˙", "⌫")
    )

    val TONES: List<String> = listOf("ˊ", "ˇ", "ˋ", "˙")
    val MEDIALS: List<String> = listOf("ㄧ", "ㄨ", "ㄩ")

    val ROW1_KEYS: List<String> = INITIAL_PAGE_ROWS[0]
    val ROW2_KEYS: List<String> = INITIAL_PAGE_ROWS[1]
    val ROW3_KEYS: List<String> = INITIAL_PAGE_ROWS[2]
}
