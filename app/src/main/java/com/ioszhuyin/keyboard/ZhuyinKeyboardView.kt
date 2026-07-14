package com.ioszhuyin.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import kotlin.math.abs
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
    private var zhuyinModeAllowed: Boolean = true
    private var englishShifted: Boolean = false
    private var returnKeyLabel: String = "換行"
    fun setMode(m: Mode) {
        mode = if (m == Mode.ZHUYIN && !zhuyinModeAllowed) Mode.ENGLISH else m
        if (mode != Mode.ZHUYIN) showFinalPage = false
        if (mode != Mode.ENGLISH) englishShifted = false
        refresh()
    }
    fun getMode(): Mode = mode

    fun setReturnKeyLabel(label: String) {
        if (returnKeyLabel == label) return
        returnKeyLabel = label
        refresh()
    }

    fun setZhuyinModeAllowed(allowed: Boolean) {
        if (zhuyinModeAllowed == allowed) return
        zhuyinModeAllowed = allowed
        if (!allowed && mode == Mode.ZHUYIN) {
            mode = Mode.ENGLISH
            showFinalPage = false
        }
        refresh()
    }

    private val ENGLISH_R1 = listOf("q", "w", "e", "r", "t", "y", "u", "i", "o", "p")
    private val ENGLISH_R2 = listOf("a", "s", "d", "f", "g", "h", "j", "k", "l")
    private val ENGLISH_R3 = listOf("z", "x", "c", "v", "b", "n", "m")

    // ============================================================
    // 公開狀態
    // ============================================================

    /** 候選字 (從字典 + 頻率學習) */
    private var candidates: List<String> = emptyList()
    private var candidatePageStart: Int = 0
    private var hasMoreCandidates: Boolean = false
    private var selectedCandidateIndex: Int = -1
    private var candidateExpanded: Boolean = false

    fun updateCandidateState(
        newCandidates: List<String>,
        pageStart: Int,
        selectedIndex: Int,
        hasMore: Boolean,
        expanded: Boolean
    ) {
        if (candidates != newCandidates || candidateExpanded != expanded) {
            candidateScrollOffset = 0f
        }
        candidates = newCandidates
        candidatePageStart = pageStart.coerceIn(0, newCandidates.size)
        selectedCandidateIndex = selectedIndex
        hasMoreCandidates = hasMore
        candidateExpanded = expanded && hasMore
    }

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
    var onCandidateExpansionToggle: (() -> Unit)? = null
    var onCandidatePageSwipe: ((Int) -> Unit)? = null
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
    private val paintKeyBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintKeyBgDisabled = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintKeyStroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintControlText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val paintCandidateText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val paintCandidateBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintBar = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintRoundRect = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintToneBg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val paintToneText = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private val cornerRadius: Float get() = dp(metrics.cornerRadius)
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
    private val colorControlText = Color.parseColor("#1F2937")
    private val colorSpaceBg = Color.WHITE
    private val colorSpaceText = Color.parseColor("#374151")
    private val colorReturnBg = Color.parseColor("#ABB1BD")
    private val colorReturnBgPressed = Color.parseColor("#9097A3")
    private val colorToneBg = Color.parseColor("#007AFF")

    init {
        paintKeyText.color = colorKeyText
        paintKeyText.textAlign = Paint.Align.CENTER
        paintKeyBg.color = colorKeyBg
        paintKeyBgDisabled.color = colorKeyBgDisabled
        paintKeyStroke.color = colorKeyStroke
        paintKeyStroke.style = Paint.Style.STROKE
        paintKeyStroke.strokeWidth = dp(metrics.keyStrokeWidth)
        paintCandidateText.color = colorKeyText
        paintCandidateText.textAlign = Paint.Align.CENTER
        paintCandidateBg.color = Color.WHITE
        paintBar.color = Color.parseColor("#E5E7EB")
        paintControlText.color = colorControlText
        paintControlText.textAlign = Paint.Align.CENTER
        paintToneBg.color = colorToneBg
        paintToneText.color = Color.WHITE
        paintToneText.textAlign = Paint.Align.CENTER
        // Visible keys are always active, like Apple's dynamic Zhuyin keyboard.
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        metricsPrefs.registerOnSharedPreferenceChangeListener(metricsListener)
        requestApplyInsets()
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
    private var candidateBarRect: RectF = RectF()
    private var candidateToggleRect: RectF = RectF()
    private data class CandidateCell(val candidateIndex: Int, val rect: RectF)
    private val candidateCells = mutableListOf<CandidateCell>()
    private var candidateContentHeight = 0f
    private var candidateScrollOffset = 0f
    private val candidateTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private var candidateDragStartY = 0f
    private var candidateDragStartX = 0f
    private var candidateDragStartOffset = 0f
    private var candidateDragging = false
    private var candidateSwipeDirection = 0
    private val toneRects = mutableListOf<RectF>()
    private var systemBottomInset = 0f

    val keyboardContentTop: Float get() {
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

    override fun onApplyWindowInsets(insets: WindowInsets): WindowInsets {
        val bottomInset = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            maxOf(
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsets.Type.mandatorySystemGestures()).bottom
            )
        } else {
            @Suppress("DEPRECATION")
            insets.systemWindowInsetBottom
        }.toFloat()

        if (systemBottomInset != bottomInset) {
            systemBottomInset = bottomInset
            relayout()
            invalidate()
        }
        return super.onApplyWindowInsets(insets)
    }

    private fun relayout() {
        metrics = KeyboardMetrics.current(context)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        val previousKeyLabels = zhuyinKeys.map { it.label }
        val previousPressedKeyIdx = pressedKeyIdx
        val previousDownKeyIdx = (downTarget as? TouchTarget.ZhuyinKey)?.idx

        val padX = dp(metrics.keyboardHorizontalPadding)
        val padTopMetric = dp(metrics.keyboardTopPadding)
        val padBottomMetric = dp(metrics.keyboardBottomPadding) + systemBottomInset
        val rowSpacing = dp(metrics.verticalGap)
        val keySpacing = dp(metrics.horizontalGap)
        val controlSpacing = dp(metrics.horizontalGap)

        val candidateH = if (candidates.isNotEmpty()) candidateBarHeight + rowSpacing else 0f

        val keyboardTotalH = keyH * 3 + rowSpacing * 2 + controlH + controlSpacing
        val contentH = padTopMetric + candidateH + keyboardTotalH + padBottomMetric
        val padTop = (height - contentH).coerceAtLeast(0f) + padTopMetric

        var y = padTop
        if (candidates.isNotEmpty()) {
            val candidateBottom = if (candidateExpanded) {
                (height - padBottomMetric).coerceAtLeast(y + candidateBarHeight)
            } else {
                y + candidateBarHeight
            }
            candidateBarRect = RectF(padX, y, width - padX, candidateBottom)
            y += candidateBarRect.height() + rowSpacing
        } else {
            candidateBarRect = RectF()
        }

        if (candidateExpanded && candidateBarRect.height() > 0f) {
            zhuyinKeys.clear()
            controlKeys.clear()
            toneRects.clear()
            pressedKeyIdx = -1
            pressedControlIdx = -1
            pressedToneIdx = -1
            layoutCandidateCells()
            updateTextSizes()
            return
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
        val currentKeyLabels = zhuyinKeys.map { it.label }
        pressedKeyIdx = PressedKeyRemapping.remapIndex(
            previousKeyLabels,
            currentKeyLabels,
            previousPressedKeyIdx
        )
        if (isBackspaceDown && previousDownKeyIdx != null) {
            val remappedDownKeyIdx = PressedKeyRemapping.remapIndex(
                previousKeyLabels,
                currentKeyLabels,
                previousDownKeyIdx
            )
            downTarget = remappedDownKeyIdx
                .takeIf { it >= 0 }
                ?.let { TouchTarget.ZhuyinKey(it) }
        }
        y -= rowSpacing  // 最後一列不加分隔
        y += controlSpacing

        // 控制列: 注音頁沿用 iOS 的 123 / 表情 / 空白 / 換行配置。
        // 語言切換交給表情／下一個鍵盤按鈕開啟系統輸入法選擇器。
        controlKeys.clear()
        val (actions, labels) = when {
            mode == Mode.ZHUYIN && showFinalPage -> Pair(
                listOf(ControlAction.NUMBER, ControlAction.EMOJI,
                    ControlAction.SPACE, ControlAction.TONE_SELECT),
                ImeBehavior.zhuyinControlLabels(showFinalPage = true)
            )
            mode == Mode.ZHUYIN -> Pair(
                listOf(ControlAction.NUMBER, ControlAction.EMOJI,
                    ControlAction.SPACE, ControlAction.RETURN),
                ImeBehavior.zhuyinControlLabels(
                    showFinalPage = false,
                    returnLabel = returnKeyLabel
                )
            )
            mode == Mode.ENGLISH -> Pair(
                listOf(ControlAction.NUMBER, ControlAction.EMOJI,
                    ControlAction.SPACE, ControlAction.RETURN),
                listOf("123", "☺", "space", returnKeyLabel)
            )
            mode == Mode.NUMBER || mode == Mode.SYMBOL -> Pair(
                listOf(
                    if (zhuyinModeAllowed) ControlAction.TOGGLE_FINALS else ControlAction.ENGLISH,
                    ControlAction.EMOJI,
                    ControlAction.SPACE,
                    ControlAction.RETURN
                ),
                listOf(
                    if (zhuyinModeAllowed) "注" else "ABC",
                    "☺",
                    if (zhuyinModeAllowed) "空白" else "space",
                    returnKeyLabel
                )
            )
            else -> Pair(
                listOf(ControlAction.SPACE, ControlAction.RETURN, ControlAction.BACKSPACE),
                listOf("空白", returnKeyLabel, "⌫")
            )
        }
        val weights = actions.map { action ->
            when (action) {
                ControlAction.SPACE -> metrics.spacebarWidthRatio
                ControlAction.RETURN,
                ControlAction.TONE_SELECT -> metrics.returnKeyWidthRatio
                else -> metrics.functionKeyWidth
            }
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
        layoutCandidateCells()
        updateTextSizes()
    }

    private fun updateTextSizes() {
        paintKeyText.textSize = min(keyH * 0.70f, dp(metrics.keyFontSize))
        paintControlText.textSize = min(controlH * 0.50f, dp(metrics.controlFontSize))
        paintCandidateText.textSize = dp(metrics.candidateFontSize)
        paintToneText.textSize = min(keyH * 0.55f, dp(metrics.toneFontSize))
    }

    private fun layoutCandidateCells() {
        candidateCells.clear()
        candidateToggleRect = RectF()
        candidateContentHeight = 0f
        if (candidateBarRect.height() <= 0f || candidates.isEmpty()) return

        val padInner = dp(metrics.candidateInnerPadding)
        val gap = dp(metrics.candidateGap)
        val toggleWidth = if (hasMoreCandidates) dp(metrics.candidateMoreWidth) else 0f
        if (hasMoreCandidates) {
            candidateToggleRect = RectF(
                candidateBarRect.right - padInner - toggleWidth,
                candidateBarRect.top + padInner,
                candidateBarRect.right - padInner,
                candidateBarRect.top + candidateBarHeight - padInner
            )
        }

        val columns = ImeBehavior.candidatePageSize(candidates)
        val gridRight = if (hasMoreCandidates) {
            candidateBarRect.right - padInner - toggleWidth - gap
        } else {
            candidateBarRect.right - padInner
        }
        val gridWidth = (gridRight - candidateBarRect.left - padInner).coerceAtLeast(1f)
        val cellWidth = ((gridWidth - gap * (columns + 1)) / columns).coerceAtLeast(1f)
        val cellHeight = (candidateBarHeight - padInner * 2).coerceAtLeast(1f)
        val visibleIndices = if (candidateExpanded) {
            candidates.indices
        } else {
            val end = (candidatePageStart + columns).coerceAtMost(candidates.size)
            candidatePageStart until end
        }
        val rowCount = CandidatePanelBehavior.rowCount(visibleIndices.count(), columns)
        candidateContentHeight = padInner * 2 + rowCount * cellHeight +
            (rowCount - 1).coerceAtLeast(0) * gap
        candidateScrollOffset = CandidatePanelBehavior.clampScroll(
            candidateScrollOffset,
            candidateContentHeight,
            candidateBarRect.height()
        )

        visibleIndices.forEachIndexed { localIndex, candidateIndex ->
            val row = localIndex / columns
            val column = localIndex % columns
            val left = candidateBarRect.left + padInner + gap + column * (cellWidth + gap)
            val top = candidateBarRect.top + padInner +
                row * (cellHeight + gap) - candidateScrollOffset
            candidateCells += CandidateCell(
                candidateIndex,
                RectF(left, top, left + cellWidth, top + cellHeight)
            )
        }
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
            rowSpec(IosAuxiliaryLayout.NUMBER_ROWS[0], startSlot = 0f),
            rowSpec(IosAuxiliaryLayout.NUMBER_ROWS[1], startSlot = 0f),
            auxiliaryThirdRowSpec(IosAuxiliaryLayout.NUMBER_ROWS[2])
        )
        Mode.SYMBOL -> listOf(
            rowSpec(IosAuxiliaryLayout.SYMBOL_ROWS[0], startSlot = 0f),
            rowSpec(IosAuxiliaryLayout.SYMBOL_ROWS[1], startSlot = 0f),
            auxiliaryThirdRowSpec(IosAuxiliaryLayout.SYMBOL_ROWS[2])
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

    private fun auxiliaryThirdRowSpec(labels: List<String>): KeyRowSpec {
        val punctuation = labels.subList(1, labels.lastIndex)
        return KeyRowSpec(
            listOf(KeySpec(labels.first(), 0f, 1.25f)) +
                punctuation.mapIndexed { index, label ->
                    KeySpec(label, 1.75f + index * 1.3f)
                } +
                listOf(KeySpec(labels.last(), 8.75f, 1.25f))
        )
    }

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
        Mode.NUMBER, Mode.SYMBOL -> 10
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
        paintControlText.typeface = Typeface.DEFAULT
        paintCandidateText.typeface = Typeface.DEFAULT
        paintToneText.typeface = bopomofoTypeface

        // 背景
        val bgTop = when {
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
                height.toFloat(), paintRoundRect)
            // 頂部分隔線
            if (bgTop > 0f) {
                paintRoundRect.color = Color.parseColor("#C8CCD0")
                canvas.drawRect(0f, bgTop, width.toFloat(), bgTop + 1f, paintRoundRect)
            }
        }

        // Candidate bar
        if (candidateBarRect.height() > 0) {
            drawRect(canvas, candidateBarRect, Color.parseColor("#E5E7EB"))
            canvas.save()
            canvas.clipRect(candidateBarRect)
            for (cell in candidateCells) {
                if (!RectF.intersects(candidateBarRect, cell.rect)) continue
                val bg = if (cell.candidateIndex == selectedCandidateIndex) {
                    Color.parseColor("#C7CCD4")
                } else {
                    Color.WHITE
                }
                drawRoundRect(canvas, cell.rect, bg, dp(metrics.candidateCornerRadius))
                val cy = cell.rect.centerY() -
                    (paintCandidateText.ascent() + paintCandidateText.descent()) / 2
                canvas.drawText(
                    candidates[cell.candidateIndex],
                    cell.rect.centerX(),
                    cy,
                    paintCandidateText
                )
            }
            canvas.restore()
            if (candidateToggleRect.height() > 0f) {
                drawRoundRect(
                    canvas,
                    candidateToggleRect,
                    Color.parseColor("#D1D5DB"),
                    dp(metrics.candidateCornerRadius)
                )
                drawCandidateToggleIcon(canvas, candidateToggleRect, candidateExpanded)
            }
        }

        // === 4×8 注音按鍵 (v45.1 動態 enable/disable + 淡入淡出動畫) ===
        for ((i, k) in zhuyinKeys.withIndex()) {
            if (k.label.isEmpty()) continue
            val isPressed = (i == pressedKeyIdx)
            val isFunctionKey = k.isToggle || k.label in setOf("⌫", "#+=", "123")

            val bgColor = when {
                isPressed && isFunctionKey -> colorControlBgPressed
                isPressed -> colorKeyBgPressed
                isFunctionKey -> colorControlBg
                else -> colorKeyBg
            }
            drawRoundRect(canvas, k.rect, bgColor, cornerRadius)

            // 邊框
            paintRoundRect.color = colorKeyStroke
            paintRoundRect.style = Paint.Style.STROKE
            paintRoundRect.strokeWidth = dp(metrics.keyStrokeWidth)
            canvas.drawRoundRect(k.rect, cornerRadius, cornerRadius, paintRoundRect)
            paintRoundRect.style = Paint.Style.FILL

            val labelPaint = if (k.label in setOf("#+=", "123")) {
                paintControlText
            } else {
                paintKeyText
            }
            labelPaint.color = colorKeyText
            val cy = k.rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
            if (k.label.isNotEmpty()) {
                canvas.drawText(k.label, k.rect.centerX(), cy, labelPaint)
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
                    if (k.action == ControlAction.EMOJI) {
                        drawMonochromeEmojiIcon(canvas, k.rect, colorControlText)
                    } else {
                        val cy = k.rect.centerY() -
                            (paintControlText.ascent() + paintControlText.descent()) / 2
                        canvas.drawText(k.label, k.rect.centerX(), cy, paintControlText)
                    }
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

    private fun drawCandidateToggleIcon(canvas: Canvas, rect: RectF, expanded: Boolean) {
        val halfWidth = min(rect.width(), rect.height()) * 0.18f
        val halfHeight = halfWidth * 0.55f
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        paintRoundRect.color = colorKeyText
        paintRoundRect.style = Paint.Style.STROKE
        paintRoundRect.strokeWidth = dp(1.8f)
        paintRoundRect.strokeCap = Paint.Cap.ROUND
        val path = Path()
        if (expanded) {
            path.moveTo(centerX - halfWidth, centerY - halfHeight)
            path.lineTo(centerX, centerY + halfHeight)
            path.lineTo(centerX + halfWidth, centerY - halfHeight)
        } else {
            path.moveTo(centerX - halfWidth, centerY + halfHeight)
            path.lineTo(centerX, centerY - halfHeight)
            path.lineTo(centerX + halfWidth, centerY + halfHeight)
        }
        canvas.drawPath(path, paintRoundRect)
        paintRoundRect.strokeCap = Paint.Cap.BUTT
        paintRoundRect.style = Paint.Style.FILL
    }

    private fun drawMonochromeEmojiIcon(canvas: Canvas, rect: RectF, color: Int) {
        val radius = min(rect.width(), rect.height()) * 0.22f
        val centerX = rect.centerX()
        val centerY = rect.centerY()
        paintRoundRect.color = color
        paintRoundRect.style = Paint.Style.STROKE
        paintRoundRect.strokeWidth = dp(1.5f)
        canvas.drawCircle(centerX, centerY, radius, paintRoundRect)
        paintRoundRect.style = Paint.Style.FILL
        val eyeRadius = dp(1.2f)
        canvas.drawCircle(centerX - radius * 0.36f, centerY - radius * 0.22f, eyeRadius, paintRoundRect)
        canvas.drawCircle(centerX + radius * 0.36f, centerY - radius * 0.22f, eyeRadius, paintRoundRect)
        paintRoundRect.style = Paint.Style.STROKE
        paintRoundRect.strokeWidth = dp(1.5f)
        canvas.drawArc(
            centerX - radius * 0.48f,
            centerY - radius * 0.02f,
            centerX + radius * 0.48f,
            centerY + radius * 0.55f,
            12f,
            156f,
            false,
            paintRoundRect
        )
        paintRoundRect.style = Paint.Style.FILL
    }

    // ============================================================
    // 觸控
    // ============================================================
    private var downTarget: TouchTarget? = null
    private var isBackspaceDown: Boolean = false

    private sealed class TouchTarget {
        data class Candidate(val idx: Int) : TouchTarget()
        object MorePage : TouchTarget()
        object CandidatePanel : TouchTarget()
        data class ZhuyinKey(val idx: Int) : TouchTarget()
        data class ControlKey(val idx: Int) : TouchTarget()
        data class ToneKey(val idx: Int) : TouchTarget()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                val t = findTarget(event.x, event.y)
                downTarget = t
                candidateDragStartX = event.x
                candidateDragStartY = event.y
                candidateDragStartOffset = candidateScrollOffset
                candidateDragging = false
                candidateSwipeDirection = 0
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
                if (!candidateExpanded &&
                    (downTarget is TouchTarget.Candidate ||
                        downTarget is TouchTarget.CandidatePanel)
                ) {
                    val dragX = event.x - candidateDragStartX
                    val dragY = event.y - candidateDragStartY
                    if (candidateDragging ||
                        (abs(dragX) > candidateTouchSlop && abs(dragX) > abs(dragY))
                    ) {
                        candidateDragging = true
                        candidateSwipeDirection = if (dragX < 0f) 1 else -1
                        invalidate()
                        return true
                    }
                }
                if (candidateExpanded && candidateBarRect.contains(event.x, event.y) &&
                    (downTarget is TouchTarget.Candidate ||
                        downTarget is TouchTarget.CandidatePanel)
                ) {
                    val dragDistance = event.y - candidateDragStartY
                    if (candidateDragging || abs(dragDistance) > candidateTouchSlop) {
                        candidateDragging = true
                        candidateScrollOffset = CandidatePanelBehavior.clampScroll(
                            candidateDragStartOffset - dragDistance,
                            candidateContentHeight,
                            candidateBarRect.height()
                        )
                        layoutCandidateCells()
                        invalidate()
                        return true
                    }
                }
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
                if (candidateDragging && candidateSwipeDirection != 0) {
                    onCandidatePageSwipe?.invoke(candidateSwipeDirection)
                } else if (!candidateDragging && t != null && t == downTarget) {
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
                candidateDragging = false
                candidateSwipeDirection = 0
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
                candidateDragging = false
                candidateSwipeDirection = 0
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun findTarget(x: Float, y: Float): TouchTarget? {
        if (candidateBarRect.height() > 0 && candidateBarRect.contains(x, y)) {
            if (candidateToggleRect.contains(x, y)) {
                return TouchTarget.MorePage
            }
            candidateCells.firstOrNull { cell ->
                RectF.intersects(candidateBarRect, cell.rect) && cell.rect.contains(x, y)
            }?.let { return TouchTarget.Candidate(it.candidateIndex) }
            return TouchTarget.CandidatePanel
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
                candidates.getOrNull(t.idx)?.let { onCandidatePress?.invoke(it) }
            }
            is TouchTarget.MorePage -> onCandidateExpansionToggle?.invoke()
            is TouchTarget.CandidatePanel -> {}
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
                        "#+=" -> onSymbolMode?.invoke()
                        "123" -> onNumberMode?.invoke()
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
