package com.ioszhuyin.keyboard

import android.content.Context
import kotlin.math.exp
import kotlin.math.ln

/**
 * Predicts the next Zhuyin key from the part of the syllable already typed,
 * using how often each syllable occurs (assets/zhuyin_syllable_frequency.tsv,
 * derived from McBopomofo; see tools/build_syllable_frequency.py).
 */
internal class KeyPrediction(syllableCounts: Map<String, Double>) {
    /** prefix → (next symbol → weight); "" is the start of a syllable. */
    private val next: Map<String, Map<String, Double>>
    private val complete: Set<String>

    init {
        val bases = HashMap<String, Double>()
        for ((syllable, count) in syllableCounts) {
            val base = syllable.filter { it !in TONE_MARKS }
            if (base.isNotEmpty()) bases[base] = (bases[base] ?: 0.0) + count
        }
        val table = HashMap<String, HashMap<String, Double>>()
        for ((base, count) in bases) {
            for (i in base.indices) {
                val row = table.getOrPut(base.substring(0, i)) { HashMap() }
                val symbol = base.substring(i, i + 1)
                row[symbol] = (row[symbol] ?: 0.0) + count
            }
        }
        next = table
        complete = bases.keys
    }

    /**
     * Probability of each next key given the untoned symbols of the current
     * syllable. After a complete syllable (ㄅㄚ), the next key may extend it
     * (ㄅㄚ→ㄅㄢ is not possible, but ㄅ→ㄅㄚ is) or start the next syllable.
     */
    fun priors(syllablePrefix: String): Map<String, Double> = rawPriors(syllablePrefix)
        // Any key that forms a real syllable is plausible, however rare (ㄍ→ㄟ for 給).
        .mapValues { maxOf(it.value, PredictiveHitTesting.PRIOR_FLOOR) }

    private fun rawPriors(syllablePrefix: String): Map<String, Double> {
        val start = normalized(next[""])
        if (syllablePrefix.isEmpty()) return start
        val continuation = normalized(next[syllablePrefix])
        if (continuation.isEmpty()) return start
        if (syllablePrefix !in complete) return continuation
        return (continuation.keys + start.keys).associateWith {
            CONTINUE_WEIGHT * (continuation[it] ?: 0.0) + (1 - CONTINUE_WEIGHT) * (start[it] ?: 0.0)
        }
    }

    private fun normalized(row: Map<String, Double>?): Map<String, Double> {
        if (row.isNullOrEmpty()) return emptyMap()
        val total = row.values.sum()
        return row.mapValues { it.value / total }
    }

    companion object {
        private const val ASSET = "zhuyin_syllable_frequency.tsv"
        private const val CONTINUE_WEIGHT = 0.5
        private val TONE_MARKS = setOf('ˉ', 'ˊ', 'ˇ', 'ˋ', '˙')

        @Volatile private var loaded: KeyPrediction? = null

        fun get(context: Context): KeyPrediction = loaded ?: synchronized(this) {
            loaded ?: KeyPrediction(parse(context.assets.open(ASSET).bufferedReader().readText()))
                .also { loaded = it }
        }

        fun parse(text: String): Map<String, Double> = text.lineSequence()
            .filter { it.isNotBlank() && !it.startsWith("#") }
            .mapNotNull { line ->
                val tab = line.indexOf('\t')
                if (tab <= 0) null else line.substring(0, tab) to (line.substring(tab + 1).toDoubleOrNull() ?: return@mapNotNull null)
            }
            .toMap()
    }
}

/**
 * Resolves a tap among Zhuyin keys like a hidden, predictive target resize.
 * Deliberately conservative: only the edge of a key that cannot continue the
 * current syllable (ㄍ then ㄩ) goes to a likely neighbor; gaps between keys
 * go to the likelier nearby key. Plausible keys never shrink.
 */
internal object PredictiveHitTesting {
    /** The middle of a drawn key always selects it, whatever the prediction. */
    private const val CORE_FRACTION = 0.6f
    /** Touch spread, in key widths and heights. */
    private const val SIGMA = 0.45
    /** Below this a key is treated as not continuing the syllable. */
    const val PRIOR_FLOOR = 0.01

    /**
     * Index of the key to select, or -1 to leave the tap to plain geometry.
     * [priors] is null for keys that are never weighted (such as the page toggle).
     */
    fun resolve(x: Float, y: Float, boxes: List<KeyboardHitBox>, priors: List<Double?>): Int {
        require(boxes.size == priors.size)
        for (i in boxes.indices) {
            val box = boxes[i]
            if (!box.enabled || !box.contains(x, y)) continue
            val prior = priors[i] ?: return i
            // A key that can continue the syllable keeps its whole drawn area:
            // moving the edges of plausible keys made the keyboard feel inconsistent.
            if (prior >= PRIOR_FLOOR) return i
            val w = box.right - box.left
            val h = box.bottom - box.top
            val cx = (box.left + box.right) / 2f
            val cy = (box.top + box.bottom) / 2f
            if (kotlin.math.abs(x - cx) <= w * CORE_FRACTION / 2 && kotlin.math.abs(y - cy) <= h * CORE_FRACTION / 2) return i
        }
        var best = -1
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in boxes.indices) {
            val box = boxes[i]
            val prior = priors[i] ?: continue
            if (!box.enabled) continue
            val w = (box.right - box.left).toDouble()
            val h = (box.bottom - box.top).toDouble()
            val dx = (x - (box.left + box.right) / 2f) / w
            val dy = (y - (box.top + box.bottom) / 2f) / h
            if (kotlin.math.abs(dx) > 1.0 || kotlin.math.abs(dy) > 1.0) continue
            val score = ln(maxOf(prior, PRIOR_FLOOR)) - (dx * dx + dy * dy) / (2 * SIGMA * SIGMA)
            if (score > bestScore) {
                bestScore = score
                best = i
            }
        }
        return best
    }

    private fun KeyboardHitBox.contains(x: Float, y: Float) = x >= left && x < right && y >= top && y < bottom

    /** For tests and tuning: how much likelier [prior] must be to win at a normalized distance. */
    internal fun likelihoodRatio(nearDistance: Double, farDistance: Double): Double =
        exp((farDistance * farDistance - nearDistance * nearDistance) / (2 * SIGMA * SIGMA))
}
