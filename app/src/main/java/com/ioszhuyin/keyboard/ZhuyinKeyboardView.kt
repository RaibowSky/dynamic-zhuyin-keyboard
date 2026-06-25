package com.ioszhuyin.keyboard

import android.content.Context
import android.graphics.*
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

    // 5 個聲調字元 (Char set, 給 prefix filter 用)
    private val TONE_CHARS: Set<Char> = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')

    // ============================================================
    // 鍵盤模式 (zhuyin / english / number / symbol)
    // ============================================================
    enum class Mode { ZHUYIN, ENGLISH, NUMBER, SYMBOL }

    private var mode: Mode = Mode.ZHUYIN
    fun setMode(m: Mode) {
        mode = m
        if (m != Mode.ZHUYIN) showFinalPage = false
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

    private val cornerRadius = dp(5f)
    private val compositionBarHeight = dp(40f)
    private val candidateBarHeight = dp(40f)
    private val keyH = dp(45f)
    private val controlH = dp(46f)

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
        paintKeyStroke.strokeWidth = dp(0.5f)
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
        paintKeyHint.textSize = dp(9f)

        // Visible keys are always active, like Apple's dynamic Zhuyin keyboard.
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
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        val padX = dp(3f)
        val rowSpacing = dp(4f)
        val keySpacing = dp(4f)
        val controlSpacing = dp(4f)

        val showComposition = composingText.isNotEmpty()
        val compositionH = if (showComposition) compositionBarHeight + rowSpacing else 0f
        val candidateH = if (candidates.isNotEmpty()) candidateBarHeight + rowSpacing else 0f

        val keyboardTotalH = keyH * 3 + rowSpacing * 2 + controlH + controlSpacing
        val contentH = compositionH + candidateH + keyboardTotalH
        val padTop = (height - contentH).coerceAtLeast(0f)

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
                listOf(ControlAction.ENGLISH, ControlAction.NUMBER,
                    ControlAction.SPACE, ControlAction.TONE_SELECT),
                listOf("ABC", "123", "空白", "選定")
            )
            mode == Mode.ZHUYIN -> Pair(
                listOf(ControlAction.ENGLISH, ControlAction.NUMBER,
                    ControlAction.SPACE, ControlAction.RETURN),
                listOf("ABC", "123", "空白", "換行")
            )
            mode == Mode.ENGLISH -> Pair(
                listOf(ControlAction.TOGGLE_FINALS, ControlAction.NUMBER, ControlAction.SYMBOL,
                    ControlAction.SPACE, ControlAction.RETURN, ControlAction.BACKSPACE),
                listOf("注", "123", "#+=", "空白", "換行", "⌫")
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
        val weights = if (mode == Mode.ZHUYIN) {
            listOf(1f, 1f, 4f, 1.5f)  // 4 鍵: ABC / 123 / 空白(寬) / 選定
        } else {
            listOf(1.3f, 1.0f, 1.2f, 4.4f, 1.1f, 1.3f)  // 6 鍵: 正常控制列
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
        paintKeyText.textSize = min(keyH * 0.40f, dp(28f))
        paintControlText.textSize = min(controlH * 0.36f, dp(20f))
        paintComposition.textSize = dp(20f)
        paintCandidateText.textSize = dp(17f)
        paintToneText.textSize = min(keyH * 0.40f, dp(24f))
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
                rowSpec(sourceRows[0], startSlot = 0),
                rowSpec(sourceRows[1], startSlot = 0),
                if (showFinalPage) toneRowSpec(sourceRows[2]) else rowSpec(sourceRows[2], startSlot = 0)
            )
        }
        Mode.ENGLISH -> listOf(
            rowSpec(ENGLISH_R1, startSlot = 0),
            rowSpec(ENGLISH_R2, startSlot = 1),
            rowSpec(ENGLISH_R3, startSlot = 2)
        )
        Mode.NUMBER -> listOf(
            rowSpec(NUMBER_R1, startSlot = 0),
            rowSpec(NUMBER_R2, startSlot = 0),
            rowSpec(NUMBER_R3, startSlot = 0)
        )
        Mode.SYMBOL -> listOf(
            rowSpec(SYMBOL_R1, startSlot = 0),
            rowSpec(SYMBOL_R2, startSlot = 0),
            rowSpec(SYMBOL_R3, startSlot = 0)
        )
    }

    private fun rowSpec(labels: List<String>, startSlot: Int): KeyRowSpec =
        KeyRowSpec(labels.mapIndexed { index, label -> KeySpec(label, (startSlot + index).toFloat()) })

    private fun toneRowSpec(labels: List<String>): KeyRowSpec {
        val tones = labels.filter { it in ZhuyinDynamicLayout.TONES }
        return KeyRowSpec(
            listOf(KeySpec("⇧", 0f)) +
                tones.mapIndexed { index, tone -> KeySpec(tone, 1.45f + index * 1.5f, 1.25f) } +
                listOf(KeySpec("⌫", 8f))
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
            val cx = compositionBarRect.left + dp(14f)
            val cy = compositionBarRect.centerY() - (paintComposition.ascent() + paintComposition.descent()) / 2
            canvas.drawText(text, cx, cy, paintComposition)
        }

        // Candidate bar
        if (candidateBarRect.height() > 0) {
            drawRect(canvas, candidateBarRect, Color.parseColor("#E5E7EB"))
            val cands = candidates.take(9)
            val n = cands.size.coerceAtLeast(1)
            val padInner = dp(2f)
            val candSpacing = dp(2f)
            val moreW = if (hasMoreCandidates) dp(32f) + candSpacing else 0f
            val candTotalW = candidateBarRect.width() - padInner * 2 - moreW
            val candW = (candTotalW - candSpacing * (n + 1)) / n
            for ((i, c) in cands.withIndex()) {
                val x = candidateBarRect.left + padInner + candSpacing + i * (candW + candSpacing)
                val r = RectF(x, candidateBarRect.top + padInner, x + candW, candidateBarRect.bottom - padInner)
                val bg = if (i == selectedCandidateIndex) Color.parseColor("#C7CCD4") else Color.WHITE
                drawRoundRect(canvas, r, bg, dp(4f))
                val cy = r.centerY() - (paintCandidateText.ascent() + paintCandidateText.descent()) / 2
                canvas.drawText(c, r.centerX(), cy, paintCandidateText)
            }
            if (hasMoreCandidates && cands.isNotEmpty()) {
                val moreX = candidateBarRect.right - padInner - dp(32f)
                val moreR = RectF(moreX, candidateBarRect.top + padInner,
                    candidateBarRect.right - padInner, candidateBarRect.bottom - padInner)
                drawRoundRect(canvas, moreR, Color.parseColor("#D1D5DB"), dp(4f))
                val cy = moreR.centerY() - (paintCandidateText.ascent() + paintCandidateText.descent()) / 2
                val saved = paintCandidateText.textSize
                paintCandidateText.textSize = dp(18f)
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
            paintRoundRect.strokeWidth = dp(0.5f)
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
                    val hintY = k.rect.bottom - dp(3f)  // 離底 3dp
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
    }

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
                if (t is TouchTarget.ControlKey && controlKeys[t.idx].action == ControlAction.BACKSPACE) {
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
                val padInner = dp(2f)
                val candSpacing = dp(2f)
                val moreW = if (hasMoreCandidates) dp(32f) + candSpacing else 0f
                val moreX = candidateBarRect.right - padInner - dp(32f)
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
                        key.label == "⌫" -> {
                            onBackspace?.invoke()
                            onBackspaceRelease?.invoke()
                        }
                        key.label.isNotEmpty() -> onKeyPress?.invoke(key.label)
                    }
                } else {
                    if (key.label.isNotEmpty()) onSymbolChar?.invoke(key.label)
                }
            }
            is TouchTarget.ToneKey -> {
                onToneSelected?.invoke(ZhuyinDynamicLayout.TONES[t.idx])
            }
        }
    }

    private fun dp(px: Float): Float = px * resources.displayMetrics.density
    private fun dp(px: Int): Float = px * resources.displayMetrics.density
}
