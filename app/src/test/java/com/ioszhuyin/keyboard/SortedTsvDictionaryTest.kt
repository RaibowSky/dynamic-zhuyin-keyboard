package com.ioszhuyin.keyboard

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SortedTsvDictionaryTest {

    @Test
    fun unknownPhraseCanSelectLeadingCharacterAndKeepKnownSuffix() {
        val asset = Path.of("src", "main", "assets", "zhuyin_cedict.tsv")
        val reader = SortedTsvDictionary(ByteBuffer.wrap(Files.readAllBytes(asset)))
        val syllables = listOf("ㄢˋ", "ㄅㄨˋ", "ㄉㄠˋ")
        val raw = syllables.joinToString("")
        var offset = 0
        val segments = syllables.map { syllable ->
            val start = offset
            offset += syllable.length
            ZhuyinSegment(syllable, start, offset, hasTone = true)
        }

        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments) { reading ->
            reader.getCandidates(reading).orEmpty()
        }

        assertEquals(raw, match?.reading)
        assertTrue("按" in match?.candidates.orEmpty())
        assertEquals(raw.length, match?.choices?.first()?.end)
        val leadingChoice = match?.choices?.first { it.text == "按" }
        assertEquals("ㄢˋ", leadingChoice?.reading)
        assertEquals("ㄅㄨˋㄉㄠˋ", raw.substring(leadingChoice?.end ?: 0))

        val edit = requireNotNull(leadingChoice).let { choice ->
            CompositionEditing.candidateSelection(
                raw = raw,
                start = choice.start,
                end = choice.end,
                candidate = choice.text
            )
        }
        assertEquals("按", edit.committedText)
        assertEquals("ㄅㄨˋㄉㄠˋ", edit.remainingText)
    }

    @Test
    fun bundledDictionaryContainsCommonTaiwanPhraseReadings() {
        val asset = Path.of("src", "main", "assets", "zhuyin_cedict.tsv")
        val reader = SortedTsvDictionary(ByteBuffer.wrap(Files.readAllBytes(asset)))

        assertEquals("這是", reader.getCandidates("ㄓㄜˋㄕˋ")?.first())
        assertEquals("這是", reader.getCandidates("ㄓㄜㄕ")?.first())
        assertEquals("做出來", reader.getCandidates("ㄗㄨㄛˋㄔㄨㄌㄞˊ")?.first())
        assertEquals("所以", reader.getCandidates("ㄙㄨㄛˇㄧˇ")?.first())
        assertEquals("現在", reader.getCandidates("ㄒㄧㄢˋㄗㄞˋ")?.first())
        assertEquals("動態", reader.getCandidates("ㄉㄨㄥˋㄊㄞˋ")?.first())
        assertEquals("鍵盤", reader.getCandidates("ㄐㄧㄢˋㄆㄢˊ")?.first())

        val syllables = listOf("ㄙㄨㄛˇ", "ㄧˇ", "ㄨㄛˇ", "ㄒㄧㄢˋ", "ㄗㄞˋ")
        val raw = syllables.joinToString("")
        var offset = 0
        val segments = syllables.map { syllable ->
            val start = offset
            offset += syllable.length
            ZhuyinSegment(syllable, start, offset, hasTone = true)
        }
        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments) { reading ->
            reader.getCandidates(reading).orEmpty()
        }

        assertEquals("ㄙㄨㄛˇㄧˇ", match?.reading)
        assertEquals("所以", match?.candidates?.first())
        assertEquals("ㄨㄛˇㄒㄧㄢˋㄗㄞˋ", raw.substring(match?.end ?: 0))
    }

    @Test
    fun binarySearchFindsFirstMiddleAndLastRows() {
        val reader = readerOf(
            "ㄅ" to "不 八",
            "ㄇ" to "嗎 媽",
            "ㄅㄚ" to "八 巴",
            "ㄇㄚ" to "嗎 媽",
            "ㄅㄚˉ" to "八",
            "ㄅㄚˋ" to "爸"
        )

        assertEquals(listOf("不", "八"), reader.getCandidates("ㄅ"))
        assertEquals(listOf("八", "巴"), reader.getCandidates("ㄅㄚ"))
        assertEquals(listOf("爸"), reader.getCandidates("ㄅㄚˋ"))
    }

    @Test
    fun binarySearchReturnsNullAroundMissingKeys() {
        val reader = readerOf(
            "ㄅ" to "不",
            "ㄇ" to "嗎",
            "ㄅㄚ" to "八",
            "ㄇㄚ" to "媽"
        )

        assertNull(reader.getCandidates("ˉ"))
        assertNull(reader.getCandidates("ㄆ"))
        assertNull(reader.getCandidates("ㄈㄚ"))
        assertNull(reader.getCandidates("ㄅㄚˉ"))
        assertNull(reader.getCandidates(""))
    }

    @Test
    fun directByteBufferAndCrLfDecodeOnlyMatchedCandidates() {
        val content = dictionaryText(
            listOf(
                "ㄅ" to "不  不 八",
                "ㄇ" to "嗎 媽",
                "ㄅㄚ" to "八 巴"
            ),
            lineSeparator = "\r\n"
        ).toByteArray(Charsets.UTF_8)
        val direct = ByteBuffer.allocateDirect(content.size).apply {
            put(content)
            flip()
        }
        val reader = SortedTsvDictionary(direct)

        assertEquals(listOf("不", "八"), reader.getCandidates("ㄅ"))
        assertEquals(listOf("八", "巴"), reader.getCandidates("ㄅㄚ"))
    }

    @Test
    fun comparatorMatchesGeneratorCodePointLengthThenUnicodeOrder() {
        val keys = listOf("ㄅㄆㄇ", "ˉˊˇˋ", "😀", "ㄅ", "ˉ")
        val expected = keys.sortedWith { left, right ->
            val byLength = left.codePointCount(0, left.length)
                .compareTo(right.codePointCount(0, right.length))
            if (byLength != 0) byLength else compareCodePoints(left, right)
        }

        assertEquals(expected, keys.sortedWith(SortedTsvDictionary::compareKeys))
        assertTrue(SortedTsvDictionary.compareKeys("ㄅㄆㄇ", "ˉˊˇˋ") < 0)
    }

    @Test
    fun keySetIsAStreamingViewWithContainsSizeAndIteration() {
        val entries = listOf(
            "ㄅ" to "不",
            "ㄇ" to "嗎",
            "ㄅㄚ" to "八",
            "ㄇㄚ" to "媽"
        )
        val reader = readerOf(*entries.toTypedArray())

        assertEquals(entries.size, reader.keys.size)
        assertTrue("ㄅㄚ" in reader.keys)
        assertFalse("ㄆㄚ" in reader.keys)
        assertEquals(sortedEntries(entries).map { it.first }, reader.keys.toList())
    }

    @Test
    fun prefixLookupIsBoundedDeduplicatedAndPrefersShorterReadings() {
        val reader = readerOf(
            "ㄋㄚ" to "那 南",
            "ㄋㄚˋ" to "那",
            "ㄋㄧ" to "你 呢",
            "ㄋㄧˇ" to "你 妳",
            "ㄋㄧˇㄏㄜˊ" to "擬合",
            "ㄋㄧˇㄏㄠˇ" to "你好"
        )

        assertEquals(
            listOf("那", "你", "南", "呢", "妳"),
            reader.getPrefixCandidates("ㄋ", limit = 5)
        )
        assertEquals(
            listOf("擬合", "你好"),
            reader.getPrefixCandidates("ㄋㄧˇㄏ", limit = 9)
        )
        assertEquals(emptyList<String>(), reader.getPrefixCandidates("ㄇ", limit = 9))
        assertEquals(emptyList<String>(), reader.getPrefixCandidates("ㄋ", limit = 0))
    }

    @Test
    fun repeatedLookupsPreserveMissingRowsAndIndependentPrefixLimits() {
        val reader = readerOf(
            "aa" to "A B",
            "ab" to "B C",
            "aaa" to "D"
        )

        assertNull(reader.getCandidates("missing"))
        assertNull(reader.getCandidates("missing"))
        assertEquals(listOf("B"), reader.getPrefixCandidates("a", limit = 1))
        assertEquals(listOf("B", "A", "C"), reader.getPrefixCandidates("a", limit = 3))
        assertEquals(listOf("B"), reader.getPrefixCandidates("a", limit = 1))
    }

    @Test
    fun binarySearchHandlesThousandsOfVariableLengthRows() {
        val entries = (0 until 2_048).map { index ->
            val key = "ㄅ".repeat(index % 5 + 1) + index.toString().padStart(4, '0')
            key to "詞$index ${"長".repeat(index % 37 + 1)}"
        }
        val reader = readerOf(*entries.toTypedArray())

        for ((key, candidates) in entries) {
            assertEquals(candidates.split(' '), reader.getCandidates(key))
        }
        assertNull(reader.getCandidates("ㄅ9999"))
        assertNull(reader.getCandidates("ㄅㄅ0000x"))
    }

    private fun readerOf(vararg entries: Pair<String, String>): SortedTsvDictionary =
        SortedTsvDictionary(dictionaryText(entries.toList()).toByteArray(Charsets.UTF_8))

    private fun dictionaryText(
        entries: List<Pair<String, String>>,
        lineSeparator: String = "\n"
    ): String = buildString {
        append("# generated fixture")
        append(lineSeparator)
        append("# key<TAB>candidates")
        append(lineSeparator)
        for ((key, candidates) in sortedEntries(entries)) {
            append(key)
            append('\t')
            append(candidates)
            append(lineSeparator)
        }
    }

    private fun sortedEntries(entries: List<Pair<String, String>>): List<Pair<String, String>> =
        entries.sortedWith { left, right ->
            SortedTsvDictionary.compareKeys(left.first, right.first)
        }

    private fun compareCodePoints(left: String, right: String): Int {
        val leftPoints = left.codePoints().toArray()
        val rightPoints = right.codePoints().toArray()
        for (index in 0 until minOf(leftPoints.size, rightPoints.size)) {
            if (leftPoints[index] != rightPoints[index]) {
                return leftPoints[index].compareTo(rightPoints[index])
            }
        }
        return leftPoints.size.compareTo(rightPoints.size)
    }
}
