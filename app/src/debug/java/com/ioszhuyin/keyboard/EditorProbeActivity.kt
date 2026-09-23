package com.ioszhuyin.keyboard

import android.app.Activity
import android.content.res.Configuration
import android.os.Bundle
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only host for real IME checks; excluded from every release APK. */
class EditorProbeActivity : Activity() {
    private lateinit var root: LinearLayout
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 100, 36, 36)
        }
        root.addView(TextView(this).apply { text = "注音輸入測試"; textSize = 24f })
        for ((hint, type, options) in listOf(
            Triple("中文輸入", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_ACTION_DONE),
            Triple("Password", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD, 0),
            Triple("Email", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0),
            Triple("ASCII", InputType.TYPE_CLASS_TEXT, EditorInfo.IME_FLAG_FORCE_ASCII))) {
            root.addView(EditText(this).apply {
                this.hint = hint; inputType = type; imeOptions = options
                textSize = 22f
                setSingleLine()
            }, LinearLayout.LayoutParams(-1, 160))
        }
        setContentView(root)
        themeColors()
    }
    override fun onConfigurationChanged(config: Configuration) {
        super.onConfigurationChanged(config)
        themeColors()
    }
    private fun themeColors() {
        val palette = ThemePalette.settings(this)
        root.setBackgroundColor(palette.background)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = if (ThemePalette.isNightMode(this)) 0
            else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        for (index in 0 until root.childCount) {
            (root.getChildAt(index) as TextView).apply {
                setTextColor(palette.title)
                setHintTextColor(palette.inputHint)
            }
        }
    }
}
