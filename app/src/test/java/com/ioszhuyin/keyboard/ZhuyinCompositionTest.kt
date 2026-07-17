package com.ioszhuyin.keyboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ZhuyinCompositionTest {
    private val knownSyllables = setOf("ㄓㄨㄥ", "ㄏㄨㄚ", "ㄖㄣ", "ㄓㄜ", "ㄧ", "ㄅㄢ")

    @Test
    fun splitsContinuousTonedReadingAtSyllableBoundaries() {
        val segments = ZhuyinComposition.splitSegments(
            text = "ㄓㄨㄥˉㄏㄨㄚˊㄖㄣˊ",
            isKnownUntonedSyllable = knownSyllables::contains
        )

        assertEquals(listOf("ㄓㄨㄥˉ", "ㄏㄨㄚˊ", "ㄖㄣˊ"), segments.map { it.text })
        assertEquals(listOf(4, 8, 11), segments.map { it.end })
        assertEquals(listOf(true, true, true), segments.map { it.hasTone })
    }

    @Test
    fun splitsContinuousUntonedReadingGreedilyIntoKnownSyllables() {
        val segments = ZhuyinComposition.splitSegments(
            text = "ㄓㄨㄥㄏㄨㄚ",
            isKnownUntonedSyllable = knownSyllables::contains
        )

        assertEquals(listOf("ㄓㄨㄥ", "ㄏㄨㄚ"), segments.map { it.text })
        assertEquals(listOf(3, 6), segments.map { it.end })
    }

    @Test
    fun prefersWholePhraseWhenDictionaryContainsIt() {
        val raw = "ㄓㄨㄥˉㄏㄨㄚˊ"
        val segments = ZhuyinComposition.splitSegments(raw, knownSyllables::contains)

        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments) { reading ->
            when (reading) {
                raw -> listOf("中華")
                "ㄓㄨㄥˉ" -> listOf("中")
                else -> emptyList()
            }
        }

        assertNotNull(match)
        assertEquals(raw, match?.reading)
        assertEquals(listOf("中華"), match?.candidates)
        assertEquals(raw.length, match?.end)
    }

    @Test
    fun fallsBackToLongestConvertiblePhrasePrefixAndLeavesSuffix() {
        val raw = "ㄓㄨㄥˉㄏㄨㄚˊㄖㄣˊ"
        val segments = ZhuyinComposition.splitSegments(raw, knownSyllables::contains)

        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments) { reading ->
            when (reading) {
                "ㄓㄨㄥˉㄏㄨㄚˊ" -> listOf("中華")
                "ㄓㄨㄥˉ" -> listOf("中")
                else -> emptyList()
            }
        }

        assertNotNull(match)
        assertEquals("ㄓㄨㄥˉㄏㄨㄚˊ", match?.reading)
        assertEquals(listOf("中華"), match?.candidates)
        assertEquals("ㄖㄣˊ", raw.substring(match?.end ?: 0))
    }

    @Test
    fun manualPhrasePrefixLeavesCompletedAndIncompleteSuffix() {
        val raw = "ㄋㄧˇㄐㄧㄚˉㄖㄣˊㄅ"
        val segments = ZhuyinComposition.splitSegments(raw) { value ->
            value in setOf("ㄋㄧ", "ㄐㄧㄚ", "ㄖㄣ")
        }

        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments) { reading ->
            when (reading) {
                "ㄋㄧˇㄐㄧㄚˉ" -> listOf("你家")
                else -> emptyList()
            }
        }

        assertNotNull(match)
        assertEquals("ㄋㄧˇㄐㄧㄚˉ", match?.reading)
        assertEquals(listOf("你家"), match?.candidates)
        assertEquals("ㄖㄣˊㄅ", raw.substring(match?.end ?: 0))
    }

    @Test
    fun manualPhrasePrefixOutranksCompletedSyllableFallback() {
        val raw = "ㄋㄧˇㄐㄧㄚˉㄖㄣˊ"
        val segments = ZhuyinComposition.splitSegments(raw) { value ->
            value in setOf("ㄋㄧ", "ㄐㄧㄚ", "ㄖㄣ")
        }

        val match = ZhuyinComposition.resolveLeadingCandidates(
            raw = raw,
            segments = segments,
            preferredPrefixCandidatesForReading = { reading ->
                if (reading == "ㄋㄧˇㄐㄧㄚˉ") listOf("你家") else emptyList()
            },
            prefixCandidatesForReading = { listOf("你家人") },
            candidatesForReading = { reading ->
                when (reading) {
                    "ㄋㄧˇㄐㄧㄚˉ" -> listOf("你家")
                    "ㄋㄧˇ" -> listOf("你")
                    "ㄐㄧㄚˉ" -> listOf("家")
                    "ㄖㄣˊ" -> listOf("人")
                    else -> emptyList()
                }
            }
        )

        assertNotNull(match)
        assertEquals("ㄋㄧˇㄐㄧㄚˉ", match?.reading)
        assertEquals(listOf("你家"), match?.candidates)
        assertEquals("ㄖㄣˊ", raw.substring(match?.end ?: 0))
    }

    @Test
    fun incompleteReadingUsesProvisionalPrefixCandidates() {
        val raw = "ㄋ"
        val segments = ZhuyinComposition.splitSegments(raw) { false }

        val match = ZhuyinComposition.resolveLeadingCandidates(
            raw = raw,
            segments = segments,
            prefixCandidatesForReading = { listOf("那", "你") },
            candidatesForReading = { emptyList() }
        )

        assertEquals(listOf("那", "你"), match?.candidates)
        assertEquals(raw.length, match?.end)
        assertEquals(true, match?.isProvisional)
    }

    @Test
    fun exactCandidatesStillOutrankPrefixCandidates() {
        val raw = "ㄋㄧ"
        val segments = ZhuyinComposition.splitSegments(raw) { it == raw }

        val match = ZhuyinComposition.resolveLeadingCandidates(
            raw = raw,
            segments = segments,
            prefixCandidatesForReading = { listOf("泥巴") },
            candidatesForReading = { reading ->
                if (reading == raw) listOf("你", "呢") else emptyList()
            }
        )

        assertEquals(listOf("你", "呢"), match?.candidates)
        assertEquals(false, match?.isProvisional)
    }

    @Test
    fun phrasePrefixPrefersCandidateMatchingCompletedLeadingSyllable() {
        val raw = "ㄋㄧˇㄏ"
        val segments = ZhuyinComposition.splitSegments(raw) { it == "ㄋㄧ" }

        val match = ZhuyinComposition.resolveLeadingCandidates(
            raw = raw,
            segments = segments,
            prefixCandidatesForReading = { listOf("擬合", "你好") },
            candidatesForReading = { reading ->
                if (reading == "ㄋㄧˇ") listOf("你", "妳", "擬") else emptyList()
            }
        )

        assertEquals(listOf("你好", "擬合"), match?.candidates)
        assertEquals(true, match?.isProvisional)
    }

    @Test
    fun composesMissingPhraseFromSyllablesAndNormalizesYiToneSandhi() {
        val raw = "ㄓㄜˋㄧˋㄅㄢˇ"
        val segments = ZhuyinComposition.splitSegments(raw, knownSyllables::contains)

        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments) { reading ->
            when (reading) {
                "ㄓㄜˋㄧˋ" -> listOf("摺椅")
                "ㄓㄜˋ" -> listOf("這")
                "ㄧˉ" -> listOf("一")
                "ㄧˋ" -> listOf("意")
                "ㄅㄢˇ" -> listOf("版")
                else -> emptyList()
            }
        }

        assertNotNull(match)
        assertEquals(raw, match?.reading)
        assertEquals("這一版", match?.candidates?.first())
        assertEquals(raw.length, match?.end)
    }

    @Test
    fun composesBuFromCanonicalToneBeforeFourthTone() {
        val raw = "ㄅㄨˊㄕˋ"
        val segments = ZhuyinComposition.splitSegments(raw) { it in setOf("ㄅㄨ", "ㄕ") }

        val match = ZhuyinComposition.resolveLeadingCandidates(raw, segments) { reading ->
            when (reading) {
                "ㄅㄨˋ" -> listOf("不")
                "ㄅㄨˊ" -> listOf("醭")
                "ㄕˋ" -> listOf("是")
                else -> emptyList()
            }
        }

        assertEquals("不是", match?.candidates?.first())
    }
}
