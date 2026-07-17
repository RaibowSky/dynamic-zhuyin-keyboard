package com.ioszhuyin.keyboard

import android.content.Context
import android.os.ParcelFileDescriptor
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Zhuyin dictionary backed by a generated CC-CEDICT asset.
 *
 * Source data: CC-CEDICT, CC BY-SA 4.0.
 * Generated asset: app/src/main/assets/zhuyin_cedict.tsv
 */
object ZhuyinDictionary {

    private const val ASSET_NAME = "zhuyin_cedict.tsv"

    @Volatile
    private var dictionary: SortedTsvDictionary? = null
    @Volatile
    private var syllableMetadata: SyllableMetadata? = null

    fun initialize(context: Context) {
        if (dictionary != null) return

        synchronized(this) {
            if (dictionary == null) {
                dictionary = SortedTsvDictionary(openAssetBuffer(context))
            }
        }
    }

    fun getCandidates(key: String): List<String>? = dictionary?.getCandidates(key)

    fun getPrefixCandidates(prefix: String, limit: Int): List<String> =
        dictionary?.getPrefixCandidates(prefix, limit).orEmpty()

    fun getAllKeys(): Set<String>? = dictionary?.keys ?: emptySet()

    fun isLegalBaseSyllable(key: String): Boolean = key in metadata().legalBaseSyllables

    fun getNextSymbols(prefix: String): Set<String>? = metadata().prefixToNext[prefix]

    private fun metadata(): SyllableMetadata {
        syllableMetadata?.let { return it }
        val source = dictionary ?: return SyllableMetadata.EMPTY

        synchronized(this) {
            syllableMetadata?.let { return it }
            val bases = source.keySequence()
                .map(::stripTones)
                .filter { value ->
                    value.length in 1..3 && value.all { ch -> ch in ZHUYIN_CHARS }
                }
                .toSet()
            val next = mutableMapOf<String, MutableSet<String>>()
            for (syllable in bases) {
                for (index in 1 until syllable.length) {
                    val prefix = syllable.substring(0, index)
                    val symbol = syllable.substring(index, index + 1)
                    next.getOrPut(prefix) { mutableSetOf() }.add(symbol)
                }
            }
            return SyllableMetadata(bases, next).also { syllableMetadata = it }
        }
    }

    private fun openAssetBuffer(context: Context): ByteBuffer = try {
        context.assets.openFd(ASSET_NAME).use { assetDescriptor ->
            val duplicatedDescriptor = ParcelFileDescriptor.dup(assetDescriptor.fileDescriptor)
            ParcelFileDescriptor.AutoCloseInputStream(duplicatedDescriptor).use { input ->
                input.channel.map(
                    FileChannel.MapMode.READ_ONLY,
                    assetDescriptor.startOffset,
                    assetDescriptor.length
                )
            }
        }
    } catch (_: IOException) {
        // A compressed asset cannot be memory-mapped. The build marks TSV files as
        // noCompress, but retaining this bounded fallback keeps local/test builds usable.
        context.assets.open(ASSET_NAME).use { input ->
            ByteBuffer.wrap(input.readBytes()).asReadOnlyBuffer()
        }
    }

    private fun stripTones(value: String): String =
        value.filter { it !in TONE_CHARS }

    private data class SyllableMetadata(
        val legalBaseSyllables: Set<String>,
        val prefixToNext: Map<String, Set<String>>
    ) {
        companion object {
            val EMPTY = SyllableMetadata(emptySet(), emptyMap())
        }
    }

    private val TONE_CHARS = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')
    private val ZHUYIN_CHARS = setOf(
        'ㄅ', 'ㄆ', 'ㄇ', 'ㄈ', 'ㄉ', 'ㄊ', 'ㄋ', 'ㄌ',
        'ㄍ', 'ㄎ', 'ㄏ', 'ㄐ', 'ㄑ', 'ㄒ',
        'ㄓ', 'ㄔ', 'ㄕ', 'ㄖ', 'ㄗ', 'ㄘ', 'ㄙ',
        'ㄧ', 'ㄨ', 'ㄩ',
        'ㄚ', 'ㄛ', 'ㄜ', 'ㄝ', 'ㄞ', 'ㄟ', 'ㄠ', 'ㄡ',
        'ㄢ', 'ㄣ', 'ㄤ', 'ㄥ', 'ㄦ'
    )
}

/**
 * Low-memory reader for TSV rows sorted by `(Unicode code-point count, key)`.
 *
 * Lookups binary-search line boundaries directly in a read-only [ByteBuffer]. Only
 * the matched row is decoded into candidate strings; keys and values are not retained
 * as a process-wide object graph. Absolute buffer reads keep concurrent lookups safe.
 */
internal class SortedTsvDictionary(source: ByteBuffer) {

    constructor(bytes: ByteArray) : this(ByteBuffer.wrap(bytes))

    private val bytes: ByteBuffer = source.slice().asReadOnlyBuffer()
    private val byteCount: Int = bytes.limit()
    private val dataStart: Int = findDataStart()
    private val lengthRanges: List<LengthRange> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        buildLengthRanges()
    }
    private val lookupCacheLock = Any()
    private val candidateCache = object : LinkedHashMap<String, List<String>>(
        LOOKUP_CACHE_CAPACITY,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<String>>?
        ): Boolean = size > LOOKUP_CACHE_CAPACITY
    }
    private val prefixCandidateCache = object : LinkedHashMap<PrefixCacheKey, List<String>>(
        LOOKUP_CACHE_CAPACITY,
        0.75f,
        true
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<PrefixCacheKey, List<String>>?
        ): Boolean = size > LOOKUP_CACHE_CAPACITY
    }

    val keys: Set<String> = object : AbstractSet<String>() {
        override val size: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
            countEntries()
        }

        override fun contains(element: String): Boolean = findLine(element) != null

        override fun iterator(): Iterator<String> = keySequence().iterator()
    }

    fun getCandidates(key: String): List<String>? {
        if (key.isEmpty()) return null
        synchronized(lookupCacheLock) {
            candidateCache[key]?.let { cached ->
                return cached.takeIf { it.isNotEmpty() }
            }
        }
        val line = findLine(key)
        val candidates = if (line == null) {
            emptyList()
        } else {
            candidatesInLine(line.start, line.end)
        }
        synchronized(lookupCacheLock) {
            candidateCache[key] = candidates
        }
        return candidates.takeIf { it.isNotEmpty() }
    }

    /**
     * Returns a bounded set of candidates whose readings begin with [prefix].
     *
     * The TSV is grouped by code-point length, so a tiny byte-range index plus a
     * binary search per length avoids scanning or retaining the whole dictionary.
     */
    fun getPrefixCandidates(prefix: String, limit: Int): List<String> {
        if (prefix.isEmpty() || limit <= 0) return emptyList()

        val cacheKey = PrefixCacheKey(prefix, limit)
        synchronized(lookupCacheLock) {
            prefixCandidateCache[cacheKey]?.let { return it }
        }

        val prefixLength = prefix.codePointCount(0, prefix.length)
        val scores = linkedMapOf<String, PrefixCandidateScore>()
        var matchingRows = 0
        var firstSeen = 0

        for (range in lengthRanges) {
            if (range.codePointLength < prefixLength) continue
            var start = findFirstPrefixLine(prefix, range) ?: continue
            while (start < range.endExclusive && matchingRows < MAX_PREFIX_MATCHING_ROWS) {
                val end = findLineEnd(start)
                val tab = findTab(start, end)
                if (tab <= start) break
                val key = decode(start, tab)
                if (!key.startsWith(prefix)) break

                candidatesInLine(start, end)
                    .take(MAX_PREFIX_CANDIDATES_PER_ROW)
                    .forEachIndexed { rank, candidate ->
                        val score = scores.getOrPut(candidate) {
                            PrefixCandidateScore(
                                shortestReadingLength = range.codePointLength,
                                bestRank = rank,
                                firstSeen = firstSeen++
                            )
                        }
                        score.appearances++
                        score.shortestReadingLength = minOf(
                            score.shortestReadingLength,
                            range.codePointLength
                        )
                        score.bestRank = minOf(score.bestRank, rank)
                    }
                matchingRows++
                start = nextLineStart(end)
            }
            if (matchingRows >= MAX_PREFIX_MATCHING_ROWS) break
        }

        val candidates = scores.entries
            .sortedWith(
                compareBy<Map.Entry<String, PrefixCandidateScore>> {
                    it.value.shortestReadingLength
                }.thenByDescending {
                    it.value.appearances
                }.thenBy {
                    it.value.bestRank
                }.thenBy {
                    it.value.firstSeen
                }
            )
            .asSequence()
            .map { it.key }
            .take(limit)
            .toList()
        synchronized(lookupCacheLock) {
            prefixCandidateCache[cacheKey] = candidates
        }
        return candidates
    }

    fun keySequence(): Sequence<String> = sequence {
        var start = dataStart
        while (start < byteCount) {
            val end = findLineEnd(start)
            val tab = findTab(start, end)
            if (tab > start) yield(decode(start, tab))
            start = nextLineStart(end)
        }
    }

    private fun findLine(target: String): LineBounds? {
        var low = dataStart
        var high = byteCount
        while (low < high) {
            val middle = low + (high - low) / 2
            val lineStart = findLineStart(middle, low)
            val lineEnd = findLineEnd(lineStart)
            val tab = findTab(lineStart, lineEnd)
            if (tab <= lineStart) return null

            val lineKey = decode(lineStart, tab)
            val comparison = compareKeys(lineKey, target)
            when {
                comparison < 0 -> low = nextLineStart(lineEnd)
                comparison > 0 -> high = lineStart
                else -> return LineBounds(lineStart, lineEnd)
            }
        }
        return null
    }

    private fun buildLengthRanges(): List<LengthRange> {
        val result = mutableListOf<LengthRange>()
        var rangeStart = dataStart
        var currentLength = -1
        var start = dataStart
        while (start < byteCount) {
            val end = findLineEnd(start)
            val tab = findTab(start, end)
            if (tab > start) {
                val key = decode(start, tab)
                val keyLength = key.codePointCount(0, key.length)
                if (currentLength < 0) {
                    currentLength = keyLength
                    rangeStart = start
                } else if (keyLength != currentLength) {
                    result.add(LengthRange(currentLength, rangeStart, start))
                    currentLength = keyLength
                    rangeStart = start
                }
            }
            start = nextLineStart(end)
        }
        if (currentLength >= 0) {
            result.add(LengthRange(currentLength, rangeStart, byteCount))
        }
        return result
    }

    private fun findFirstPrefixLine(prefix: String, range: LengthRange): Int? {
        var low = range.start
        var high = range.endExclusive
        while (low < high) {
            val middle = low + (high - low) / 2
            val lineStart = findLineStart(middle, low)
            val lineEnd = findLineEnd(lineStart)
            val tab = findTab(lineStart, lineEnd)
            if (tab <= lineStart) return null

            val key = decode(lineStart, tab)
            if (compareKeyToPrefix(key, prefix) < 0) {
                low = nextLineStart(lineEnd)
            } else {
                high = lineStart
            }
        }
        return low.takeIf { it < range.endExclusive }
    }

    private fun candidatesInLine(start: Int, end: Int): List<String> {
        val tab = findTab(start, end)
        if (tab <= start || tab >= end - 1) return emptyList()
        return decode(tab + 1, contentEnd(start, end))
            .split(' ')
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun findDataStart(): Int {
        var start = 0
        while (start < byteCount) {
            val end = findLineEnd(start)
            if (!isHeaderOrBlank(start, end)) return start
            start = nextLineStart(end)
        }
        return byteCount
    }

    private fun isHeaderOrBlank(start: Int, end: Int): Boolean {
        if (start >= end) return true
        if (byteAt(start) == COMMENT) return true
        for (index in start until end) {
            when (byteAt(index)) {
                SPACE, TAB, CARRIAGE_RETURN -> Unit
                else -> return false
            }
        }
        return true
    }

    private fun findLineStart(index: Int, floor: Int): Int {
        var cursor = index.coerceAtMost(byteCount - 1)
        while (cursor > floor && byteAt(cursor - 1) != LINE_FEED) cursor--
        return cursor
    }

    private fun findLineEnd(start: Int): Int {
        var cursor = start
        while (cursor < byteCount && byteAt(cursor) != LINE_FEED) cursor++
        return cursor
    }

    private fun nextLineStart(lineEnd: Int): Int =
        if (lineEnd < byteCount) lineEnd + 1 else byteCount

    private fun findTab(start: Int, end: Int): Int {
        for (index in start until end) {
            if (byteAt(index) == TAB) return index
        }
        return -1
    }

    private fun contentEnd(start: Int, end: Int): Int =
        if (end > start && byteAt(end - 1) == CARRIAGE_RETURN) end - 1 else end

    private fun decode(start: Int, end: Int): String {
        if (end <= start) return ""
        val result = ByteArray(end - start)
        for (index in result.indices) result[index] = bytes.get(start + index)
        return result.toString(Charsets.UTF_8)
    }

    private fun countEntries(): Int {
        var count = 0
        var start = dataStart
        while (start < byteCount) {
            val end = findLineEnd(start)
            if (findTab(start, end) > start) count++
            start = nextLineStart(end)
        }
        return count
    }

    private fun byteAt(index: Int): Int = bytes.get(index).toInt() and 0xFF

    private data class LineBounds(val start: Int, val end: Int)

    private data class LengthRange(
        val codePointLength: Int,
        val start: Int,
        val endExclusive: Int
    )

    private data class PrefixCandidateScore(
        var shortestReadingLength: Int,
        var bestRank: Int,
        val firstSeen: Int,
        var appearances: Int = 0
    )

    private data class PrefixCacheKey(
        val prefix: String,
        val limit: Int
    )

    companion object {
        private const val COMMENT = '#'.code
        private const val SPACE = ' '.code
        private const val TAB = '\t'.code
        private const val CARRIAGE_RETURN = '\r'.code
        private const val LINE_FEED = '\n'.code
        private const val MAX_PREFIX_MATCHING_ROWS = 64
        private const val MAX_PREFIX_CANDIDATES_PER_ROW = 4
        private const val LOOKUP_CACHE_CAPACITY = 256

        private fun compareKeyToPrefix(key: String, prefix: String): Int {
            var keyIndex = 0
            var prefixIndex = 0
            while (keyIndex < key.length && prefixIndex < prefix.length) {
                val keyCodePoint = key.codePointAt(keyIndex)
                val prefixCodePoint = prefix.codePointAt(prefixIndex)
                if (keyCodePoint != prefixCodePoint) {
                    return keyCodePoint.compareTo(prefixCodePoint)
                }
                keyIndex += Character.charCount(keyCodePoint)
                prefixIndex += Character.charCount(prefixCodePoint)
            }
            return if (prefixIndex == prefix.length) 0 else -1
        }

        internal fun compareKeys(left: String, right: String): Int {
            val lengthComparison = left.codePointCount(0, left.length)
                .compareTo(right.codePointCount(0, right.length))
            if (lengthComparison != 0) return lengthComparison

            var leftIndex = 0
            var rightIndex = 0
            while (leftIndex < left.length && rightIndex < right.length) {
                val leftCodePoint = left.codePointAt(leftIndex)
                val rightCodePoint = right.codePointAt(rightIndex)
                if (leftCodePoint != rightCodePoint) {
                    return leftCodePoint.compareTo(rightCodePoint)
                }
                leftIndex += Character.charCount(leftCodePoint)
                rightIndex += Character.charCount(rightCodePoint)
            }
            return 0
        }
    }
}
