package com.ioszhuyin.keyboard

import android.content.Context

/**
 * Zhuyin dictionary backed by a generated CC-CEDICT asset.
 *
 * Source data: CC-CEDICT, CC BY-SA 4.0.
 * Generated asset: app/src/main/assets/zhuyin_cedict.tsv
 */
object ZhuyinDictionary {

    private const val ASSET_NAME = "zhuyin_cedict.tsv"

    @Volatile
    private var dictionary: Map<String, List<String>> = emptyMap()
    @Volatile
    private var legalBaseSyllables: Set<String> = emptySet()
    @Volatile
    private var prefixToNext: Map<String, Set<String>> = emptyMap()

    fun initialize(context: Context) {
        if (dictionary.isNotEmpty()) return

        val loaded = linkedMapOf<String, List<String>>()
        context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).useLines { lines ->
            lines.forEach { line ->
                if (line.isBlank() || line.startsWith("#")) return@forEach
                val tab = line.indexOf('\t')
                if (tab <= 0 || tab == line.lastIndex) return@forEach

                val key = line.substring(0, tab)
                val values = line.substring(tab + 1)
                    .split(' ')
                    .filter { it.isNotBlank() }
                    .distinct()
                if (values.isNotEmpty()) loaded[key] = values
            }
        }

        val bases = loaded.keys
            .map { stripTones(it) }
            .filter { it.length in 1..3 && it.all { ch -> ch in ZHUYIN_CHARS } }
            .toSet()
        val next = mutableMapOf<String, MutableSet<String>>()
        for (syllable in bases) {
            for (i in 1 until syllable.length) {
                val prefix = syllable.substring(0, i)
                val symbol = syllable.substring(i, i + 1)
                next.getOrPut(prefix) { mutableSetOf() }.add(symbol)
            }
        }

        dictionary = loaded
        legalBaseSyllables = bases
        prefixToNext = next
    }

    fun getCandidates(key: String): List<String>? = dictionary[key]

    fun getAllKeys(): Set<String>? = dictionary.keys

    fun isLegalBaseSyllable(key: String): Boolean = key in legalBaseSyllables

    fun getNextSymbols(prefix: String): Set<String>? = prefixToNext[prefix]

    private fun stripTones(value: String): String =
        value.filter { it !in TONE_CHARS }

    private val TONE_CHARS = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')
    private val ZHUYIN_CHARS = setOf(
        'ㄅ', 'ㄆ', 'ㄇ', 'ㄈ', 'ㄉ', 'ㄊ', 'ㄋ', 'ㄌ',
        'ㄍ', 'ㄎ', 'ㄏ', 'ㄐ', 'ㄑ', 'ㄒ',
        'ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ',
        'ㄧ', 'ㄨ', 'ㄩ',
        'ㄚ', 'ㄛ', 'ㄜ', 'ㄝ', 'ㄞ', 'ㄟ', 'ㄠ', 'ㄡ',
        'ㄢ', 'ㄣ', 'ㄤ', 'ㄥ', 'ㄦ'
    )
}
