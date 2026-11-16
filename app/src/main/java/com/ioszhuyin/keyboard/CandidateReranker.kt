package com.ioszhuyin.keyboard

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.LongBuffer
import java.util.zip.ZipInputStream
import kotlin.math.ln

/**
 * Experimental: a separately downloaded character model (a .zip "model pack")
 * that reorders homophone candidates using the text before the cursor.
 *
 * The pack holds `zhuyin_rerank.onnx`, `vocab.json` and `meta.json`; nothing is
 * bundled in the APK and the app still has no network permission.
 */
internal object RerankModelPack {
    private const val DIR = "rerank_model"
    const val MODEL = "zhuyin_rerank.onnx"
    const val VOCAB = "vocab.json"
    const val META = "meta.json"
    private const val PREFS = "rerank_model"
    private const val KEY_ENABLED = "enabled"
    private const val MAX_ENTRY_BYTES = 64L * 1024 * 1024
    private val REQUIRED = setOf(MODEL, VOCAB, META)

    fun dir(context: Context) = File(context.applicationContext.filesDir, DIR)

    fun installed(context: Context): Boolean = REQUIRED.all { File(dir(context), it).isFile }

    fun enabled(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /** Extracts the pack's three files; anything else in the zip is ignored. */
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
                    if (entry.isDirectory || name !in REQUIRED) continue
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

internal class CandidateReranker private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val index: Map<String, Long>,
    private val maxContext: Int,
    private val k: Int,
    private val pad: Long,
    private val unk: Long,
    private val bos: Long,
    val beta: Double
) : AutoCloseable {

    /** Sum of log P(char | context, earlier chars) for each candidate, or null if any is too long. */
    fun score(context: String, candidates: List<String>): DoubleArray? {
        if (candidates.isEmpty() || candidates.any { it.isEmpty() || it.codePointCount(0, it.length) > k }) return null
        val ctx = codePoints(context).takeLast(maxContext - 1).map { index[it] ?: unk }
            .ifEmpty { listOf(bos) }
        val length = ctx.size + k
        val ids = LongBuffer.allocate(candidates.size * length)
        val targets = LongBuffer.allocate(candidates.size * k)
        for (candidate in candidates) {
            val tail = codePoints(candidate).map { index[it] ?: unk }
            ctx.forEach(ids::put)
            for (j in 0 until k) {
                val id = tail.getOrNull(j) ?: pad
                ids.put(id)
                targets.put(id)
            }
        }
        ids.rewind()
        targets.rewind()
        val rows = candidates.size.toLong()
        OnnxTensor.createTensor(env, ids, longArrayOf(rows, length.toLong())).use { idTensor ->
            OnnxTensor.createTensor(env, targets, longArrayOf(rows, k.toLong())).use { targetTensor ->
                session.run(mapOf("ids" to idTensor, "targets" to targetTensor)).use { result ->
                    @Suppress("UNCHECKED_CAST")
                    val logp = result[0].value as Array<FloatArray>
                    return DoubleArray(candidates.size) { row -> logp[row].sumOf { it.toDouble() } }
                }
            }
        }
    }

    /** Indices of [candidates] in their new order: model score blended with the dictionary rank. */
    fun rank(context: String, candidates: List<String>): List<Int>? {
        val scores = score(context, candidates) ?: return null
        return candidates.indices.sortedByDescending { scores[it] - beta * ln(it + 1.0) }
    }

    override fun close() = session.close()

    companion object {
        fun open(dir: File): CandidateReranker {
            val meta = JSONObject(File(dir, RerankModelPack.META).readText())
            require(meta.optInt("format") == 1) { "不支援的模型格式" }
            val vocab = JSONArray(File(dir, RerankModelPack.VOCAB).readText())
            val index = HashMap<String, Long>(vocab.length() * 2)
            for (i in 0 until vocab.length()) index[vocab.getString(i)] = i.toLong()
            val env = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            val session = env.createSession(File(dir, RerankModelPack.MODEL).absolutePath, options)
            return CandidateReranker(
                env, session, index,
                maxContext = meta.optInt("max_context", 32),
                k = meta.optInt("k", 4),
                pad = meta.optLong("pad", 0),
                unk = meta.optLong("unk", 1),
                bos = meta.optLong("bos", 2),
                beta = meta.optDouble("beta", 1.0)
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
