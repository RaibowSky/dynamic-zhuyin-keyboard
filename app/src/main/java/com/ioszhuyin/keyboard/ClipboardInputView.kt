package com.ioszhuyin.keyboard

import android.content.Context
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView

/** Native, scrollable panel inside the IME window, so taps keep the current editor focused. */
internal class ClipboardInputView(context: Context, private val keyboard: ZhuyinKeyboardView,
    private val history: ClipboardHistory, private val paste: (String) -> Unit,
    private val pasteCurrent: () -> Unit) : FrameLayout(context) {
    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; visibility = GONE; isClickable = true
    }
    private val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private var entries = emptyList<ClipboardEntry>()
    private var panelPalette = ThemePalette.keyboard(context)
    val showingClipboard: Boolean get() = panel.visibility == VISIBLE
    private val refreshTask = object : Runnable {
        override fun run() {
            if (showingClipboard) { history.refresh(); postDelayed(this, 30_000) }
        }
    }

    init {
        addView(keyboard, LayoutParams(-1, -1))
        addView(panel, LayoutParams(-1, 0, Gravity.BOTTOM))
        history.onChanged = { entries = it; if (showingClipboard) renderRows() }
    }

    fun openClipboard() {
        keyboard.cancelCursorGesture()
        panel.visibility = VISIBLE
        rebuildPanel()
        updatePanelSize()
        history.captureCurrent()
        history.refresh()
        removeCallbacks(refreshTask)
        postDelayed(refreshTask, 30_000)
    }

    fun closeClipboard() {
        panel.visibility = GONE
        removeCallbacks(refreshTask)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        if (showingClipboard) updatePanelSize()
    }

    private fun updatePanelSize() {
        val wanted = (keyboard.height - keyboard.keyboardContentTop).toInt().coerceAtLeast(1)
        if (panel.layoutParams.height != wanted) {
            panel.layoutParams = LayoutParams(-1, wanted, Gravity.BOTTOM)
        }
        panel.setPadding(dp(8), 0, dp(8), keyboard.navigationBottomInset)
    }

    fun refreshTheme() { if (showingClipboard) rebuildPanel() }

    private fun rebuildPanel() {
        panelPalette = ThemePalette.keyboard(context)
        panel.setBackgroundColor(panelPalette.background)
        panel.removeAllViews()
        val scroll = ScrollView(context)
        val content = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(pill("返回", dp(64), dp(36)) { closeClipboard() })
        header.addView(TextView(context).apply {
            text = "剪貼簿"; textSize = 17f; gravity = Gravity.CENTER; typeface = Typeface.DEFAULT_BOLD
            setTextColor(panelPalette.keyText)
        }, LinearLayout.LayoutParams(0, dp(44), 1f))
        header.addView(Switch(context).apply {
            text = "儲存歷史"; textSize = 13f; isChecked = history.enabled; minHeight = dp(44)
            setTextColor(panelPalette.keyText)
            setOnCheckedChangeListener { _, value -> history.setEnabled(value) }
        }, LinearLayout.LayoutParams(-2, dp(44)))
        panel.addView(header)
        val actions = LinearLayout(context)
        actions.addView(pill("貼上目前內容", 0, dp(36), 1f) { pasteCurrent() })
        actions.addView(pill("清除未釘選", 0, dp(36), 1f) { history.clearUnpinned() })
        content.addView(actions, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(2) })
        (rows.parent as? android.view.ViewGroup)?.removeView(rows)
        content.addView(rows)
        content.addView(TextView(context).apply {
            text = "未釘選最多 50 筆；超過 1 小時的項目會在下次使用剪貼簿時清除。釘選會保留。關閉只停止新增。"
            textSize = 11f; setTextColor(panelPalette.controlText)
            setPadding(dp(4), dp(8), dp(4), dp(8))
        })
        scroll.addView(content)
        panel.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        renderRows()
    }

    private fun renderRows() {
        rows.removeAllViews()
        if (entries.isEmpty()) rows.addView(TextView(context).apply {
            text = if (history.enabled) "尚無紀錄。複製文字後會顯示在這裡。" else "開啟「儲存歷史」後，開始保留複製的文字。"
            textSize = 14f; gravity = Gravity.CENTER
            setTextColor(panelPalette.controlText); setPadding(dp(8), dp(24), dp(8), dp(24))
        })
        for (entry in entries) {
            val card = LinearLayout(context).apply {
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(panelPalette.keyBackground, 10)
                setPadding(dp(12), dp(2), dp(4), dp(2))
            }
            card.addView(TextView(context).apply {
                text = entry.text; maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                textSize = 16f; minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL
                setTextColor(panelPalette.keyText)
                contentDescription = "貼上：${entry.text.take(100)}"
                isFocusable = true; setOnClickListener { paste(entry.text) }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            card.addView(iconButton("📌", if (entry.pinned) "取消釘選" else "釘選", entry.pinned) { history.pin(entry) })
            card.addView(iconButton("✕", "刪除", false) { history.delete(entry) })
            rows.addView(card, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        }
    }

    private fun roundRect(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radiusDp).toFloat()
    }

    private fun pill(label: String, width: Int, height: Int, weight: Float = 0f, action: () -> Unit) =
        TextView(context).apply {
            text = label; textSize = 14f; gravity = Gravity.CENTER
            setTextColor(panelPalette.controlText)
            background = roundRect(panelPalette.controlBackground, 8)
            isClickable = true; setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(width, height, weight).apply {
                marginStart = dp(2); marginEnd = dp(2); topMargin = dp(2); bottomMargin = dp(2)
            }
        }

    private fun iconButton(glyph: String, description: String, active: Boolean, action: () -> Unit) =
        TextView(context).apply {
            text = glyph; textSize = 17f; gravity = Gravity.CENTER
            setTextColor(panelPalette.controlText)
            alpha = if (active || glyph != "📌") 1f else 0.45f
            if (active) background = roundRect(panelPalette.controlBackground, 8)
            contentDescription = description
            isClickable = true; setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    override fun onDetachedFromWindow() { removeCallbacks(refreshTask); super.onDetachedFromWindow() }
}
