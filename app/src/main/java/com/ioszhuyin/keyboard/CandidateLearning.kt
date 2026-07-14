package com.ioszhuyin.keyboard

import android.content.Context
import android.content.SharedPreferences

internal object CandidateLearningSettings {
    private const val PREFS_NAME = "candidate_learning_settings"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_RECORDS_REVISION = "records_revision"

    private fun preferences(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean =
        preferences(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        preferences(context)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun notifyRecordsChanged(context: Context) {
        preferences(context)
            .edit()
            .putLong(KEY_RECORDS_REVISION, System.nanoTime())
            .apply()
    }

    fun registerListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        preferences(context).registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(
        context: Context,
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) {
        preferences(context).unregisterOnSharedPreferenceChangeListener(listener)
    }

    fun isRecordsChange(key: String?): Boolean = key == KEY_RECORDS_REVISION
}

internal object LegacyCandidateLearning {
    const val PREFS_NAME = "zhuyin_freq"
    const val FREQ_KEY = "freq_data"

    fun parse(raw: String): Map<String, Int> {
        val result = linkedMapOf<String, Int>()
        raw.split('|').forEach { entry ->
            val separator = entry.lastIndexOf(':')
            if (separator <= 0 || separator >= entry.lastIndex) return@forEach
            val word = entry.substring(0, separator)
            val count = entry.substring(separator + 1).toIntOrNull() ?: return@forEach
            if (word.isEmpty() || count <= 0) return@forEach
            result[word] = maxOf(result[word] ?: 0, count)
        }
        return result
    }
}

internal object CandidateRanking {
    fun order(
        manualCandidates: List<String>,
        dictionaryCandidates: List<String>,
        learnedCounts: Map<String, Int>
    ): List<String> {
        val manual = manualCandidates.distinct()
        val manualSet = manual.toSet()
        val dictionary = dictionaryCandidates
            .filter { it !in manualSet }
            .distinct()
            .withIndex()
            .sortedWith(
                compareByDescending<IndexedValue<String>> {
                    learnedCounts[it.value] ?: 0
                }.thenBy { it.index }
            )
            .map { it.value }
        return manual + dictionary
    }
}
