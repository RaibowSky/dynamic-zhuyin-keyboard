package com.ioszhuyin.keyboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import java.security.MessageDigest
import java.util.concurrent.Executors

/** The default IME may receive clipboard events while its process is alive; never runs a keep-alive service. */
internal class ClipboardHistory(private val context: Context, private val captureAllowed: () -> Boolean) {
    private val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    private val prefs = context.getSharedPreferences("clipboard_history_options", Context.MODE_PRIVATE)
    private val store = ClipboardHistoryStore(context)
    private val io = Executors.newSingleThreadExecutor { Thread(it, "clipboard-history-io") }
    private val main = Handler(Looper.getMainLooper())
    private var closed = false
    var onChanged: ((List<ClipboardEntry>) -> Unit)? = null
    val enabled: Boolean get() = prefs.getBoolean("enabled", false)
    private val listener = ClipboardManager.OnPrimaryClipChangedListener {
        // Mark a clip copied in a no-capture editor by identity, so the mark cannot outlive that
        // clip (process death, history toggled off) and hide a later copy.
        if (!captureAllowed()) {
            currentClipText()?.let { (clip, text) ->
                prefs.edit().putString("blocked_clip_token", clipToken(clip, text)).apply()
            }
        } else {
            // A change event is a genuinely new copy. Before API 26 the token is only a text hash,
            // so it must not be suppressed by an older identical clip's block or dedup mark.
            prefs.edit().remove("blocked_clip_token").apply()
            if (enabled) captureCurrent(freshCopy = true)
        }
    }

    init { clipboard.addPrimaryClipChangedListener(listener) }

    fun setEnabled(value: Boolean) {
        prefs.edit().putBoolean("enabled", value).apply()
        if (value) captureCurrent()
        refresh()
    }

    fun captureCurrent(freshCopy: Boolean = false) {
        if (!enabled || !captureAllowed()) return
        val (clip, text) = currentClipText() ?: return
        val token = clipToken(clip, text)
        if (token == prefs.getString("blocked_clip_token", null)) return
        val sensitive = clip.description.extras?.getBoolean("android.content.extra.IS_SENSITIVE", false) == true
        execute { store.record(text, token, sensitive, freshCopy) }
    }

    private fun currentClipText(): Pair<ClipData, String>? {
        val clip = runCatching { clipboard.primaryClip }.getOrNull() ?: return null
        if (clip.itemCount == 0) return null
        // Do not resolve content URIs or coerce remote/rich clipboard items.
        val text = clip.getItemAt(0).text?.toString() ?: return null
        return clip to text
    }

    private fun clipToken(clip: ClipData, text: String): String {
        val timestamp = if (Build.VERSION.SDK_INT >= 26) clip.description.timestamp else 0L
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return "$timestamp:$digest"
    }

    fun refresh() = execute { }
    fun pin(entry: ClipboardEntry) = execute {
        if (!store.setPinned(entry.id, !entry.pinned)) main.post {
            if (!closed) Toast.makeText(context, "釘選最多 50 筆；請先取消其他釘選，或重新整理", Toast.LENGTH_SHORT).show()
        }
    }
    fun delete(entry: ClipboardEntry) = execute { store.delete(entry.id) }
    fun clearUnpinned() = execute { store.clearUnpinned() }

    private fun execute(action: () -> Unit) {
        if (closed) return
        io.execute {
            val result = runCatching { action(); store.entries() }
            main.post {
                if (!closed) result.onSuccess { onChanged?.invoke(it) }.onFailure {
                    Toast.makeText(context, "無法讀取或儲存剪貼簿紀錄", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun close() {
        closed = true
        onChanged = null
        clipboard.removePrimaryClipChangedListener(listener)
        io.execute { store.close() }
        io.shutdown()
    }
}
