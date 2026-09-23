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

        val composedCandidates = composeSyllableCandidates(
            raw = raw,
            segments = segments,
            preferredCandidates = preferredPrefixCandidatesForReading,
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

        // When a complete path is unavailable, preserve manual prefix correction.
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
            val partial = composeSyllableCandidates(raw, segments,
                preferredPrefixCandidatesForReading, candidatesForReading, prefixCandidatesForReading)
            if (partial.isNotEmpty()) {
                return withLeadingCharacterAlternatives(
                    CandidateMatch(raw, partial, 0, raw.length, isProvisional = true),
                    raw, segments, candidatesForReading)
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

    // A bounded beam at each syllable boundary; input length is not capped.
    // Candidate order already incorporates bundled frequency and allowed learning.
    private fun composeSyllableCandidates(
        raw: String,
        segments: List<ZhuyinSegment>,
        preferredCandidates: (String) -> List<String>,
        candidatesForReading: (String) -> List<String>,
        partialCandidates: (String) -> List<String> = { emptyList() }
    ): List<String> {
        if (segments.size < 2 || segments.first().start != 0 ||
            segments.last().end != raw.length || segments.dropLast(1).any { !it.hasTone }) return emptyList()
        data class Path(val text: String, val cost: Int)
        val beams = Array(segments.size + 1) { mutableListOf<Path>() }
        beams[0].add(Path("", 0))
        val order = compareBy<Path> { it.cost }.thenBy { it.text }
        for (start in segments.indices) {
            if (beams[start].isEmpty()) continue
            val prefixes = beams[start].sortedWith(order).distinctBy { it.text }
                .take(MAX_FALLBACK_CANDIDATES)
            for (end in start + 1..minOf(segments.size, start + 16)) {
                val span = end - start
                val reading = raw.substring(segments[start].start, segments[end - 1].end)
                val normalized = (start until end).joinToString("") { index ->
                    toneSandhiReading(segments[index], segments.getOrNull(index + 1))
                        ?: segments[index].text
                }
                val preferred = preferredCandidates(reading).toSet()
                val incomplete = end == segments.size && !segments.last().hasTone
                val normalizedChoices = if (incomplete) partialCandidates(reading) else candidatesForReading(normalized)
                val choices = (normalizedChoices +
                    if (normalized != reading) candidatesForReading(reading) else emptyList())
                    .distinct().filter { span > 1 || isSingleCodePoint(it) }.take(4)
                for ((rank, choice) in choices.withIndex()) {
                    // Fewer phrase boundaries beat naive character concatenation.
                    // Bonuses are bounded, so one learned word cannot dominate a sentence.
                    val sandhiPenalty = if (normalized != reading && choice !in normalizedChoices && choice !in preferred) 16 else 0
                    val edgeCost = 12 + rank * 2 - minOf(span, 8) * 2 + sandhiPenalty -
                        (if (choice in preferred) 6 else 0)
                    for (prefix in prefixes) beams[end].add(Path(prefix.text + choice, prefix.cost + edgeCost))
                }
                if (beams[end].size > MAX_FALLBACK_CANDIDATES) {
                    val best = beams[end].sortedWith(order).distinctBy { it.text }
                        .take(MAX_FALLBACK_CANDIDATES)
                    beams[end].clear()
                    beams[end].addAll(best)
                }
            }
            beams[start].clear()
        }
        return beams.last().sortedWith(order).distinctBy { it.text }.map { it.text }
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
