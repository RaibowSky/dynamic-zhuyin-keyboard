package com.ioszhuyin.keyboard

import android.content.Intent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val enableBtn = findViewById<Button>(R.id.btn_enable_keyboard)
        val switchBtn = findViewById<Button>(R.id.btn_switch_keyboard)
        val infoText = findViewById<TextView>(R.id.tv_info)

        enableBtn.setOnClickListener {
            val intent = Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS)
            intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(intent)
        }

        switchBtn.setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }

        infoText.text = buildString {
            appendLine("iOS 注音鍵盤")
            appendLine()
            appendLine("使用方法：")
            appendLine("1. 點擊「開啟鍵盤設定」")
            appendLine("2. 啟用本鍵盤並允許所有權限")
            appendLine("3. 返回後點擊「切換鍵盤」")
            appendLine("4. 選擇「鍵盤」→ 再選「iOS注音鍵盤」")
            appendLine()
            appendLine("⚠️ 需賦予「顯示在上面」和「存取權限」")
        }
    }
}