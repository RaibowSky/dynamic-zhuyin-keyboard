package com.ioszhuyin.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.LongBuffer
import java.util.zip.ZipInputStream

/**
 * Experimental: a separately downloaded candidate model (a .zip "model pack")
 * that picks the most natural sentence for the text before the cursor.
 *
 * The pack is the 0c D512 reranker (MIT, https://huggingface.co/Funmula/0c-sequence-d512):
 * `model.onnx`, `vocab.json`, `manifest.json`, and for the 4-bit version
 * `model.onnx.data`. Nothing is bundled in the APK and the app still has no
 * network permission.
 */
internal object RerankModelPack {
    private const val DIR = "rerank_model"
    const val MODEL = "model.onnx"
    const val MODEL_DATA = "model.onnx.data"
    const val VOCAB = "vocab.json"
    const val MANIFEST = "manifest.json"
    private const val LICENSE = "LICENSE"
    private const val PREFS = "rerank_model"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_ENTRY_BYTES = 200L * 1024 * 1024
    private val REQUIRED = setOf(MODEL, VOCAB, MANIFEST)
    private val ACCEPTED = REQUIRED + setOf(MODEL_DATA, LICENSE)

    fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    fun installed(context: Context): Boolean = REQUIRED.all { File(dir(context), it).isFile }

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Extracts the pack's model files; anything else in the zip is ignored. */
    fun install(context: Context, uri: Uri) {
        val staging = File(context.applicationContext.cacheDir, "rerank_staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException("無法開啟檔案")
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfterLast('/')
                    if (entry.isDirectory || name !in ACCEPTED) continue
                    File(staging, name).outputStream().use { out ->
                        val buffer = ByteArray(64 * 1024)
                        var total = 0L
                        while (true) {
                            val read = zip.read(buffer)
                            if (read < 0) break
                            total += read
                            require(total <= MAX_ENTRY_BYTES) { "$name 太大" }
                            out.write(buffer, 0, read)
                        }
                    }
                }
            }
            val missing = REQUIRED.filterNot { File(staging, it).isFile }
            require(missing.isEmpty()) { "模型包缺少 ${missing.joinToString()}" }
            // Fails here, before replacing anything, if the pack is not usable.
            CandidateReranker.open(staging).close()
            val target = dir(context)
            target.deleteRecursively()
            require(staging.renameTo(target)) { "無法安裝模型" }
        } finally {
            staging.deleteRecursively()
        }
    }

    fun remove(context: Context) {
        dir(context).deleteRecursively()
        setEnabled(context, false)
    }
}

/**
 * Scores candidates for one Zhuyin reading with the 0c model:
 * log P(candidate | preceding text, reading). Candidates of one reading are
 * packed into a prefix tree and scored in a single run.
 */
internal class CandidateReranker private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val characters: Map<String, Long>,
    private val bases: Map<String, Long>,
    private val neutralTolerantLast: Set<String>,
    private val weight: Double,
    private val margin: Double,
    private val maxSyllables: Int,
    private val maxCandidates: Int,
    private val contextLength: Int
) : AutoCloseable {

    /**
     * Model score per candidate; candidates the model cannot read (wrong length
     * or unknown characters) score negative infinity. Null if the reading itself
     * cannot be scored or the first candidate is unreadable.
     */
    fun score(context: String, syllables: List<String>, candidates: List<String>): DoubleArray? {
        val n = syllables.size
        if (n !in 1..maxSyllables || candidates.isEmpty()) return null
        val sounds = syllables.map { toSound(it) ?: return null }
        val encoded: List<List<Long>?> = candidates.take(maxCandidates).map { candidate ->
            val chars = codePoints(candidate).takeIf { it.size == n }
            chars?.mapNotNull { characters[it] }?.takeIf { it.size == n }
        }
        if (encoded.first() == null) return null
        val readable = encoded.indices.filter { encoded[it] != null }
        val head = buildList {
            add(BOS)
            codePoints(context).takeLast(contextLength).forEach { add(characters[it] ?: UNK) }
            add(SEP)
        }
        val ids = readable.map { encoded[it]!! }
        var scores = run(head, sounds, ids)
        val (base, tone) = sounds.last()
        val lastBase = syllables.last().trimEnd(*TONE_MARKS)
        if (lastBase in neutralTolerantLast && tone != NEUTRAL && tone != UNKNOWN_TONE) {
            // Sentence-final particles such as ㄇㄚ are often typed with first tone.
            val neutral = run(head, sounds.dropLast(1) + (base to NEUTRAL), ids)
            scores = DoubleArray(scores.size) { maxOf(scores[it], neutral[it]) }
        }
        val result = DoubleArray(candidates.size) { Double.NEGATIVE_INFINITY }
        readable.forEachIndexed { row, index -> result[index] = scores[row] }
        return result
    }

    /**
     * Indices of [candidates] in their new order. The model may move another
     * candidate ahead of the dictionary's first choice only when its score,
     * after a small penalty per dictionary rank, beats the first by the margin.
     */
    fun rank(context: String, syllables: List<String>, candidates: List<String>): List<Int>? {
        val scores = score(context, syllables, candidates) ?: return null
        val fused = DoubleArray(scores.size) { weight * scores[it] - RANK_PENALTY * it }
        val order = candidates.indices.sortedByDescending { fused[it] }.toMutableList()
        if (order.first() != 0 && fused[order.first()] - fused[0] <= margin) {
            order.remove(0)
            order.add(0, 0)
        }
        return order
    }

    private fun run(head: List<Long>, sounds: List<Pair<Long, Long>>, candidates: List<List<Long>>): DoubleArray {
        val n = sounds.size
        val out = head.size + n
        val ids = ArrayList<Long>(); val baseIds = ArrayList<Long>(); val tones = ArrayList<Long>()
        val types = ArrayList<Long>(); val positions = ArrayList<Long>()
        head.forEach { ids += it; baseIds += 0; tones += 0; types += 0 }
        sounds.forEach { (base, tone) -> ids += 0; baseIds += base; tones += tone; types += 1 }
        ids += OUT; baseIds += 0; tones += 0; types += 0
        for (i in 0..out) positions += i.toLong()

        val nodes = HashMap<Pair<Int, Long>, Int>()
        val ancestors = HashMap<Int, List<Int>>()
        val projected = ArrayList<Int>().apply { add(out) }
        val targetNode = LongArray(candidates.size * n)
        val targetIds = LongArray(candidates.size * n)
        candidates.forEachIndexed { k, chars ->
            var parent = -1
            targetNode[k * n] = 0
            for (j in 1 until n) {
                val key = parent to chars[j - 1]
                val node = nodes.getOrPut(key) {
                    val index = ids.size
                    ids += chars[j - 1]; baseIds += 0; tones += 0; types += 2
                    positions += (out + j).toLong()
                    ancestors[index] = (ancestors[parent] ?: emptyList()) + index
                    projected += index
                    index
                }
                parent = node
                targetNode[k * n + j] = projected.indexOf(node).toLong()
            }
            for (j in 0 until n) targetIds[k * n + j] = chars[j] - CHARACTER_OFFSET
        }

        val length = ids.size
        val mask = ByteBuffer.allocateDirect(length * length)
        for (row in 0 until length) {
            val allowed = ancestors[row]
            for (col in 0 until length) {
                val visible = if (allowed == null) col <= row else col <= out || col in allowed
                mask.put(if (visible) 1 else 0)
            }
        }
        mask.rewind()

        val tensors = ArrayList<OnnxTensor>()
        fun row(values: List<Long>) = OnnxTensor.createTensor(env, LongBuffer.wrap(values.toLongArray()),
            longArrayOf(1, values.size.toLong())).also(tensors::add)
        try {
            val feeds = mapOf(
                "ids" to row(ids), "bases" to row(baseIds), "tones" to row(tones),
                "types" to row(types), "positions" to row(positions),
                "mask" to OnnxTensor.createTensor(env, mask, longArrayOf(1, 1, length.toLong(), length.toLong()),
                    ai.onnxruntime.OnnxJavaType.BOOL).also(tensors::add),
                "proj_pos" to OnnxTensor.createTensor(env, LongBuffer.wrap(projected.map { it.toLong() }.toLongArray()),
                    longArrayOf(projected.size.toLong())).also(tensors::add),
                "target_node" to OnnxTensor.createTensor(env, LongBuffer.wrap(targetNode),
                    longArrayOf(candidates.size.toLong(), n.toLong())).also(tensors::add),
                "target_ids" to OnnxTensor.createTensor(env, LongBuffer.wrap(targetIds),
                    longArrayOf(candidates.size.toLong(), n.toLong())).also(tensors::add)
            )
            session.run(feeds, setOf("scores")).use { result ->
                val scores = result[0].value as FloatArray
                return DoubleArray(scores.size) { scores[it].toDouble() }
            }
        } finally {
            tensors.forEach { it.close() }
        }
    }

    /** "ㄋㄧˇ" → (base, tone); a syllable typed without a tone has unknown tone 0. */
    private fun toSound(syllable: String): Pair<Long, Long>? {
        val mark = syllable.lastOrNull() ?: return null
        val tone = when (mark) {
            'ˉ' -> 1L; 'ˊ' -> 2L; 'ˇ' -> 3L; 'ˋ' -> 4L; '˙' -> NEUTRAL
            else -> UNKNOWN_TONE
        }
        val base = if (tone == UNKNOWN_TONE) syllable else syllable.dropLast(1)
        return (bases[base] ?: return null) to tone
    }

    override fun close() = session.close()

    companion object {
        private const val BOS = 1L
        private const val SEP = 2L
        private const val OUT = 3L
        private const val UNK = 4L
        private const val SPECIALS = 8
        private const val BASE_OFFSET = 2
        private const val CHARACTER_OFFSET = 6L
        private const val NEUTRAL = 5L
        private const val UNKNOWN_TONE = 0L
        // Our dictionary order has no calibrated log score; keep only a slight
        // preference for it so the model decides close calls such as 媽/嗎.
        private const val RANK_PENALTY = 0.25
        private val TONE_MARKS = charArrayOf('ˉ', 'ˊ', 'ˇ', 'ˋ', '˙')

        fun open(dir: File): CandidateReranker {
            val manifest = JSONObject(File(dir, RerankModelPack.MANIFEST).readText())
            require(manifest.optString("schema") == "zuin-sequence-runtime-v2") { "不支援的模型格式" }
            val vocab = JSONObject(File(dir, RerankModelPack.VOCAB).readText())
            val characters = vocab.getJSONArray("characters").let { array ->
                HashMap<String, Long>(array.length() * 2).apply {
                    for (i in 0 until array.length()) put(array.getString(i), (i + SPECIALS).toLong())
                }
            }
            val bases = vocab.getJSONArray("bases").let { array ->
                HashMap<String, Long>(array.length() * 2).apply {
                    for (i in 0 until array.length()) put(array.getString(i), (i + BASE_OFFSET).toLong())
                }
            }
            val neutral = manifest.optJSONArray("neutral_tolerant_last")?.let { array ->
                (0 until array.length()).map(array::getString).toSet()
            }.orEmpty()
            val limits = manifest.optJSONObject("limits") ?: JSONObject()
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            // The 4-bit model keeps its weights in model.onnx.data next to it.
            val session = env.createSession(File(dir, RerankModelPack.MODEL).absolutePath, options)
            return CandidateReranker(
                env, session, characters, bases, neutral,
                weight = manifest.optDouble("weight", 2.0),
                margin = manifest.optDouble("margin", 0.0),
                maxSyllables = limits.optInt("max_syllables", 32),
                maxCandidates = limits.optInt("max_candidates", 16),
                contextLength = limits.optInt("context_runes", 64)
            )
        }

        private fun codePoints(text: String): List<String> {
            val out = ArrayList<String>(text.length)
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                out += String(Character.toChars(cp))
                i += Character.charCount(cp)
            }
            return out
        }
    }
}
