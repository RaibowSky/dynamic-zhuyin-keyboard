package com.ioszhuyin.keyboard

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.text.SpannableString
import android.text.Spanned
import android.text.style.MetricAffectingSpan
import android.text.TextPaint
import android.util.AtomicFile
import java.io.File
import java.io.InputStream

/** Local-only font storage; a failed import never replaces the selected font. */
internal object KeyboardFont {
    const val PREVIEW = "ㄅㄆㄇㄈ ㄧㄨㄩ ˉˊˇˋ˙ 注音鍵盤 ABC abc 0123456789"
    private const val MAX_BYTES = 20 * 1024 * 1024
    private const val FILE_NAME = "keyboard-custom.ttf"
    private var cachedStamp: String? = null
    private var cachedFont: Typeface? = null

    fun stage(context: Context, uri: Uri): File =
        requireNotNull(context.contentResolver.openInputStream(uri)) { "無法讀取字型" }
            .use { stage(context, it) }

    fun stage(context: Context, input: InputStream): File {
        val staged = File.createTempFile("font-preview-", ".ttf", context.cacheDir)
        try {
            staged.outputStream().use { output ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    require(total <= MAX_BYTES) { "字型不得超過 20 MB" }
                    output.write(buffer, 0, count)
                }
            }
            val magic = ByteArray(4)
            java.io.DataInputStream(staged.inputStream()).use { it.readFully(magic) }
            require(magic.contentEquals(byteArrayOf(0, 1, 0, 0)) ||
                magic.contentEquals("OTTO".toByteArray())) { "請選擇有效的 TTF 或 OTF 字型" }
            Typeface.createFromFile(staged)
            return staged
        } catch (error: Exception) {
            staged.delete()
            throw error
        }
    }

    @Synchronized fun apply(context: Context, staged: File) {
        Typeface.createFromFile(staged)
        val atomic = AtomicFile(File(context.filesDir, FILE_NAME))
        val output = atomic.startWrite()
        try {
            staged.inputStream().use { it.copyTo(output) }
            atomic.finishWrite(output)
            cachedStamp = null
        } catch (error: Exception) {
            atomic.failWrite(output)
            throw error
        }
    }

    @Synchronized fun reset(context: Context) {
        AtomicFile(File(context.filesDir, FILE_NAME)).delete()
        cachedStamp = null
        cachedFont = null
    }

    @Synchronized fun load(context: Context): Typeface? {
        val file = File(context.filesDir, FILE_NAME)
        // Recover a previous atomic write after a process interruption (API 24+).
        if (File(file.path + ".bak").exists()) runCatching { AtomicFile(file).openRead().close() }
        val stamp = "${file.path}:${file.exists()}:${file.length()}:${file.lastModified()}"
        if (stamp != cachedStamp) {
            cachedFont = runCatching {
                if (file.exists()) Typeface.createFromFile(file) else null
            }.getOrNull()
            cachedStamp = stamp
        }
        return cachedFont
    }

    fun choose(text: String, custom: Typeface?, fallback: Typeface): Typeface {
        if (custom == null) return fallback
        val probe = Paint().apply { typeface = custom }
        var offset = 0
        while (offset < text.length) {
            val end = offset + Character.charCount(text.codePointAt(offset))
            if (!text.substring(offset, end).isBlank() && !probe.hasGlyph(text.substring(offset, end))) return fallback
            offset = end
        }
        return custom
    }

    fun preview(context: Context, custom: Typeface): CharSequence {
        val bopomofo = Typeface.createFromAsset(context.assets, "bopomofo.ttf")
        return SpannableString(PREVIEW).apply {
            for (index in indices) {
                val value = substring(index, index + 1)
                val fallback = if (value[0] in 'ㄅ'..'ㄩ' || value[0] in "ˉˊˇˋ˙") bopomofo else Typeface.DEFAULT
                val face = choose(value, custom, fallback)
                setSpan(object : MetricAffectingSpan() {
                    override fun updateDrawState(paint: TextPaint) { paint.typeface = face }
                    override fun updateMeasureState(paint: TextPaint) { paint.typeface = face }
                }, index, index + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
    }
}
