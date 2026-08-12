package com.ioszhuyin.keyboard

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UserDictionarySearchPolicyTest {

    @Test
    fun ordinaryTextKeepsSubstringSearchBehavior() {
        val search = build("  注音  ")

        assertEquals(
            "zhuyin LIKE ? ESCAPE '\\' OR word LIKE ? ESCAPE '\\'",
            search.selection
        )
        assertArrayEquals(arrayOf("%注音%", "%注音%"), search.args)
    }

    @Test
    fun percentAndUnderscoreAreEscapedAsLiterals() {
        assertArrayEquals(arrayOf("%100\\%%", "%100\\%%"), build("100%").args)
        assertArrayEquals(arrayOf("%a\\_b%", "%a\\_b%"), build("a_b").args)
    }

    @Test
    fun escapeCharacterIsEscapedAsALiteral() {
        assertArrayEquals(arrayOf("%a\\\\b%", "%a\\\\b%"), build("a\\b").args)
    }

    @Test
    fun blankQueryDoesNotAddAFilter() {
        assertNull(UserDictionarySearchPolicy.build("  ", "zhuyin", "word"))
    }

    private fun build(query: String): UserDictionarySearch =
        requireNotNull(UserDictionarySearchPolicy.build(query, "zhuyin", "word"))
}
