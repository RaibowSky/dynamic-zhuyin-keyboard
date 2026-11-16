package com.ioszhuyin.keyboard

import org.junit.Assert.*
import org.junit.Test

class SentenceDecoderTest {
    @Test fun incompleteFinalSyllableProducesProvisionalSentenceAndExactCorrection() {
        val raw = "abcd"
        val segments = segments(raw).mapIndexed { index, segment ->
            segment.copy(hasTone = index < 3)
        }
        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments,
            prefixCandidatesForReading = { if (it == "d") listOf("丁") else emptyList() },
            candidatesForReading = {
                when (it) { "ab" -> listOf("甲乙"); "a" -> listOf("甲"); "c" -> listOf("丙"); else -> emptyList() }
            })!!
        assertEquals("甲乙丙丁", match.candidates.first())
        assertTrue(match.choices.first().isProvisional)
        assertTrue(match.choices.any { it.text == "甲" && it.end == 1 && !it.isProvisional })
    }

    private fun segments(raw: String) = raw.indices.map {
        ZhuyinSegment(raw.substring(it, it + 1), it, it + 1, true)
    }

    @Test fun combinesCompetingPhrasesAcrossLongInputDeterministically() {
        val words = mapOf("a" to listOf("A"), "b" to listOf("B"), "c" to listOf("C"),
            "d" to listOf("D"), "e" to listOf("E"), "f" to listOf("F"),
            "abc" to listOf("長詞"), "def" to listOf("片語"), "ab" to listOf("短詞"))
        fun decode() = ZhuyinComposition.resolveLeadingCandidates("abcdef", segments("abcdef")) {
            words[it].orEmpty()
        }!!
        assertEquals("長詞片語", decode().candidates.first())
        assertEquals(6, decode().choices.first().end)
        assertEquals(decode(), decode())
        assertTrue(decode().choices.any { it.text == "A" && it.end == 1 })
    }

    @Test fun userPhraseWinsBetweenEquivalentSegmentations() {
        val match = ZhuyinComposition.resolveLeadingCandidates("abcd", segments("abcd"),
            preferredPrefixCandidatesForReading = { if (it == "bc") listOf("私詞") else emptyList() },
            candidatesForReading = {
                when (it) {
                    "a" -> listOf("A"); "b" -> listOf("B"); "c" -> listOf("C"); "d" -> listOf("D")
                    "ab" -> listOf("常詞"); "bc" -> listOf("私詞"); else -> emptyList()
                }
            })!!
        assertEquals("A私詞D", match.candidates.first())
    }

    @Test fun longInputHasNoSentenceLengthCeilingAndCorrectsFromTheStart() {
        val raw = "a".repeat(80)
        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments(raw)) {
            if (it == "a") listOf("甲", "乙") else emptyList()
        }!!
        assertEquals(80, match.choices.first().end)
        assertEquals("甲".repeat(80), match.candidates.first())
        assertEquals(1, match.choices.count { it.end == 80 })
        assertEquals(listOf("甲", "乙"), match.choices.drop(1).map { it.text })
        assertTrue(match.choices.drop(1).all { it.end == 1 })
    }

    @Test fun wrongSentenceIsFollowedByLeadingWordsLongestFirstNotMoreSentences() {
        val words = mapOf("a" to listOf("A", "Ａ"), "b" to listOf("B"), "c" to listOf("C"),
            "d" to listOf("D"), "abc" to listOf("長詞"), "ab" to listOf("短詞", "另詞"), "cd" to listOf("尾詞"))
        val match = ZhuyinComposition.resolveLeadingCandidates("abcd", segments("abcd")) {
            words[it].orEmpty()
        }!!
        assertEquals(listOf("短詞尾詞", "長詞", "短詞", "另詞", "A", "Ａ"), match.candidates)
        assertEquals(listOf(4, 3, 2, 2, 1, 1), match.choices.map { it.end })
    }

}
