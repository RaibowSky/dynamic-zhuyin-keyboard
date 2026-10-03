package com.ioszhuyin.keyboard

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bundled next-word table derived from McBopomofo phrase frequencies.
 * Generated asset: app/src/main/assets/next_word.tsv (tools/build_next_word_table.py).
 */
internal object NextWordTable {
    private const val ASSET_NAME = "next_word.tsv"
    private const val MAX_SUGGESTIONS = 8

    @Volatile private var table: Map<String, List<String>>? = null
    private val preloading = AtomicBoolean(false)

    /** Parses the asset off the main thread so the first committed candidate does not stall the IME. */
    fun preload(context: Context) {
        if (table != null || !preloading.compareAndSet(false, true)) return
        val appContext = context.applicationContext
        Thread({ load(appContext) }, "next-word-preload").apply { isDaemon = true }.start()
    }

    /** Continuations for the committed word, falling back to its last two and last character. */
    fun continuations(context: Context, previous: String): List<String> {
        val loaded = table ?: return emptyList<String>().also { preload(context) }
        return lookup(loaded, previous)
    }

    internal fun lookup(table: Map<String, List<String>>, previous: String): List<String> {
        val results = LinkedHashSet<String>()
        for (size in minOf(previous.length, 3) downTo 1) {
            table[previous.takeLast(size)]?.let(results::addAll)
            if (results.size >= MAX_SUGGESTIONS) break
        }
        return results.take(MAX_SUGGESTIONS)
    }

    internal fun parse(lines: Sequence<String>): Map<String, List<String>> {
        val parsed = HashMap<String, List<String>>()
        for (line in lines) {
            if (line.isEmpty() || line.startsWith("#")) continue
            val key = line.substringBefore('\t')
            val values = line.substringAfter('\t', "").split(' ').filter { it.isNotEmpty() }
            if (key.isNotEmpty() && values.isNotEmpty()) parsed[key] = values
        }
        return parsed
    }

    @Synchronized
    private fun load(context: Context): Map<String, List<String>> {
        table?.let { return it }
        val loaded = runCatching {
            context.assets.open(ASSET_NAME).bufferedReader(Charsets.UTF_8).use { parse(it.lineSequence()) }
        }.getOrDefault(emptyMap())
        table = loaded
        return loaded
    }
}
