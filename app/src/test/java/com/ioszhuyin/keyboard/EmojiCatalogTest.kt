package com.ioszhuyin.keyboard

import org.junit.Assert.*
import org.junit.Test

class EmojiCatalogTest {
    @Test fun everyEmojiIsReachableExactlyOnceWithinItsCategory() {
        assertEquals(7, EmojiCatalog.categories.size)
        assertTrue(EmojiCatalog.categories.flatMap { it.entries }.distinct().size >= 300)
        for (category in EmojiCatalog.categories) {
            assertEquals(category.entries.size, category.entries.distinct().size)
            assertFalse(category.entries.any { it.isBlank() || it == "⌫" })
            val pages = (0 until category.pageCount).map(category::page)
            assertTrue(pages.all { it.size in 1..EmojiCatalog.PAGE_SIZE })
            assertEquals(category.entries, pages.flatten())
        }
    }

    @Test fun joinedEmojiAndVariationSelectorsRemainSingleEntries() {
        val all = EmojiCatalog.categories.flatMap { it.entries }
        assertTrue("👨‍👩‍👧‍👦" in all)
        assertTrue("❤️" in all)
        assertEquals("👨‍👩‍👧‍👦".length, UnicodeBackspace.codeUnitsToDelete("👨‍👩‍👧‍👦"))
        assertEquals("❤️".length, UnicodeBackspace.codeUnitsToDelete("❤️"))
    }
}
