package com.ioszhuyin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class UserDictionaryImportPolicyTest {

    @Test
    fun validatesAndTrimsDictionaryFields() {
        assertEquals(
            "ㄋㄧˇ" to "你",
            UserDictionaryImportPolicy.validatedEntry("  ㄋㄧˇ  ", "  你  ")
        )
    }

    @Test
    fun rejectsOversizedFields() {
        assertThrows(IllegalArgumentException::class.java) {
            UserDictionaryImportPolicy.validatedEntry(
                "ㄅ".repeat(UserDictionaryImportPolicy.MAX_READING_LENGTH + 1),
                "字"
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            UserDictionaryImportPolicy.validatedEntry(
                "ㄅ",
                "字".repeat(UserDictionaryImportPolicy.MAX_WORD_LENGTH + 1)
            )
        }
    }

    @Test
    fun rejectsTooManyImportedRecords() {
        UserDictionaryImportPolicy.requireValidRecordCount(
            UserDictionaryImportPolicy.MAX_IMPORT_RECORDS - 1,
            1
        )
        assertThrows(IllegalArgumentException::class.java) {
            UserDictionaryImportPolicy.requireValidRecordCount(
                UserDictionaryImportPolicy.MAX_IMPORT_RECORDS,
                1
            )
        }
    }

    @Test
    fun importByteLimitCountsUtf8Bytes() {
        assertThrows(IllegalArgumentException::class.java) {
            UserDictionaryImportPolicy.requireValidTextSize("中文字", maxBytes = 8)
        }
        UserDictionaryImportPolicy.requireValidTextSize("中文字", maxBytes = 9)
    }

    @Test
    fun stripsOneUtf8BomBeforeDetectingJsonOrTsv() {
        assertEquals(
            "{\"entries\":[]}",
            UserDictionaryImportPolicy.normalizeForImport("\uFEFF  {\"entries\":[]}  ")
        )
        assertEquals(
            "ㄋㄧˇ\t你",
            UserDictionaryImportPolicy.normalizeForImport("\uFEFFㄋㄧˇ\t你\n")
        )
    }
}
