package com.ioszhuyin.keyboard

internal data class ZhuyinSegment(
    val text: String,
    val start: Int,
    val end: Int,
    val hasTone: Boolean
)

internal data class CandidateMatch(
    val reading: String,
    val candidates: List<String>,
    val start: Int,
    val end: Int
)

internal object ZhuyinComposition {
    private const val MAX_SYLLABLE_LENGTH = 3
    private const val MAX_CHOICES_PER_SYLLABLE = 3
    private const val MAX_FALLBACK_CANDIDATES = 9
    private val toneChars = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')

    fun splitSegments(
        text: String,
        isKnownUntonedSyllable: (String) -> Boolean
    ): List<ZhuyinSegment> {
        if (text.isEmpty()) return emptyList()
        val result = mutableListOf<ZhuyinSegment>()
        var index = 0
        while (index < text.length) {
            if (text[index] in toneChars) {
                result.add(
                    ZhuyinSegment(
                        text = text.substring(index, index + 1),
                        start = index,
                        end = index + 1,
                        hasTone = true
                    )
                )
                index++
                continue
            }

            val runStart = index
            while (index < text.length && text[index] !in toneChars) index++
            splitBaseRun(text, runStart, index, result, isKnownUntonedSyllable)

            if (index < text.length && text[index] in toneChars && result.isNotEmpty()) {
                val last = result.removeAt(result.lastIndex)
                result.add(
                    ZhuyinSegment(
                        text = text.substring(last.start, index + 1),
                        start = last.start,
                        end = index + 1,
                        hasTone = true
                    )
                )
                index++
            }
        }
        return result
    }

    fun resolveLeadingCandidates(
        raw: String,
        segments: List<ZhuyinSegment>,
        candidatesForReading: (String) -> List<String>
    ): CandidateMatch? {
        if (raw.isEmpty()) return null
        val candidateEnds = (segments.map { it.end } + raw.length)
            .filter { it in 1..raw.length }
            .distinct()
            .sortedDescending()

        val fullCandidates = candidatesForReading(raw)
        if (fullCandidates.isNotEmpty()) {
            return CandidateMatch(
                reading = raw,
                candidates = fullCandidates,
                start = 0,
                end = raw.length
            )
        }

        val composedCandidates = composeSyllableCandidates(
            raw = raw,
            segments = segments,
            candidatesForReading = candidatesForReading
        )
        if (composedCandidates.isNotEmpty()) {
            return CandidateMatch(
                reading = raw,
                candidates = composedCandidates,
                start = 0,
                end = raw.length
            )
        }

        for (end in candidateEnds.filter { it < raw.length }) {
            val reading = raw.substring(0, end)
            val candidates = candidatesForReading(reading)
            if (candidates.isNotEmpty()) {
                return CandidateMatch(
                    reading = reading,
                    candidates = candidates,
                    start = 0,
                    end = end
                )
            }
        }
        return null
    }

    private fun composeSyllableCandidates(
        raw: String,
        segments: List<ZhuyinSegment>,
        candidatesForReading: (String) -> List<String>
    ): List<String> {
        if (
            segments.size < 2 ||
            segments.firstOrNull()?.start != 0 ||
            segments.lastOrNull()?.end != raw.length ||
            segments.any { !it.hasTone }
        ) {
            return emptyList()
        }

        var combinations = listOf("")
        segments.forEachIndexed { index, segment ->
            val readings = linkedSetOf<String>()
            toneSandhiReading(segment, segments.getOrNull(index + 1))?.let(readings::add)
            readings.add(segment.text)

            val choices = linkedSetOf<String>()
            readings.forEach { reading ->
                candidatesForReading(reading)
                    .asSequence()
                    .filter(::isSingleCodePoint)
                    .take(MAX_CHOICES_PER_SYLLABLE)
                    .forEach(choices::add)
            }
            if (choices.isEmpty()) return emptyList()

            combinations = combinations
                .asSequence()
                .flatMap { prefix -> choices.asSequence().map { prefix + it } }
                .distinct()
                .take(MAX_FALLBACK_CANDIDATES)
                .toList()
        }
        return combinations
    }

    private fun toneSandhiReading(
        segment: ZhuyinSegment,
        next: ZhuyinSegment?
    ): String? {
        val currentTone = segment.text.lastOrNull()?.takeIf { it in toneChars } ?: return null
        val nextTone = next?.text?.lastOrNull()?.takeIf { it in toneChars } ?: return null
        val base = segment.text.dropLast(1)
        return when {
            base == "ㄧ" && currentTone == 'ˊ' && nextTone == 'ˋ' -> "ㄧˉ"
            base == "ㄧ" && currentTone == 'ˋ' && nextTone in setOf('ˉ', 'ˊ', 'ˇ') -> "ㄧˉ"
            base == "ㄅㄨ" && currentTone == 'ˊ' && nextTone == 'ˋ' -> "ㄅㄨˋ"
            else -> null
        }
    }

    private fun isSingleCodePoint(value: String): Boolean =
        value.isNotEmpty() && value.codePointCount(0, value.length) == 1

    private fun splitBaseRun(
        text: String,
        start: Int,
        end: Int,
        result: MutableList<ZhuyinSegment>,
        isKnownUntonedSyllable: (String) -> Boolean
    ) {
        var position = start
        while (position < end) {
            var foundEnd = -1
            for (length in minOf(MAX_SYLLABLE_LENGTH, end - position) downTo 1) {
                val candidate = text.substring(position, position + length)
                if (isKnownUntonedSyllable(candidate)) {
                    foundEnd = position + length
                    break
                }
            }
            if (foundEnd < 0) foundEnd = position + 1
            result.add(
                ZhuyinSegment(
                    text = text.substring(position, foundEnd),
                    start = position,
                    end = foundEnd,
                    hasTone = false
                )
            )
            position = foundEnd
        }
    }
}
