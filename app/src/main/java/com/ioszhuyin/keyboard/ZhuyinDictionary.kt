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

    val keys: Set<String> = object : AbstractSet<String>() {
        override val size: Int by lazy(LazyThreadSafetyMode.PUBLICATION) {
            countEntries()
        }

        override fun contains(element: String): Boolean = findLine(element) != null

        override fun iterator(): Iterator<String> = keySequence().iterator()
    }

    fun getCandidates(key: String): List<String>? {
        if (key.isEmpty()) return null
        val line = findLine(key) ?: return null
        val tab = findTab(line.start, line.end)
        if (tab <= line.start || tab >= line.end - 1) return null

        val candidates = decode(tab + 1, contentEnd(line.start, line.end))
            .split(' ')
            .filter { it.isNotBlank() }
            .distinct()
        return candidates.takeIf { it.isNotEmpty() }
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

    companion object {
        private const val COMMENT = '#'.code
        private const val SPACE = ' '.code
        private const val TAB = '\t'.code
        private const val CARRIAGE_RETURN = '\r'.code
        private const val LINE_FEED = '\n'.code

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
