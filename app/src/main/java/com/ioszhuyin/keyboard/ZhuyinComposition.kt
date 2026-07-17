package com.ioszhuyin.keyboard

internal data class ZhuyinSegment(
    val text: String,
    val start: Int,
    val end: Int,
    val hasTone: Boolean
)

internal data class CandidateChoice(
    val text: String,
    val reading: String,
    val start: Int,
    val end: Int,
    val isProvisional: Boolean = false
)

internal data class CandidateMatch(
    val reading: String,
    val candidates: List<String>,
    val start: Int,
    val end: Int,
    val isProvisional: Boolean = false,
    val choices: List<CandidateChoice> = candidates.map { candidate ->
        CandidateChoice(
            text = candidate,
            reading = reading,
            start = start,
            end = end,
            isProvisional = isProvisional
        )
    }
)

internal object ZhuyinComposition {
    private const val MAX_SYLLABLE_LENGTH = 3
    private const val MAX_COMPOSED_SYLLABLES = 3
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
        preferredPrefixCandidatesForReading: (String) -> List<String> = { emptyList() },
        prefixCandidatesForReading: (String) -> List<String> = { emptyList() },
        candidatesForReading: (String) -> List<String>
    ): CandidateMatch? {
        if (raw.isEmpty()) return null
        val candidateEnds = (segments.map { it.end } + raw.length)
            .filter { it in 1..raw.length }
            .distinct()
            .sortedDescending()

        val fullCandidates = candidatesForReading(raw)
        if (fullCandidates.isNotEmpty()) {
            return withLeadingCharacterAlternatives(
                match = CandidateMatch(
                    reading = raw,
                    candidates = fullCandidates,
                    start = 0,
                    end = raw.length
                ),
                raw = raw,
                segments = segments,
                candidatesForReading = candidatesForReading
            )
        }

        // Prefer an explicitly prioritized multi-syllable prefix (currently a
        // user-dictionary phrase) over a synthesized character-by-character
        // candidate for the entire buffer. Generic dictionary-prefix fallback
        // stays below synthesis; every multi-syllable result is mixed with
        // leading-character alternatives before it is shown.
        val phrasePrefixEnds = segments
            .drop(1)
            .map { it.end }
            .filter { it in 1 until raw.length }
            .distinct()
            .sortedDescending()
        for (end in phrasePrefixEnds) {
            val reading = raw.substring(0, end)
            if (preferredPrefixCandidatesForReading(reading).isNotEmpty()) {
                val candidates = candidatesForReading(reading)
                return withLeadingCharacterAlternatives(
                    match = CandidateMatch(
                        reading = reading,
                        candidates = candidates,
                        start = 0,
                        end = end
                    ),
                    raw = raw,
                    segments = segments,
                    candidatesForReading = candidatesForReading
                )
            }
        }

        val composedCandidates = composeSyllableCandidates(
            raw = raw,
            segments = segments,
            candidatesForReading = candidatesForReading
        )
        if (composedCandidates.isNotEmpty()) {
            return withLeadingCharacterAlternatives(
                match = CandidateMatch(
                    reading = raw,
                    candidates = composedCandidates,
                    start = 0,
                    end = raw.length
                ),
                raw = raw,
                segments = segments,
                candidatesForReading = candidatesForReading
            )
        }

        if (segments.any { !it.hasTone }) {
            val prefixCandidates = prioritizePrefixCandidates(
                candidates = prefixCandidatesForReading(raw),
                raw = raw,
                segments = segments,
                candidatesForReading = candidatesForReading
            )
            if (prefixCandidates.isNotEmpty()) {
                return withLeadingCharacterAlternatives(
                    match = CandidateMatch(
                        reading = raw,
                        candidates = prefixCandidates,
                        start = 0,
                        end = raw.length,
                        isProvisional = true
                    ),
                    raw = raw,
                    segments = segments,
                    candidatesForReading = candidatesForReading
                )
            }
        }

        for (end in candidateEnds.filter { it < raw.length }) {
            val reading = raw.substring(0, end)
            val candidates = candidatesForReading(reading)
            if (candidates.isNotEmpty()) {
                return withLeadingCharacterAlternatives(
                    match = CandidateMatch(
                        reading = reading,
                        candidates = candidates,
                        start = 0,
                        end = end
                    ),
                    raw = raw,
                    segments = segments,
                    candidatesForReading = candidatesForReading
                )
            }
        }
        return null
    }

    private fun withLeadingCharacterAlternatives(
        match: CandidateMatch,
        raw: String,
        segments: List<ZhuyinSegment>,
        candidatesForReading: (String) -> List<String>
    ): CandidateMatch {
        if (match.start != 0 || match.candidates.isEmpty()) return match
        val leadingSegment = segments.firstOrNull {
            it.start == 0 && it.end in 1 until match.end
        } ?: return match
        val leadingReading = raw.substring(0, leadingSegment.end)
        val leadingChoices = candidatesForReading(leadingReading)
            .asSequence()
            .filter(::isSingleCodePoint)
            .map { candidate ->
                CandidateChoice(
                    text = candidate,
                    reading = leadingReading,
                    start = 0,
                    end = leadingSegment.end
                )
            }
            .toList()
        if (leadingChoices.isEmpty()) return match

        val mixedChoices = buildList {
            add(match.choices.first())
            addAll(leadingChoices)
            addAll(match.choices.drop(1))
        }.distinctBy { choice -> Triple(choice.text, choice.start, choice.end) }
        return match.copy(
            candidates = mixedChoices.map(CandidateChoice::text),
            choices = mixedChoices
        )
    }

    private fun prioritizePrefixCandidates(
        candidates: List<String>,
        raw: String,
        segments: List<ZhuyinSegment>,
        candidatesForReading: (String) -> List<String>
    ): List<String> {
        if (candidates.size < 2) return candidates
        val leadingSegment = segments.firstOrNull {
            it.start == 0 && it.end < raw.length
        } ?: return candidates
        val leadingChoices = candidatesForReading(leadingSegment.text)
            .filter(::isSingleCodePoint)
        if (leadingChoices.isEmpty()) return candidates

        return candidates.withIndex()
            .sortedWith(
                compareBy<IndexedValue<String>> { indexed ->
                    leadingChoices.indexOfFirst(indexed.value::startsWith)
                        .takeIf { it >= 0 }
                        ?: Int.MAX_VALUE
                }.thenBy { it.index }
            )
            .map { it.value }
    }

    private fun composeSyllableCandidates(
        raw: String,
        segments: List<ZhuyinSegment>,
        candidatesForReading: (String) -> List<String>
    ): List<String> {
        if (
            segments.size < 2 ||
            segments.size > MAX_COMPOSED_SYLLABLES ||
            segments.firstOrNull()?.start != 0 ||
            segments.lastOrNull()?.end != raw.length ||
            segments.any { !it.hasTone }
        ) {
            return emptyList()
        }

        data class RankedCombination(val text: String, val rank: Int, val order: Int)

        var nextOrder = 0
        var combinations = listOf(RankedCombination("", rank = 0, order = nextOrder++))
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
                .flatMap { prefix ->
                    choices.asSequence().mapIndexed { choiceIndex, choice ->
                        RankedCombination(
                            text = prefix.text + choice,
                            rank = prefix.rank + choiceIndex,
                            order = nextOrder++
                        )
                    }
                }
                .sortedWith(compareBy<RankedCombination> { it.rank }.thenBy { it.order })
                .distinctBy { it.text }
                .take(MAX_FALLBACK_CANDIDATES)
                .toList()
        }
        return combinations.map { it.text }
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
