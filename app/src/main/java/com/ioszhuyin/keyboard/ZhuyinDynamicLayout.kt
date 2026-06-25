package com.ioszhuyin.keyboard

/**
 * Static page definitions for the iOS-style dynamic Zhuyin keyboard.
 *
 * This object intentionally does not decide whether a Zhuyin sequence is legal.
 * Visible keys are always pressable; candidates are driven by the dictionary.
 */
object ZhuyinDynamicLayout {

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
