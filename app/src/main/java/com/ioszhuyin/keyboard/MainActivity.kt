package com.ioszhuyin.keyboard

import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var store: UserDictionaryStore
    private lateinit var adapter: ArrayAdapter<String>
    private lateinit var zhuyinInput: EditText
    private lateinit var wordInput: EditText
    private lateinit var searchInput: EditText
    private lateinit var listView: ListView
    private lateinit var statusText: TextView
    private lateinit var learningStatusText: TextView
    private lateinit var learningToggleButton: Button
    private val dictionaryIo = Executors.newSingleThreadExecutor { task ->
        Thread(task, "user-dictionary-io")
    }

    private var entries: List<UserDictionaryEntry> = emptyList()
    private var totalEntries: Int = 0
    private var selectedEntry: UserDictionaryEntry? = null
    private var listRefreshGeneration: Long = 0
    private var learningStatusGeneration: Long = 0
    private var listLoading = false
    private val learningPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener listener@ { _, key ->
            if (CandidateLearningSettings.isRecordsChange(key)) {
                if (isFinishing || isDestroyed) return@listener
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (::learningStatusText.isInitialized) refreshLearningStatus()
                    if (::adapter.isInitialized && ::searchInput.isInitialized) refreshList()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = UserDictionaryStore(this)
        buildLayout()
        refreshList()
    }

    override fun onDestroy() {
        if (::store.isInitialized) {
            runCatching {
                dictionaryIo.execute { store.close() }
            }.onFailure {
                store.close()
            }
        }
        dictionaryIo.shutdown()
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        CandidateLearningSettings.registerListener(this, learningPreferencesListener)
        if (::adapter.isInitialized && ::searchInput.isInitialized) refreshList()
    }

    override fun onStop() {
        CandidateLearningSettings.unregisterListener(this, learningPreferencesListener)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        if (::learningStatusText.isInitialized && ::learningToggleButton.isInitialized) {
            refreshLearningStatus()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && ::learningStatusText.isInitialized && ::learningToggleButton.isInitialized) {
            refreshLearningStatus()
        }
    }

    @Deprecated("Used for simple document import/export on older AndroidX setup.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQUEST_IMPORT -> importDictionary(uri)
            REQUEST_EXPORT_DICTIONARY -> exportDictionary(uri, includeLearning = false)
            REQUEST_EXPORT_WITH_LEARNING -> exportDictionary(uri, includeLearning = true)
        }
    }

    private fun buildLayout() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            setBackgroundColor(0xFFF3F4F6.toInt())
        }

        val title = TextView(this).apply {
            text = "動態注音鍵盤"
            textSize = 26f
            setTextColor(0xFF1F2937.toInt())
            gravity = Gravity.CENTER
        }
        root.addView(title, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        val subtitle = TextView(this).apply {
            text = "字典管理"
            textSize = 15f
            setTextColor(0xFF6B7280.toInt())
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(16))
        }
        root.addView(subtitle, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        root.addView(
            button("啟用鍵盤", 0xFF2563EB.toInt()) {
                startActivity(Intent(android.provider.Settings.ACTION_INPUT_METHOD_SETTINGS))
            }
        )
        root.addView(
            button("切換鍵盤", 0xFF047857.toInt()) {
                val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
                imm.showInputMethodPicker()
            }
        )

        statusText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF4B5563.toInt())
            setPadding(0, dp(14), 0, dp(8))
        }
        root.addView(statusText, LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)

        learningStatusText = TextView(this).apply {
            textSize = 14f
            setTextColor(0xFF4B5563.toInt())
            setPadding(0, dp(4), 0, dp(8))
        }
        root.addView(
            learningStatusText,
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )

        val learningRow = row()
        learningToggleButton = button("", 0xFF7C3AED.toInt()) { toggleLearning() }
        learningRow.addView(learningToggleButton, rowWeight())
        learningRow.addView(
            button("清除學習紀錄", 0xFFDC2626.toInt()) { confirmClearLearning() },
            rowWeight()
        )
        root.addView(learningRow)
        refreshLearningStatus()

        zhuyinInput = editText("注音，例如 ㄇㄚ˙")
        wordInput = editText("詞彙，例如 嗎")
        root.addView(zhuyinInput)
        root.addView(zhuyinPad())
        root.addView(wordInput)

        val editRow = row()
        editRow.addView(button("新增", 0xFF2563EB.toInt()) { addEntry() }, rowWeight())
        editRow.addView(button("更新", 0xFF7C3AED.toInt()) { updateEntry() }, rowWeight())
        editRow.addView(button("清空", 0xFF6B7280.toInt()) { clearSelection() }, rowWeight())
        root.addView(editRow)

        searchInput = editText("搜尋注音或詞彙")
        root.addView(searchInput)

        val searchRow = row()
        searchRow.addView(button("搜尋", 0xFF374151.toInt()) { refreshList() }, rowWeight())
        searchRow.addView(button("顯示全部", 0xFF6B7280.toInt()) {
            searchInput.setText("")
            refreshList()
        }, rowWeight())
        searchRow.addView(button("刪除", 0xFFDC2626.toInt()) { confirmDelete() }, rowWeight())
        root.addView(searchRow)

        val fileRow = row()
        fileRow.addView(button("匯入", 0xFF0E7490.toInt()) { openImportFile() }, rowWeight())
        fileRow.addView(button("匯出", 0xFF047857.toInt()) { chooseExportContent() }, rowWeight())
        root.addView(fileRow)

        if (isDebugBuild()) {
            root.addView(debugMetricsPanel())
        }

        adapter = ArrayAdapter(this, android.R.layout.simple_list_item_activated_1, mutableListOf())
        listView = ListView(this).apply {
            adapter = this@MainActivity.adapter
            choiceMode = ListView.CHOICE_MODE_SINGLE
            setOnItemClickListener { _, _, position, _ ->
                selectedEntry = entries.getOrNull(position)
                selectedEntry?.let {
                    zhuyinInput.setText(it.zhuyin)
                    wordInput.setText(it.word)
                    statusText.text = "正在編輯：${it.zhuyin} → ${it.word}"
                }
            }
        }
        root.addView(listView, LinearLayout.LayoutParams.MATCH_PARENT, dp(260))

        val info = TextView(this).apply {
            text = "匯入支援 JSON，或每行「注音<TAB>詞彙」的 TSV；" +
                "檔案上限 16 MB、總筆數上限 50,000。一般匯出只包含手動新增詞彙；" +
                "候選學習紀錄必須另外選擇並確認風險。"
            textSize = 13f
            setTextColor(0xFF6B7280.toInt())
            setPadding(0, dp(12), 0, 0)
        }
        root.addView(info)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun addEntry() {
        val zhuyin = zhuyinInput.text.toString()
        val word = wordInput.text.toString()
        runDictionaryTask("新增失敗", operation = {
            store.addEntry(zhuyin, word)
        }) {
            toast("已新增詞彙")
            if (
                selectedEntry == null &&
                zhuyinInput.text.toString() == zhuyin &&
                wordInput.text.toString() == word
            ) {
                clearSelection()
            }
            refreshList()
        }
    }

    private fun updateEntry() {
        if (listLoading) {
            toast("字典正在載入，請稍候")
            return
        }
        val entry = selectedEntry
        if (entry == null) {
            toast("請先點選要編輯的詞彙")
            return
        }
        val zhuyin = zhuyinInput.text.toString()
        val word = wordInput.text.toString()
        runDictionaryTask("更新失敗", operation = {
            check(store.updateEntry(entry.id, zhuyin, word)) { "找不到要更新的詞彙" }
        }) {
            toast("已更新詞彙")
            if (
                selectedEntry?.id == entry.id &&
                zhuyinInput.text.toString() == zhuyin &&
                wordInput.text.toString() == word
            ) {
                clearSelection()
            }
            refreshList()
        }
    }

    private fun confirmDelete() {
        if (listLoading) {
            toast("字典正在載入，請稍候")
            return
        }
        val entry = selectedEntry
        if (entry == null) {
            toast("請先點選要刪除的詞彙")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("刪除詞彙")
            .setMessage("確定刪除「${entry.zhuyin} → ${entry.word}」嗎？")
            .setPositiveButton("刪除") { _, _ ->
                if (listLoading || selectedEntry != entry) {
                    toast("字典內容已更新，請重新選取詞彙")
                    return@setPositiveButton
                }
                runDictionaryTask("刪除失敗", operation = {
                    check(store.deleteEntry(entry)) { "詞彙已更新或不存在，請重新選取" }
                }) {
                    if (selectedEntry?.id == entry.id) clearSelection()
                    refreshList()
                    toast("已刪除詞彙")
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun refreshList() {
        val query = searchInputOrEmpty()
        val generation = ++listRefreshGeneration
        listLoading = true
        entries = emptyList()
        listView.clearChoices()
        adapter.clear()
        adapter.notifyDataSetChanged()
        statusText.text = "使用者字典：載入中…"
        runCatching {
            dictionaryIo.execute {
                val result = runCatching {
                    store.search(query, MAX_VISIBLE_ENTRIES) to store.entryCount(query)
                }
                postToUi {
                    if (generation == listRefreshGeneration) {
                        result.onSuccess { (visibleEntries, total) ->
                            listLoading = false
                            entries = visibleEntries
                            totalEntries = total
                            selectedEntry = selectedEntry?.let { selected ->
                                visibleEntries.firstOrNull { it.id == selected.id }
                            }
                            adapter.clear()
                            adapter.addAll(entries.map { "${it.zhuyin}    ${it.word}" })
                            adapter.notifyDataSetChanged()
                            listView.clearChoices()
                            selectedEntry?.let { selected ->
                                val position = entries.indexOfFirst { it.id == selected.id }
                                if (position >= 0) listView.setItemChecked(position, true)
                            }
                            statusText.text = dictionaryStatusText()
                        }.onFailure {
                            listLoading = false
                            selectedEntry = null
                            statusText.text = "使用者字典：載入失敗"
                            toast(it.message ?: "載入字典失敗")
                        }
                    }
                }
            }
        }.onFailure {
            listLoading = false
            selectedEntry = null
            statusText.text = "使用者字典：載入失敗"
            toast(it.message ?: "載入字典失敗")
        }
    }

    private fun refreshLearningStatus() {
        if (!::learningStatusText.isInitialized || !::learningToggleButton.isInitialized) return
        val enabled = CandidateLearningSettings.isEnabled(this)
        learningToggleButton.text = if (enabled) "暫停學習" else "繼續學習"
        learningStatusText.text = if (enabled) {
            "候選學習：開啟（載入中…）"
        } else {
            "候選學習：暫停（載入中…）"
        }
        val generation = ++learningStatusGeneration
        runCatching {
            dictionaryIo.execute {
                val result = runCatching { store.learningEntryCount() }
                postToUi {
                    if (generation != learningStatusGeneration) return@postToUi
                    result.onSuccess { count ->
                        learningStatusText.text = learningStatusCopy(enabled, count)
                    }.onFailure {
                        learningStatusText.text = if (enabled) {
                            "候選學習：開啟（無法讀取筆數）"
                        } else {
                            "候選學習：暫停（無法讀取筆數）"
                        }
                    }
                }
            }
        }.onFailure {
            learningStatusText.text = if (enabled) {
                "候選學習：開啟（無法讀取筆數）"
            } else {
                "候選學習：暫停（無法讀取筆數）"
            }
        }
    }

    private fun toggleLearning() {
        val enabled = !CandidateLearningSettings.isEnabled(this)
        CandidateLearningSettings.setEnabled(this, enabled)
        refreshLearningStatus()
        toast(if (enabled) "已繼續候選學習" else "已暫停新增候選學習")
    }

    private fun confirmClearLearning() {
        runCatching {
            dictionaryIo.execute {
                val result = runCatching { store.learningEntryCount() }
                postToUi {
                    result.onSuccess { count ->
                        if (count == 0) {
                            toast("目前沒有候選學習紀錄")
                            return@onSuccess
                        }
                        AlertDialog.Builder(this)
                            .setTitle("清除候選學習紀錄")
                            .setMessage("確定清除 $count 筆候選排序紀錄嗎？手動新增的使用者詞彙不會被刪除。")
                            .setPositiveButton("清除") { _, _ ->
                                runDictionaryTask("清除失敗", operation = store::clearLearning) { deleted ->
                                    refreshLearningStatus()
                                    toast("已清除 $deleted 筆候選學習紀錄")
                                }
                            }
                            .setNegativeButton("取消", null)
                            .show()
                    }.onFailure {
                        toast(it.message ?: "讀取學習紀錄失敗")
                    }
                }
            }
        }.onFailure {
            toast(it.message ?: "讀取學習紀錄失敗")
        }
    }

    private fun clearSelection() {
        selectedEntry = null
        if (::listView.isInitialized) {
            listView.clearChoices()
            listView.invalidateViews()
        }
        zhuyinInput.setText("")
        wordInput.setText("")
        statusText.text = dictionaryStatusText()
    }

    private fun openImportFile() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQUEST_IMPORT)
    }

    private fun chooseExportContent() {
        AlertDialog.Builder(this)
            .setTitle("匯出內容")
            .setItems(
                arrayOf(
                    "只匯出手動新增詞彙（建議）",
                    "包含候選學習紀錄"
                )
            ) { _, selected ->
                if (selected == 0) {
                    openExportFile(includeLearning = false)
                } else {
                    confirmLearningExport()
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun confirmLearningExport() {
        AlertDialog.Builder(this)
            .setTitle("學習紀錄可能包含私人資訊")
            .setMessage(
                "候選學習紀錄可能包含人名、地址、公司名稱或其他私人用詞。" +
                    "匯出檔不會加密，請妥善保管並避免分享給他人。"
            )
            .setPositiveButton("了解，繼續匯出") { _, _ ->
                openExportFile(includeLearning = true)
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun openExportFile(includeLearning: Boolean) {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(
                Intent.EXTRA_TITLE,
                if (includeLearning) {
                    "zhuyin_user_dictionary_with_learning.json"
                } else {
                    "zhuyin_user_dictionary.json"
                }
            )
        }
        startActivityForResult(
            intent,
            if (includeLearning) {
                REQUEST_EXPORT_WITH_LEARNING
            } else {
                REQUEST_EXPORT_DICTIONARY
            }
        )
    }

    private fun importDictionary(uri: Uri) {
        toast("正在匯入…")
        dictionaryIo.execute {
            val result = runCatching { store.importFromUri(contentResolver, uri) }
            if (result.isSuccess) {
                CandidateLearningSettings.notifyRecordsChanged(applicationContext)
            }
            val resultMessage = result.fold(
                onSuccess = { imported ->
                    if (imported.learning > 0) {
                        "已匯入 ${imported.entries} 筆詞彙、${imported.learning} 筆學習紀錄"
                    } else {
                        "已匯入 ${imported.entries} 筆詞彙"
                    }
                },
                onFailure = { it.message ?: "匯入失敗" }
            )
            postToUi(fallbackMessage = resultMessage) {
                result.onSuccess {
                    refreshList()
                    refreshLearningStatus()
                    toast(resultMessage)
                }.onFailure {
                    toast(resultMessage)
                }
            }
        }
    }

    private fun exportDictionary(uri: Uri, includeLearning: Boolean) {
        toast("正在匯出…")
        dictionaryIo.execute {
            val result = runCatching {
                store.exportToUri(contentResolver, uri, includeLearning)
            }
            val resultMessage = result.fold(
                onSuccess = {
                    if (includeLearning) {
                        "已匯出使用者字典與學習紀錄"
                    } else {
                        "已匯出使用者字典"
                    }
                },
                onFailure = { it.message ?: "匯出失敗" }
            )
            postToUi(fallbackMessage = resultMessage) {
                result.onSuccess {
                    toast(resultMessage)
                }.onFailure {
                    toast(resultMessage)
                }
            }
        }
    }

    private fun postToUi(fallbackMessage: String? = null, action: () -> Unit) {
        runOnUiThread {
            if (!isFinishing && !isDestroyed) {
                action()
            } else if (fallbackMessage != null) {
                Toast.makeText(applicationContext, fallbackMessage, Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun <T> runDictionaryTask(
        fallbackError: String,
        operation: () -> T,
        onSuccess: (T) -> Unit
    ) {
        runCatching {
            dictionaryIo.execute {
                val result = runCatching(operation)
                if (result.isSuccess) {
                    CandidateLearningSettings.notifyRecordsChanged(applicationContext)
                }
                val fallbackMessage = result.fold(
                    onSuccess = { "字典變更已完成" },
                    onFailure = { it.message ?: fallbackError }
                )
                postToUi(fallbackMessage = fallbackMessage) {
                    result.onSuccess(onSuccess).onFailure {
                        toast(it.message ?: fallbackError)
                    }
                }
            }
        }.onFailure {
            toast(it.message ?: fallbackError)
        }
    }

    private fun learningStatusCopy(enabled: Boolean, count: Int): String =
        if (enabled) {
            "候選學習：開啟（$count 筆排序紀錄）"
        } else {
            "候選學習：暫停（保留 $count 筆既有排序紀錄）"
        }

    private fun dictionaryStatusText(): String =
        if (totalEntries > entries.size) {
            "使用者字典：$totalEntries 筆（顯示前 ${entries.size} 筆）"
        } else {
            "使用者字典：$totalEntries 筆"
        }

    private fun searchInputOrEmpty(): String =
        if (::searchInput.isInitialized) searchInput.text.toString() else ""

    private fun debugMetricsPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(8))
            addView(sectionTitle("鍵盤版面調整"))

            val presetRow = row()
            presetRow.addView(button("Pixel 預設", 0xFF475569.toInt()) {
                KeyboardMetrics.applyPreset(this@MainActivity, KeyboardMetrics.PRESET_PIXEL)
                toast("已套用 Pixel 預設")
            }, rowWeight())
            presetRow.addView(button("iOS 預設", 0xFF334155.toInt()) {
                KeyboardMetrics.applyPreset(this@MainActivity, KeyboardMetrics.PRESET_IOS)
                toast("已套用 iOS 預設")
            }, rowWeight())
            addView(presetRow)

            addView(metricSlider("按鍵高度", KeyboardMetrics.KEY_KEY_HEIGHT, 34f, 70f))
            addView(metricSlider("水平間距", KeyboardMetrics.KEY_HORIZONTAL_GAP, 0f, 14f))
            addView(metricSlider("垂直間距", KeyboardMetrics.KEY_VERTICAL_GAP, 0f, 14f))
            addView(metricSlider("第一排偏移", KeyboardMetrics.KEY_ROW_OFFSET_1, 0f, 2f))
            addView(metricSlider("第二排偏移", KeyboardMetrics.KEY_ROW_OFFSET_2, 0f, 2f))
            addView(metricSlider("第三排偏移", KeyboardMetrics.KEY_ROW_OFFSET_3, 0f, 2f))
            addView(metricSlider("左右內距", KeyboardMetrics.KEY_HORIZONTAL_PADDING, 0f, 20f))
            addView(metricSlider("上方內距", KeyboardMetrics.KEY_TOP_PADDING, 0f, 30f))
            addView(metricSlider("下方內距", KeyboardMetrics.KEY_BOTTOM_PADDING, 0f, 30f))
            addView(metricSlider("功能鍵寬度", KeyboardMetrics.KEY_FUNCTION_KEY_WIDTH, 0.7f, 2.4f))
            addView(metricSlider("空白鍵寬度比例", KeyboardMetrics.KEY_SPACEBAR_RATIO, 2.5f, 7f))
            addView(metricSlider("候選列高度", KeyboardMetrics.KEY_CANDIDATE_BAR_HEIGHT, 24f, 64f))
        }
    }

    private fun sectionTitle(textValue: String): TextView =
        TextView(this).apply {
            text = textValue
            textSize = 16f
            setTextColor(0xFF1F2937.toInt())
            setPadding(0, dp(10), 0, dp(8))
        }

    private fun metricSlider(labelText: String, key: String, min: Float, max: Float): LinearLayout {
        val prefs = KeyboardMetrics.prefs(this)
        val current = KeyboardMetrics.current(this)
        val initial = prefs.getFloat(key, metricValue(current, key)).coerceIn(min, max)
        val label = TextView(this).apply {
            setTextColor(0xFF374151.toInt())
            textSize = 13f
        }
        val seek = SeekBar(this).apply {
            this.max = ((max - min) * SLIDER_SCALE).toInt()
            progress = ((initial - min) * SLIDER_SCALE).toInt()
            setOnSeekBarChangeListener(simpleSeekBarListener { progressValue ->
                val value = min + progressValue / SLIDER_SCALE
                prefs.edit().putFloat(key, value).apply()
                label.text = "$labelText: ${formatMetric(value)}"
            })
        }
        label.text = "$labelText: ${formatMetric(initial)}"
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(label)
            addView(seek)
        }
    }

    private fun simpleSeekBarListener(onChange: (Int) -> Unit): SeekBar.OnSeekBarChangeListener =
        object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) onChange(progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }

    private fun metricValue(metrics: KeyboardLayoutMetrics, key: String): Float = when (key) {
        KeyboardMetrics.KEY_KEY_HEIGHT -> metrics.keyHeight
        KeyboardMetrics.KEY_HORIZONTAL_GAP -> metrics.horizontalGap
        KeyboardMetrics.KEY_VERTICAL_GAP -> metrics.verticalGap
        KeyboardMetrics.KEY_ROW_OFFSET_1 -> metrics.rowOffset1
        KeyboardMetrics.KEY_ROW_OFFSET_2 -> metrics.rowOffset2
        KeyboardMetrics.KEY_ROW_OFFSET_3 -> metrics.rowOffset3
        KeyboardMetrics.KEY_HORIZONTAL_PADDING -> metrics.keyboardHorizontalPadding
        KeyboardMetrics.KEY_TOP_PADDING -> metrics.keyboardTopPadding
        KeyboardMetrics.KEY_BOTTOM_PADDING -> metrics.keyboardBottomPadding
        KeyboardMetrics.KEY_FUNCTION_KEY_WIDTH -> metrics.functionKeyWidth
        KeyboardMetrics.KEY_SPACEBAR_RATIO -> metrics.spacebarWidthRatio
        KeyboardMetrics.KEY_CANDIDATE_BAR_HEIGHT -> metrics.candidateBarHeight
        else -> 0f
    }

    private fun formatMetric(value: Float): String =
        String.format(java.util.Locale.US, "%.1f", value)

    private fun zhuyinPad(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(symbolRow(listOf("ㄅ", "ㄆ", "ㄇ", "ㄈ", "ㄉ", "ㄊ", "ㄋ", "ㄌ")))
            addView(symbolRow(listOf("ㄍ", "ㄎ", "ㄏ", "ㄐ", "ㄑ", "ㄒ", "ㄓ", "ㄔ", "ㄕ", "ㄖ")))
            addView(symbolRow(listOf("ㄗ", "ㄘ", "ㄙ", "ㄧ", "ㄨ", "ㄩ", "ㄚ", "ㄛ", "ㄜ", "ㄝ")))
            addView(symbolRow(listOf("ㄞ", "ㄟ", "ㄠ", "ㄡ", "ㄢ", "ㄣ", "ㄤ", "ㄥ", "ㄦ")))
            addView(symbolRow(listOf("ˉ", "ˊ", "ˇ", "ˋ", "˙", "退格", "清注音")))
        }

    private fun symbolRow(symbols: List<String>): HorizontalScrollView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        symbols.forEach { symbol ->
            row.addView(symbolButton(symbol))
        }
        return HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }
    }

    private fun symbolButton(symbol: String): Button =
        Button(this).apply {
            text = symbol
            textSize = if (symbol.length == 1) 18f else 13f
            isAllCaps = false
            setTextColor(0xFF1F2937.toInt())
            setBackgroundColor(0xFFE5E7EB.toInt())
            setOnClickListener {
                when (symbol) {
                    "退格" -> {
                        val current = zhuyinInput.text.toString()
                        if (current.isNotEmpty()) {
                            zhuyinInput.setText(current.dropLast(1))
                            zhuyinInput.setSelection(zhuyinInput.text.length)
                        }
                    }
                    "清注音" -> zhuyinInput.setText("")
                    else -> {
                        zhuyinInput.append(symbol)
                        zhuyinInput.setSelection(zhuyinInput.text.length)
                    }
                }
            }
            layoutParams = LinearLayout.LayoutParams(
                if (symbol.length == 1) dp(48) else dp(76),
                dp(48)
            ).apply {
                marginEnd = dp(6)
                bottomMargin = dp(6)
            }
        }

    private fun editText(hintText: String): EditText =
        EditText(this).apply {
            hint = hintText
            textSize = 16f
            setTextColor(0xFF1F2937.toInt())
            setHintTextColor(0xFF9CA3AF.toInt())
            backgroundTintList = ColorStateList.valueOf(0xFFD1D5DB.toInt())
            setSingleLine(true)
            setPadding(dp(12), 0, dp(12), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(52)
            ).apply {
                bottomMargin = dp(8)
            }
        }

    private fun button(label: String, color: Int, action: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(0xFFFFFFFF.toInt())
            setBackgroundColor(color)
            setOnClickListener { action() }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                bottomMargin = dp(8)
            }
        }

    private fun row(): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

    private fun rowWeight(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginEnd = dp(6)
            bottomMargin = dp(8)
        }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun isDebugBuild(): Boolean =
        (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQUEST_IMPORT = 1001
        private const val REQUEST_EXPORT_DICTIONARY = 1002
        private const val REQUEST_EXPORT_WITH_LEARNING = 1004
        private const val SLIDER_SCALE = 10f
        private const val MAX_VISIBLE_ENTRIES = 500
    }
}
