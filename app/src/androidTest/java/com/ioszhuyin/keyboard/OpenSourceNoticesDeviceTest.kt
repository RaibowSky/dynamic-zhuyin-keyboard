package com.ioszhuyin.keyboard

import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenSourceNoticesDeviceTest {
    @Test fun licensesPageIncludesEveryBundledNotice() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val text = OpenSourceNotices.text(context)
        assertFalse(text.contains("無法讀取"))
        listOf(
            "Apache License 2.0",
            "Apple Inc.",
            "CC BY-SA 4.0",
            "Paul Andrew Denisowski",
            "Mengjuei Hsieh",
            "next_word.tsv",
            "Pai-Hsiang Hsiao",
            "Academia Sinica",
            "SIL OPEN FONT LICENSE",
            "TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION",
            "appcompat 1.6.1",
            "kotlinx.coroutines library.",
            "Kotlin Compiler"
        ).forEach { assertTrue("missing $it", text.contains(it)) }
        assertFalse("iOS is not an Apple trademark", text.contains("iOS 是 Apple"))
    }
}
