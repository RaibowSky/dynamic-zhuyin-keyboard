package com.ioszhuyin.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.min

/**
 * v45 動態注音鍵盤 View
 *
 * 跟 v44 的差異:
 *   - v44: 換 stage 時**整片 layout 重畫** (INITIAL→AFTER_CONSONANT→AFTER_FINAL)
 *   - v45: 4×8 固定 layout, 按鍵的 enable/disable 動態改, layout 位置不變
 *
 * 新功能:
 *   - 聲調浮動面板 (4 鍵, 浮在最後一個按鍵上方)
 *   - 動態 enable/disable 帶淡入淡出動畫
 *   - 按鍵點擊是 1 步到位, 不再 stage 切換
 *
 * 保留的 v44 功能:
 *   - compose bar + candidate bar
 *   - 候選分頁 (▶)
 *   - 控制列 (韻/123/🌐/空白/換行/⌫)
 *   - IME 切換、長按 backspace
 */
class ZhuyinKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    var bopomofoTypeface: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)  // 預設值, 外部可覆寫
    private var metrics: KeyboardLayoutMetrics = KeyboardMetrics.current(context)
    private var overlayBitmap: Bitmap? = null
    private var loadedOverlayUri: String? = null
    private val metricsPrefs: SharedPreferences = KeyboardMetrics.prefs(context)
    private val metricsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (KeyboardMetrics.isKeyboardMetricKey(key)) {
                metrics = KeyboardMetrics.current(context)
                overlayBitmap = null
                loadedOverlayUri = null
                refresh()
            }
        }

    // 5 個聲調字元 (Char set, 給 prefix filter 用)
    private val TONE_CHARS: Set<Char> = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')

    // ============================================================
    // 鍵盤模式 (zhuyin / english / number / symbol)
    // ============================================================
    enum class Mode { ZHUYIN, ENGLISH, NUMBER, SYMBOL }

    private var mode: Mode = Mode.ZHUYIN
    private var englishShifted: Boolean = false
    fun setMode(m: Mode) {
        mode = m
        if (m != Mode.ZHUYIN) showFinalPage = false
        if (m != Mode.ENGLISH) englishShifted = false
        refresh()
    }
    fun getMode(): Mode = mode

    private val ENGLISH_R1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val ENGLISH_R2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val ENGLISH_R3 = listOf("z", "x", "c", "v", "b", "n", "m")

    // 數字/符號的固定 layout (3 列 8 鍵)
    // R1: 1 2 3 4 5 6 7 8
    // R2: 9 0 - / : ; ( )
    // R3: . , ? ! ' " @ #
    private val NUMBER_R1 = listOf("1", "2", "3", "4", "5", "6", "7", "8")
    private val NUMBER_R2 = listOf("9", "0", "-", "/", ":", ";", "(", ")")
    private val NUMBER_R3 = listOf(".", ",", "?", "!", "'", "\"", "@", "#")

    // 符號版
    private val SYMBOL_R1 = listOf("[", "]", "{", "}", "#", "%", "^", "*")
    private val SYMBOL_R2 = listOf("+", "=", "_", "\\", "|", "~", "<", ">")
    private val SYMBOL_R3 = listOf("€", "£", "¥", "•", "、", "。", "《", "》")

    private val SYMBOL_R1B = listOf("1", "2", "3", "4", "5", "6", "7", "8")  // 占位

    // ============================================================
    // 公開狀態
    // ============================================================

    /** 當前已拼字串 */
    var composingText: StringBuilder = StringBuilder()

    /** 候選字 (從字典 + 頻率學習) */
    var candidates: List<String> = emptyList()

    /** 候選分頁 */
    var hasMoreCandidates: Boolean = false
    var selectedCandidateIndex: Int = -1
    var onNextCandidatesPage: (() -> Unit)? = null

    /** iOS-style page switch: false = 聲母頁, true = 韻母/聲調頁 */
    private var showFinalPage: Boolean = false
    fun setFinalPage(show: Boolean) {
        if (showFinalPage != show) {
            showFinalPage = show
            refresh()
        }
    }

    // ============================================================
    // Callbacks
    // ============================================================
    var onKeyPress: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onBackspaceRelease: (() -> Unit)? = null
    var onSpace: (() -> Unit)? = null
    var onReturn: (() -> Unit)? = null
    var onSwitchIme: (() -> Unit)? = null
    var onCandidatePress: ((String) -> Unit)? = null
    var onNumberMode: (() -> Unit)? = null
    var onSymbolMode: (() -> Unit)? = null
    var onEnglishMode: (() -> Unit)? = null
    var onSymbolChar: ((String) -> Unit)? = null  // 數字/符號模式直接 commit 字元
    var onToggleToZhuyin: (() -> Unit)? = null  // 數字/符號模式切回注音
    var onEmojiMode: (() -> Unit)? = null
    var onToneSelected: ((String) -> Unit)? = null

    // ============================================================
    // Paints
    // ============================================================
    private val paintKeyText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val paintKeyHint = Paint(Paint.ANTI_ALIAS_FLAG)  // ㄧ/ㄨ/ㄩ 雙重身份提示
    private val paintKeyBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintKeyBgDisabled = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintKeyStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintControlText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val paintComposition = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val paintCandidateText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val paintCandidateBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRoundRect = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintToneBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintToneText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private val cornerRadius: Float get() = dp(metrics.cornerRadius)
    private val compositionBarHeight: Float get() = dp(metrics.compositionBarHeight)
    private val candidateBarHeight: Float get() = dp(metrics.candidateBarHeight)
    private val keyH: Float get() = dp(metrics.keyHeight)
    private val controlH: Float get() = dp(metrics.controlHeight)

    // ============================================================
    // 顏色
    // ============================================================
    private val colorKeyBg = Color.WHITE
    private val colorKeyBgDisabled = Color.parseColor("#F3F4F6")
    private val colorKeyBgPressed = Color.parseColor("#C7CCD4")
    private val colorKeyStroke = Color.parseColor("#D1D5DB")
    private val colorKeyText = Color.parseColor("#1F2937")
    private val colorKeyTextDisabled = Color.parseColor("#D1D5DB")
    private val colorControlBg = Color.parseColor("#ABB1BD")
    private val colorControlBgPressed = Color.parseColor("#9097A3")
    private val colorControlText = Color.WHITE
    private val colorSpaceBg = Color.WHITE
    private val colorSpaceText = Color.parseColor("#374151")
    private val colorReturnBg = Color.parseColor("#007AFF")
    private val colorReturnBgPressed = Color.parseColor("#0056CC")
    private val colorToneBg = Color.parseColor("#007AFF")

    init {
        paintKeyText.color = colorKeyText
        paintKeyText.textAlign = Paint.Align.CENTER
        paintKeyBg.color = colorKeyBg
        paintKeyBgDisabled.color = colorKeyBgDisabled
        paintKeyStroke.color = colorKeyStroke
        paintKeyStroke.style = Paint.Style.STROKE
        paintKeyStroke.strokeWidth = dp(metrics.keyStrokeWidth)
        paintComposition.color = Color.BLACK
        paintComposition.textAlign = Paint.Align.LEFT
        paintCandidateText.color = colorKeyText
        paintCandidateText.textAlign = Paint.Align.CENTER
        paintCandidateBg.color = Color.WHITE
        paintBar.color = Color.parseColor("#E5E7EB")
        paintControlText.color = colorControlText
        paintControlText.textAlign = Paint.Align.CENTER
        paintToneBg.color = colorToneBg
        paintToneText.color = Color.WHITE
        paintToneText.textAlign = Paint.Align.CENTER
        paintKeyHint.color = Color.parseColor("#9CA3AF")  // gray-400
        paintKeyHint.textAlign = Paint.Align.CENTER
        paintKeyHint.textSize = dp(metrics.hintFontSize)

        // Visible keys are always active, like Apple's dynamic Zhuyin keyboard.
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        metricsPrefs.registerOnSharedPreferenceChangeListener(metricsListener)
    }

    override fun onDetachedFromWindow() {
        if (isBackspaceDown) {
            onBackspaceRelease?.invoke()
        }
        pressedKeyIdx = -1
        pressedControlIdx = -1
        pressedToneIdx = -1
        downTarget = null
        isBackspaceDown = false
        metricsPrefs.unregisterOnSharedPreferenceChangeListener(metricsListener)
        super.onDetachedFromWindow()
    }

    // ============================================================
    // Layout 結構
    // ============================================================
    private data class ZhuyinKey(
        val label: String,
        val row: Int,
        val col: Int,
        var rect: RectF = RectF(),
        val isTone: Boolean = false,  // 聲調按鈕 (˙ ˊ ˇ ˋ) 觸發 onToneSelected, 不走 onKeyPress
        val isToggle: Boolean = false  // 上箭頭 (切換聲母頁), 不走 onKeyPress
    )
    private data class KeySpec(
        val label: String,
        val slot: Float,
        val span: Float = 1f
    )
    private data class KeyRowSpec(
        val keys: List<KeySpec>
    )
    private data class ControlKey(
        val label: String,
        val action: ControlAction,
        var rect: RectF = RectF()
    )
    private enum class ControlAction {
        TOGGLE_FINALS, ENGLISH, NUMBER, SYMBOL, EMOJI, SPACE, RETURN, BACKSPACE,
        TONE_SELECT
    }

    private val zhuyinKeys = mutableListOf<ZhuyinKey>()
    private val controlKeys = mutableListOf<ControlKey>()
    private var compositionBarRect: RectF = RectF()
    private var candidateBarRect: RectF = RectF()
    private val toneRects = mutableListOf<RectF>()

    val keyboardContentTop: Float get() {
        if (compositionBarRect.height() > 0f) return compositionBarRect.top
        if (candidateBarRect.height() > 0f) return candidateBarRect.top
        return zhuyinKeys.firstOrNull()?.rect?.top ?: 0f
    }

    // ============================================================
    // Layout 重算
    // ============================================================
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        relayout()
    }

    private fun relayout() {
        metrics = KeyboardMetrics.current(context)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        val padX = dp(metrics.keyboardHorizontalPadding)
        val padTopMetric = dp(metrics.keyboardTopPadding)
        val padBottomMetric = dp(metrics.keyboardBottomPadding)
        val rowSpacing = dp(metrics.verticalGap)
        val keySpacing = dp(metrics.horizontalGap)
        val controlSpacing = dp(metrics.horizontalGap)

        val showComposition = composingText.isNotEmpty()
        val compositionH = if (showComposition) compositionBarHeight + rowSpacing else 0f
        val candidateH = if (candidates.isNotEmpty()) candidateBarHeight + rowSpacing else 0f

        val keyboardTotalH = keyH * 3 + rowSpacing * 2 + controlH + controlSpacing
        val contentH = padTopMetric + compositionH + candidateH + keyboardTotalH + padBottomMetric
        val padTop = (height - contentH).coerceAtLeast(0f) + padTopMetric

        var y = padTop
        if (showComposition) {
            compositionBarRect = RectF(padX, y, width - padX, y + compositionBarHeight)
            y += compositionBarHeight + rowSpacing
        } else {
            compositionBarRect = RectF()
        }
        if (candidates.isNotEmpty()) {
            candidateBarRect = RectF(padX, y, width - padX, y + candidateBarHeight)
            y += candidateBarHeight + rowSpacing
        } else {
            candidateBarRect = RectF()
        }

        // === iOS-style explicit geometry: short rows keep fixed slot offsets ===
        zhuyinKeys.clear()
        val rows = currentKeyRows()
        for ((r, row) in rows.withIndex()) {
            val n = columnCountForCurrentMode()
            val totalSpacing = keySpacing * (n + 1)
            val keyW = (width - 2 * padX - totalSpacing) / n
            for (key in row.keys) {
                val slot = key.slot
                val sym = key.label
                val x = padX + keySpacing + slot * (keyW + keySpacing)
                val spanSpacing = keySpacing * (key.span - 1f).coerceAtLeast(0f)
                val rect = RectF(x, y, x + keyW * key.span + spanSpacing, y + keyH)
                val isTone = mode == Mode.ZHUYIN && sym in ZhuyinDynamicLayout.TONES
                val isToggle = mode == Mode.ZHUYIN && sym == "⇧"
                zhuyinKeys.add(ZhuyinKey(sym, r, slot.toInt(), rect, isTone, isToggle))
            }
            y += keyH + rowSpacing
        }
        y -= rowSpacing  // 最後一列不加分隔
        y += controlSpacing

        // 控制列: 依模式顯示不同 label
        // 聲母頁: 韻 / ABC / 123 / 空白 / 換行 / ⌫
        // 韻母頁: ABC / 123 / 空白(一聲) / 選定
        controlKeys.clear()
        val (actions, labels) = when {
            mode == Mode.ZHUYIN && showFinalPage -> Pair(
                listOf(ControlAction.ENGLISH, ControlAction.NUMBER, ControlAction.EMOJI,
                    ControlAction.SPACE, ControlAction.TONE_SELECT),
                listOf("ABC", "123", "☺", "一聲", "選定")
            )
            mode == Mode.ZHUYIN -> Pair(
                listOf(ControlAction.ENGLISH, ControlAction.NUMBER, ControlAction.EMOJI,
                    ControlAction.SPACE, ControlAction.RETURN),
                listOf("ABC", "123", "☺", "空白", "換行")
            )
            mode == Mode.ENGLISH -> Pair(
                listOf(ControlAction.TOGGLE_FINALS, ControlAction.NUMBER, ControlAction.EMOJI,
                    ControlAction.SPACE, ControlAction.RETURN),
                listOf("注", "123", "☺", "space", "return")
            )
            mode == Mode.NUMBER -> Pair(
                listOf(ControlAction.TOGGLE_FINALS, ControlAction.ENGLISH, ControlAction.SYMBOL,
                    ControlAction.SPACE, ControlAction.RETURN, ControlAction.BACKSPACE),
                listOf("注", "ABC", "#+=", "空白", "換行", "⌫")
            )
            mode == Mode.SYMBOL -> Pair(
                listOf(ControlAction.TOGGLE_FINALS, ControlAction.ENGLISH, ControlAction.NUMBER,
                    ControlAction.SPACE, ControlAction.RETURN, ControlAction.BACKSPACE),
                listOf("注", "ABC", "123", "空白", "換行", "⌫")
            )
            else -> Pair(
                listOf(ControlAction.SPACE, ControlAction.RETURN, ControlAction.BACKSPACE),
                listOf("空白", "換行", "⌫")
            )
        }
        val weights = when (mode) {
            Mode.ZHUYIN -> listOf(
                metrics.functionKeyWidth,
                metrics.functionKeyWidth,
                metrics.functionKeyWidth,
                metrics.spacebarWidthRatio,
                metrics.returnKeyWidthRatio
            )
            Mode.ENGLISH -> listOf(
                metrics.functionKeyWidth,
                metrics.functionKeyWidth,
                metrics.functionKeyWidth,
                metrics.spacebarWidthRatio,
                metrics.returnKeyWidthRatio
            )
            else -> listOf(
                metrics.functionKeyWidth,
                1.0f,
                1.2f,
                metrics.spacebarWidthRatio,
                1.1f,
                metrics.functionKeyWidth
            )
        }
        val totalW = weights.sum()
        val totalSpacing = controlSpacing * (weights.size + 1)
        var x = padX + controlSpacing
        for (i in weights.indices) {
            val w = (width - 2 * padX - totalSpacing) * (weights[i] / totalW)
            val rect = RectF(x, y, x + w, y + controlH)
            controlKeys.add(ControlKey(labels[i], actions[i], rect))
            x += w + controlSpacing
        }

        // 聲調面板: AFTER_FINAL 時聲調在 controlKeys 內 (iOS 26 風格), 不需要額外 toneRects
        toneRects.clear()
        // 字型大小
        paintKeyText.textSize = min(keyH * 0.70f, dp(metrics.keyFontSize))
        paintControlText.textSize = min(controlH * 0.50f, dp(metrics.controlFontSize))
        paintComposition.textSize = dp(metrics.compositionFontSize)
        paintCandidateText.textSize = dp(metrics.candidateFontSize)
        paintToneText.textSize = min(keyH * 0.55f, dp(metrics.toneFontSize))
        paintKeyHint.textSize = dp(metrics.hintFontSize)
    }

    fun refresh() {
        relayout()
        invalidate()
    }

    private fun currentKeyRows(): List<KeyRowSpec> = when (mode) {
        Mode.ZHUYIN -> {
            val sourceRows = if (showFinalPage) {
                ZhuyinDynamicLayout.FINAL_PAGE_ROWS
            } else {
                ZhuyinDynamicLayout.INITIAL_PAGE_ROWS
            }
            listOf(
                rowSpec(sourceRows[0], startSlot = metrics.rowOffset1),
                rowSpec(sourceRows[1], startSlot = if (showFinalPage) 1f else metrics.rowOffset2),
                if (showFinalPage) toneRowSpec(sourceRows[2]) else rowSpec(sourceRows[2], startSlot = metrics.rowOffset3)
            )
        }
        Mode.ENGLISH -> listOf(
            rowSpec(englishLabels(ENGLISH_R1), startSlot = 0f),
            rowSpec(englishLabels(ENGLISH_R2), startSlot = 0.5f),
            englishThirdRowSpec()
        )
        Mode.NUMBER -> listOf(
            rowSpec(NUMBER_R1, startSlot = 0f),
            rowSpec(NUMBER_R2, startSlot = 0f),
            rowSpec(NUMBER_R3, startSlot = 0f)
        )
        Mode.SYMBOL -> listOf(
            rowSpec(SYMBOL_R1, startSlot = 0f),
            rowSpec(SYMBOL_R2, startSlot = 0f),
            rowSpec(SYMBOL_R3, startSlot = 0f)
        )
    }

    private fun rowSpec(labels: List<String>, startSlot: Float): KeyRowSpec =
        KeyRowSpec(labels.mapIndexed { index, label -> KeySpec(label, startSlot + index) })

    private fun englishLabels(labels: List<String>): List<String> =
        if (englishShifted) labels.map { it.uppercase() } else labels

    private fun englishThirdRowSpec(): KeyRowSpec =
        KeyRowSpec(
            listOf(KeySpec("⇧", metrics.rowOffset3, metrics.englishFunctionKeyWidth)) +
                englishLabels(ENGLISH_R3).mapIndexed { index, label ->
                    KeySpec(label, metrics.englishLetterStartSlot + index)
                } +
                listOf(KeySpec("⌫", columnCountForCurrentMode() - metrics.englishFunctionKeyWidth, metrics.englishFunctionKeyWidth))
        )

    private fun toneRowSpec(labels: List<String>): KeyRowSpec {
        val tones = labels.filter { it in ZhuyinDynamicLayout.TONES }
        return KeyRowSpec(
            listOf(KeySpec("⇧", metrics.toneShiftSlot)) +
                tones.mapIndexed { index, tone ->
                    KeySpec(tone, metrics.toneStartSlot + index * metrics.toneKeyStep, metrics.toneKeyWidth)
                } +
                listOf(KeySpec("⌫", metrics.toneBackspaceSlot))
        )
    }

    private fun columnCountForCurrentMode(): Int = when (mode) {
        Mode.ZHUYIN -> 9
        Mode.ENGLISH -> 10
        Mode.NUMBER, Mode.SYMBOL -> 8
    }

    // ============================================================
    // 繪製
    // ============================================================
    private var pressedKeyIdx: Int = -1
    private var pressedControlIdx: Int = -1
    private var pressedToneIdx: Int = -1

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 設定字型
        paintKeyText.typeface = bopomofoTypeface
        paintComposition.typeface = bopomofoTypeface
        paintControlText.typeface = Typeface.DEFAULT
        paintCandidateText.typeface = Typeface.DEFAULT
        paintToneText.typeface = bopomofoTypeface

        // 背景
        val bgTop = when {
            compositionBarRect.height() > 0 -> compositionBarRect.top
            candidateBarRect.height() > 0 -> candidateBarRect.top
            zhuyinKeys.isNotEmpty() -> zhuyinKeys.first().rect.top
            else -> 0f
        }
        val bgBottom = if (controlKeys.isNotEmpty()) controlKeys.last().rect.bottom
                        else if (zhuyinKeys.isNotEmpty()) zhuyinKeys.last().rect.bottom
                        else height.toFloat()
        if (bgBottom > bgTop) {
            paintRoundRect.color = Color.parseColor("#D1D5DB")
            canvas.drawRect(0f, bgTop.coerceAtLeast(0f), width.toFloat(),
                bgBottom.coerceAtMost(height.toFloat()), paintRoundRect)
            // 頂部分隔線
            if (bgTop > 0f) {
                paintRoundRect.color = Color.parseColor("#C8CCD0")
                canvas.drawRect(0f, bgTop, width.toFloat(), bgTop + 1f, paintRoundRect)
            }
        }

        // Compose bar
        if (compositionBarRect.height() > 0) {
            drawRoundRect(canvas, compositionBarRect, Color.WHITE, cornerRadius)
            val text = composingText.toString()
            val cx = compositionBarRect.left + dp(metrics.compositionTextLeftPadding)
            val cy = compositionBarRect.centerY() - (paintComposition.ascent() + paintComposition.descent()) / 2
            canvas.drawText(text, cx, cy, paintComposition)
        }

        // Candidate bar
        if (candidateBarRect.height() > 0) {
            drawRect(canvas, candidateBarRect, Color.parseColor("#E5E7EB"))
            val cands = candidates.take(9)
            val n = cands.size.coerceAtLeast(1)
            val padInner = dp(metrics.candidateInnerPadding)
            val candSpacing = dp(metrics.candidateGap)
            val moreW = if (hasMoreCandidates) dp(metrics.candidateMoreWidth) + candSpacing else 0f
            val candTotalW = candidateBarRect.width() - padInner * 2 - moreW
            val candW = (candTotalW - candSpacing * (n + 1)) / n
            for ((i, c) in cands.withIndex()) {
                val x = candidateBarRect.left + padInner + candSpacing + i * (candW + candSpacing)
                val r = RectF(x, candidateBarRect.top + padInner, x + candW, candidateBarRect.bottom - padInner)
                val bg = if (i == selectedCandidateIndex) Color.parseColor("#C7CCD4") else Color.WHITE
                drawRoundRect(canvas, r, bg, dp(metrics.candidateCornerRadius))
                val cy = r.centerY() - (paintCandidateText.ascent() + paintCandidateText.descent()) / 2
                canvas.drawText(c, r.centerX(), cy, paintCandidateText)
            }
            if (hasMoreCandidates && cands.isNotEmpty()) {
                val moreX = candidateBarRect.right - padInner - dp(metrics.candidateMoreWidth)
                val moreR = RectF(moreX, candidateBarRect.top + padInner,
                    candidateBarRect.right - padInner, candidateBarRect.bottom - padInner)
                drawRoundRect(canvas, moreR, Color.parseColor("#D1D5DB"), dp(metrics.candidateCornerRadius))
                val cy = moreR.centerY() - (paintCandidateText.ascent() + paintCandidateText.descent()) / 2
                val saved = paintCandidateText.textSize
                paintCandidateText.textSize = dp(metrics.candidateMoreFontSize)
                canvas.drawText("▶", moreR.centerX(), cy, paintCandidateText)
                paintCandidateText.textSize = saved
            }

        }

        // === 4×8 注音按鍵 (v45.1 動態 enable/disable + 淡入淡出動畫) ===
        for ((i, k) in zhuyinKeys.withIndex()) {
            if (k.label.isEmpty()) continue
            val isPressed = (i == pressedKeyIdx)

            val bgColor = when {
                isPressed -> colorKeyBgPressed
                else -> colorKeyBg
            }
            drawRoundRect(canvas, k.rect, bgColor, cornerRadius)

            // 邊框
            paintRoundRect.color = colorKeyStroke
            paintRoundRect.style = Paint.Style.STROKE
            paintRoundRect.strokeWidth = dp(metrics.keyStrokeWidth)
            canvas.drawRoundRect(k.rect, cornerRadius, cornerRadius, paintRoundRect)
            paintRoundRect.style = Paint.Style.FILL

            paintKeyText.color = colorKeyText
            val cy = k.rect.centerY() - (paintKeyText.ascent() + paintKeyText.descent()) / 2
            if (k.label.isNotEmpty()) {
                canvas.drawText(k.label, k.rect.centerX(), cy, paintKeyText)
            }

            // ㄧ/ㄨ/ㄩ 雙重身份提示
            // 注音模式下, 這 3 個有「介音 / 獨立韻」兩種身份
            // 顯示: 大字主符號 (中心) + 小字字名 (底部)
            if (k.label.isNotEmpty() && mode == Mode.ZHUYIN && ZhuyinDynamicLayout.MEDIALS.contains(k.label)) {
                val hintText = when (k.label) {
                    "ㄧ" -> "衣"
                    "ㄨ" -> "烏"
                    "ㄩ" -> "淤"
                    else -> ""
                }
                if (hintText.isNotEmpty()) {
                    val hintY = k.rect.bottom - dp(metrics.hintBottomPadding)
                    canvas.drawText(hintText, k.rect.centerX(), hintY, paintKeyHint)
                }
            }
        }

        // 控制列
        for ((i, k) in controlKeys.withIndex()) {
            val isPressed = (i == pressedControlIdx)
            when (k.action) {
                ControlAction.SPACE -> {
                    val c = if (isPressed) Color.parseColor("#E5E7EB") else colorSpaceBg
                    drawRoundRect(canvas, k.rect, c, cornerRadius)
                    paintControlText.color = colorSpaceText
                    val cy = k.rect.centerY() - (paintControlText.ascent() + paintControlText.descent()) / 2
                    canvas.drawText(k.label, k.rect.centerX(), cy, paintControlText)
                }
                ControlAction.RETURN -> {
                    val c = if (isPressed) colorReturnBgPressed else colorReturnBg
                    drawRoundRect(canvas, k.rect, c, cornerRadius)
                    paintControlText.color = colorControlText
                    val cy = k.rect.centerY() - (paintControlText.ascent() + paintControlText.descent()) / 2
                    canvas.drawText(k.label, k.rect.centerX(), cy, paintControlText)
                }
                ControlAction.BACKSPACE -> {
                    val c = if (isPressed) colorControlBgPressed else colorControlBg
                    drawRoundRect(canvas, k.rect, c, cornerRadius)
                    paintControlText.color = colorControlText
                    val cy = k.rect.centerY() - (paintControlText.ascent() + paintControlText.descent()) / 2
                    canvas.drawText(k.label, k.rect.centerX(), cy, paintControlText)
                }
                else -> {
                    val c = if (isPressed) colorControlBgPressed else colorControlBg
                    drawRoundRect(canvas, k.rect, c, cornerRadius)
                    paintControlText.color = colorControlText
                    val cy = k.rect.centerY() - (paintControlText.ascent() + paintControlText.descent()) / 2
                    canvas.drawText(k.label, k.rect.centerX(), cy, paintControlText)
                }
            }
        }

        // === 聲調浮動面板 ===
        // iOS 26 風格: 聲調在 controlKeys 內, 不需要額外繪製
        // (toneRects 已清空)
        drawDebugOverlay(canvas, bgTop, bgBottom)
    }

    private fun drawDebugOverlay(canvas: Canvas, top: Float, bottom: Float) {
        if (!isDebugBuild() || bottom <= top) return
        val prefs = metricsPrefs
        if (!prefs.getBoolean(KeyboardMetrics.KEY_OVERLAY_ENABLED, false)) return
        val uriText = prefs.getString(KeyboardMetrics.KEY_OVERLAY_URI, null) ?: return
        val alpha = prefs.getInt(KeyboardMetrics.KEY_OVERLAY_ALPHA, 40).coerceIn(0, 100)
        if (alpha <= 0) return

        val bitmap = overlayBitmap ?: loadOverlayBitmap(uriText) ?: return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.alpha = (255 * alpha / 100f).toInt()
        }
        canvas.drawBitmap(
            bitmap,
            null,
            RectF(0f, top.coerceAtLeast(0f), width.toFloat(), bottom.coerceAtMost(height.toFloat())),
            paint
        )
    }

    private fun loadOverlayBitmap(uriText: String): Bitmap? {
        if (loadedOverlayUri == uriText && overlayBitmap != null) return overlayBitmap
        return try {
            context.contentResolver.openInputStream(Uri.parse(uriText))?.use { input ->
                BitmapFactory.decodeStream(input)
            }?.also { bitmap ->
                loadedOverlayUri = uriText
                overlayBitmap = bitmap
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun isDebugBuild(): Boolean =
        (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun drawRoundRect(canvas: Canvas, r: RectF, color: Int, radius: Float) {
        paintRoundRect.color = color
        paintRoundRect.style = Paint.Style.FILL
        canvas.drawRoundRect(r, radius, radius, paintRoundRect)
    }
    private fun drawRect(canvas: Canvas, r: RectF, color: Int) {
        paintRoundRect.color = color
        paintRoundRect.style = Paint.Style.FILL
        canvas.drawRect(r, paintRoundRect)
    }

    // ============================================================
    // 觸控
    // ============================================================
    private var downTarget: TouchTarget? = null
    private var isBackspaceDown: Boolean = false

    private sealed class TouchTarget {
        data class Candidate(val idx: Int) : TouchTarget()
        object MorePage : TouchTarget()
        object Composition : TouchTarget()
        data class ZhuyinKey(val idx: Int) : TouchTarget()
        data class ControlKey(val idx: Int) : TouchTarget()
        data class ToneKey(val idx: Int) : TouchTarget()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val t = findTarget(event.x, event.y)
                downTarget = t
                pressedKeyIdx = (t as? TouchTarget.ZhuyinKey)?.idx ?: -1
                pressedControlIdx = (t as? TouchTarget.ControlKey)?.idx ?: -1
                pressedToneIdx = (t as? TouchTarget.ToneKey)?.idx ?: -1
                if (isBackspaceTarget(t)) {
                    isBackspaceDown = true
                    onBackspace?.invoke()
                } else {
                    isBackspaceDown = false
                }
                invalidate()
                return t != null
            }
            MotionEvent.ACTION_MOVE -> {
                val t = findTarget(event.x, event.y)
                val newP = (t as? TouchTarget.ZhuyinKey)?.idx ?: -1
                val newC = (t as? TouchTarget.ControlKey)?.idx ?: -1
                val newT = (t as? TouchTarget.ToneKey)?.idx ?: -1
                if (isBackspaceDown && !isBackspaceTarget(t)) {
                    onBackspaceRelease?.invoke()
                    isBackspaceDown = false
                }
                if (newP != pressedKeyIdx || newC != pressedControlIdx || newT != pressedToneIdx) {
                    pressedKeyIdx = newP
                    pressedControlIdx = newC
                    pressedToneIdx = newT
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                val t = findTarget(event.x, event.y)
                if (t != null && t == downTarget) {
                    handleTarget(t)
                }
                if (isBackspaceDown) {
                    onBackspaceRelease?.invoke()
                }
                pressedKeyIdx = -1
                pressedControlIdx = -1
                pressedToneIdx = -1
                downTarget = null
                isBackspaceDown = false
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (isBackspaceDown) {
                    onBackspaceRelease?.invoke()
                }
                pressedKeyIdx = -1
                pressedControlIdx = -1
                pressedToneIdx = -1
                downTarget = null
                isBackspaceDown = false
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findTarget(x: Float, y: Float): TouchTarget? {
        if (compositionBarRect.height() > 0 && compositionBarRect.contains(x, y)) {
            return TouchTarget.Composition
        }
        if (candidateBarRect.height() > 0 && candidateBarRect.contains(x, y)) {
            val cands = candidates.take(9)
            if (cands.isNotEmpty()) {
                val n = cands.size
                val padInner = dp(metrics.candidateInnerPadding)
                val candSpacing = dp(metrics.candidateGap)
                val moreW = if (hasMoreCandidates) dp(metrics.candidateMoreWidth) + candSpacing else 0f
                val moreX = candidateBarRect.right - padInner - dp(metrics.candidateMoreWidth)
                if (hasMoreCandidates && x > moreX) {
                    return TouchTarget.MorePage
                }
                val candTotalW = candidateBarRect.width() - padInner * 2 - moreW
                val candW = (candTotalW - candSpacing * (n + 1)) / n
                val idx = ((x - candidateBarRect.left - padInner) / (candW + candSpacing)).toInt()
                if (idx in 0 until n && idx < cands.size) {
                    return TouchTarget.Candidate(idx)
                }
            }
        }
        // 控制列 (優先於注音, 因為 y 範圍不會撞)
        for ((i, k) in controlKeys.withIndex()) {
            if (k.rect.contains(x, y)) return TouchTarget.ControlKey(i)
        }
        // 注音按鍵
        for ((i, k) in zhuyinKeys.withIndex()) {
            if (k.label.isNotEmpty() && k.rect.contains(x, y)) return TouchTarget.ZhuyinKey(i)
        }
        return null
    }

    private fun handleTarget(t: TouchTarget) {
        when (t) {
            is TouchTarget.Candidate -> {
                val cands = candidates.take(9)
                if (t.idx in cands.indices) onCandidatePress?.invoke(cands[t.idx])
            }
            is TouchTarget.MorePage -> onNextCandidatesPage?.invoke()
            is TouchTarget.Composition -> {}  // 點 compose bar 不做事
            is TouchTarget.ControlKey -> {
                when (controlKeys[t.idx].action) {
                    ControlAction.SPACE -> onSpace?.invoke()
                    ControlAction.RETURN -> onReturn?.invoke()
                    ControlAction.EMOJI -> onEmojiMode?.invoke()
                    ControlAction.ENGLISH -> onEnglishMode?.invoke()
                    ControlAction.NUMBER -> onNumberMode?.invoke()
                    ControlAction.SYMBOL -> onSymbolMode?.invoke()
                    ControlAction.TOGGLE_FINALS -> {
                        if (mode == Mode.ZHUYIN) {
                            showFinalPage = true
                            refresh()
                        } else {
                            onToggleToZhuyin?.invoke()
                        }
                    }
                    ControlAction.BACKSPACE -> { /* handled on DOWN */ }
                    ControlAction.TONE_SELECT -> {
                        onReturn?.invoke()
                    }
                }
            }
            is TouchTarget.ZhuyinKey -> {
                val key = zhuyinKeys[t.idx]
                if (mode == Mode.ZHUYIN) {
                    when {
                        key.isTone -> onToneSelected?.invoke(key.label)
                        key.isToggle -> onKeyPress?.invoke(key.label)
                        key.label == "⌫" -> { /* handled on DOWN/UP for repeat delete */ }
                        key.label.isNotEmpty() -> onKeyPress?.invoke(key.label)
                    }
                } else {
                    when (key.label) {
                        "⇧" -> {
                            englishShifted = !englishShifted
                            refresh()
                        }
                        "⌫" -> { /* handled on DOWN/UP for repeat delete */ }
                        else -> if (key.label.isNotEmpty()) {
                            onSymbolChar?.invoke(key.label)
                            if (mode == Mode.ENGLISH && englishShifted) {
                                englishShifted = false
                                refresh()
                            }
                        }
                    }
                }
            }
            is TouchTarget.ToneKey -> {
                onToneSelected?.invoke(ZhuyinDynamicLayout.TONES[t.idx])
            }
        }
    }

    private fun isBackspaceTarget(target: TouchTarget?): Boolean = when (target) {
        is TouchTarget.ControlKey -> controlKeys[target.idx].action == ControlAction.BACKSPACE
        is TouchTarget.ZhuyinKey -> zhuyinKeys[target.idx].label == "⌫"
        else -> false
    }

    private fun dp(px: Float): Float = px * resources.displayMetrics.density
    private fun dp(px: Int): Float = px * resources.displayMetrics.density
}
