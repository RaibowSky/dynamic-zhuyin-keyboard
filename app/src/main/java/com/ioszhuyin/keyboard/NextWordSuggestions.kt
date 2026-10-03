package com.ioszhuyin.keyboard

/** Small offline starter set plus a bundled corpus table; personal continuations use the learning store. */
internal object NextWordSuggestions {
    /** Shown after a committed word when nothing else can be predicted. */
    val PUNCTUATION_FALLBACK = listOf("，", "。", "！", "？")

    fun learningKey(word: String) = "@next:$word"
    fun eligible(word: String): Boolean = word.length in 1..64 && word.all {
        it in '㐀'..'鿿'
    }
    private val common = mapOf(
        "你好" to listOf("嗎", "世界"), "謝謝" to listOf("你", "大家", "幫忙"),
        "早安" to listOf("你好", "大家"), "晚安" to listOf("好夢"),
        "今天" to listOf("晚上", "下午", "天氣"), "明天" to listOf("早上", "見"),
        "我" to listOf("想", "是", "要", "的"), "你" to listOf("好", "是", "的"),
        "我們" to listOf("一起", "可以"), "可以" to listOf("嗎", "幫忙"),
        "生日" to listOf("快樂"), "新年" to listOf("快樂"), "台灣" to listOf("人", "美食")
    )

    /** Order: learned first, curated starter phrases, then the bundled corpus table. */
    fun suggest(previous: String, learned: List<String>, bundled: List<String> = emptyList()): List<String> =
        if (!eligible(previous)) emptyList() else
            (learned.filter(::eligible) + common[previous].orEmpty() + bundled.filter(::eligible))
                .distinct().take(8)
}
