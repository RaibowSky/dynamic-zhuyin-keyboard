package com.ioszhuyin.keyboard

import org.junit.Assert.*
import org.junit.Test

class NextWordSuggestionsTest {
    @Test fun learnedWordsPrecedeStarterSuggestionsWithoutDuplicates() {
        assertEquals(listOf("您", "你", "大家", "幫忙"),
            NextWordSuggestions.suggest("謝謝", listOf("您", "你", "您")))
        assertEquals(listOf("快樂"), NextWordSuggestions.suggest("生日", emptyList()))
    }
    @Test fun sentenceBoundariesAndNonChineseInputAreNotLearningContexts() {
        for (word in listOf("", "test@example.com", "你好！", "123456", "你 好")) {
            assertFalse(NextWordSuggestions.eligible(word))
            assertTrue(NextWordSuggestions.suggest(word, listOf("你")).isEmpty())
        }
        assertEquals(emptyList<String>(), NextWordSuggestions.suggest("未知", listOf("123", "😀")))
        assertTrue(NextWordSuggestions.learningKey("你好").startsWith("@next:"))
    }
}

class NextWordTableTest {
    private val table = NextWordTable.parse(
        sequenceOf("# header", "測\t試 驗 量", "測試\t版 儀", "試\t用", "").asSequence()
    )

    @Test fun parseSkipsCommentsAndBlankLines() {
        assertEquals(setOf("測", "測試", "試"), table.keys)
        assertEquals(listOf("試", "驗", "量"), table["測"])
    }

    @Test fun longerPrefixWinsThenFallsBackToLastCharacters() {
        assertEquals(listOf("版", "儀", "用"), NextWordTable.lookup(table, "測試"))
        assertEquals(listOf("試", "驗", "量"), NextWordTable.lookup(table, "測"))
        assertTrue(NextWordTable.lookup(table, "未知").isEmpty())
    }

    @Test fun bundledContinuationsFollowLearnedAndStarterSuggestions() {
        assertEquals(listOf("您", "你", "大家", "幫忙", "妳"),
            NextWordSuggestions.suggest("謝謝", listOf("您"), listOf("你", "妳", "123")))
        assertEquals(listOf("試", "驗"), NextWordSuggestions.suggest("測", emptyList(), listOf("試", "驗")))
    }
}
