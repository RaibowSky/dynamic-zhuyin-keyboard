package com.ioszhuyin.keyboard

import android.content.Context

/** Text for the in-app open-source licenses page, read from the bundled notice assets. */
object OpenSourceNotices {
    /** Bundled notice files, in display order, with the section title shown above each. */
    val SECTIONS = listOf(
        "注音字典（CC-CEDICT、McBopomofo）" to "zhuyin_cedict_LICENSE.txt",
        "片語讀音來源（libtabe）" to "libtabe_LICENSE.txt",
        "注音字型（ToneOZ 拼音文楷）" to "bopomofo_FONT_NOTICE.txt",
        "SIL Open Font License 1.1" to "bopomofo_OFL.txt"
    )

    const val HEADER =
        "動態注音鍵盤以 Apache License 2.0 授權釋出：\n" +
            "https://www.apache.org/licenses/LICENSE-2.0\n" +
            "原始碼：https://github.com/RaibowSky/dynamic-zhuyin-keyboard\n\n" +
            "本 App 與 Apple Inc. 無關，也未獲其背書或贊助。名稱中的「iOS」僅描述啟發設計的鍵盤操作方式；" +
            "iOS 是 Apple Inc. 的商標。本 App 不包含任何 Apple 原始碼、圖像或音效。\n\n" +
            "本 App 隨附下列第三方資料，授權與著作權聲明如下。"

    fun text(context: Context): String = buildString {
        append(HEADER)
        for ((title, asset) in SECTIONS) {
            append("\n\n━━━━━━━━━━━━\n")
            append(title)
            append("\n━━━━━━━━━━━━\n\n")
            append(
                runCatching {
                    context.assets.open(asset).bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                }.getOrElse { "（無法讀取 $asset）" }
            )
        }
    }
}
