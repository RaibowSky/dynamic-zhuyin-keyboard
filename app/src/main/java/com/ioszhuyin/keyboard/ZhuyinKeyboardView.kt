package com.ioszhuyin.keyboard

import android.content.Context
import android.content.SharedPreferences
import android.graphics.*
import android.content.res.Configuration
import android.media.AudioManager
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
    var customTypeface: Typeface? = null
        set(value) { field = value; refresh() }
    private var metrics: KeyboardLayoutMetrics = KeyboardMetrics.current(context)
    private val metricsPrefs: SharedPreferences = KeyboardMetrics.prefs(context)
    private val metricsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (KeyboardMetrics.isKeyboardMetricKey(key)) {
                metrics = KeyboardMetrics.current(context)
                refresh()
            }
        }

    // 5 個聲調字元 (Char set, 給 prefix filter 用)
    private val TONE_CHARS: Set<Char> = setOf('ˉ', '˙', 'ˊ', 'ˇ', 'ˋ')

    // ============================================================
    // 鍵盤模式 (zhuyin / english / number / symbol)
    // ============================================================
    enum class Mode {
        ZHUYIN,
        ENGLISH,
        EMOJI,
        NUMBER,
        SYMBOL,
        HALF_WIDTH_NUMBER,
        HALF_WIDTH_SYMBOL
    }

    private var mode: Mode = Mode.ZHUYIN
    private var zhuyinModeAllowed: Boolean = true
    private var englishShifted: Boolean = false
    private var returnKeyLabel: String = "換行"
    fun setMode(m: Mode) {
        mode = if (m in setOf(Mode.ZHUYIN, Mode.EMOJI) && !zhuyinModeAllowed) Mode.ENGLISH else m
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
        if (!allowed && mode in setOf(Mode.ZHUYIN, Mode.EMOJI)) {
            mode = Mode.ENGLISH
            showFinalPage = false
        }
        refresh()
    }

    // ============================================================
    // 公開狀態
    // ============================================================

    /** 候選字 (從字典 + 頻率學習) */
    private var candidates: List<String> = emptyList()
    private var composingActive = false
    private var candidatePageStart: Int = 0
    private var hasMoreCandidates: Boolean = false
    private var selectedCandidateIndex: Int = -1
    private var candidateExpanded: Boolean = false

    fun updateCandidateState(
        newCandidates: List<String>,
        pageStart: Int,
        selectedIndex: Int,
        hasMore: Boolean,
        expanded: Boolean,
        composing: Boolean = false
    ) {
        if (candidates != newCandidates || candidateExpanded != expanded) {
            candidateScrollOffset = 0f
        }
        candidates = newCandidates
        composingActive = composing
        if (composing || newCandidates.isNotEmpty()) editTools = false
        candidatePageStart = pageStart.coerceIn(0, newCandidates.size)
        selectedCandidateIndex = selectedIndex
        hasMoreCandidates = hasMore
        candidateExpanded = expanded && hasMore
    }

    /** iOS-style page switch: false = 聲母頁, true = 韻母/聲調頁 */
    private var showFinalPage: Boolean = false
    fun setFinalPage(show: Boolean) {
        showFinalPage = show
    }

    // ============================================================
    // Callbacks
    // ============================================================
    var onKeyPress: ((String) -> Unit)? = null
    var onBackspace: (() -> Unit)? = null
    var onBackspaceRelease: (() -> Unit)? = null
    var onSpace: (() -> Unit)? = null
    var onWidthToggle: (() -> Unit)? = null
    var onEmoji: (() -> Unit)? = null
    var onPaste: (() -> Unit)? = null
    var onSettings: (() -> Unit)? = null
    var onEditAction: ((String) -> Unit)? = null
    private var editTools = false
    var onCursorGestureStart: (() -> Boolean)? = null
    var onCursorMove: ((Int) -> Unit)? = null
    var onReturn: (() -> Unit)? = null
    var onCandidateConfirm: (() -> Unit)? = null
    var onCandidatePress: ((Int) -> Unit)? = null
    var onCandidateExpansionToggle: (() -> Unit)? = null
    var onCandidatePageSwipe: ((Int) -> Unit)? = null
    var onNumberMode: (() -> Unit)? = null
    var onSymbolMode: (() -> Unit)? = null
    var onEnglishMode: (() -> Unit)? = null
    var onSymbolChar: ((String) -> Unit)? = null  // 數字/符號模式直接 commit 字元
    var onToggleToZhuyin: (() -> Unit)? = null  // 數字/符號模式切回注音
    var onToneSelected: ((String) -> Unit)? = null
    /** Fired at finger-down, before dictionary or editor work, so the press feels immediate. */
    var onKeyFeedback: (() -> Unit)? = null

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
    private var layoutHeightScale = 1f
    private val candidateBarHeight: Float get() = dp(metrics.candidateBarHeight) * layoutHeightScale
    private val keyH: Float get() = dp(metrics.keyHeight) * layoutHeightScale
    private val controlH: Float get() = dp(metrics.controlHeight) * layoutHeightScale

    // ============================================================
    // 顏色
    // ============================================================
    private var palette: KeyboardPalette = ThemePalette.keyboard(context)
    private val colorKeyBg get() = palette.keyBackground
    private val colorKeyBgDisabled get() = palette.keyBackgroundDisabled
    private val colorKeyBgPressed get() = palette.keyBackgroundPressed
    private val colorKeyStroke get() = palette.keyStroke
    private val colorKeyText get() = palette.keyText
    private val colorKeyTextDisabled get() = palette.keyTextDisabled
    private val colorControlBg get() = palette.controlBackground
    private val colorControlBgPressed get() = palette.controlBackgroundPressed
    private val colorControlText get() = palette.controlText
    private val colorSpaceBg get() = palette.spaceBackground
    private val colorSpaceText get() = palette.spaceText
    private val colorReturnBg get() = palette.returnBackground
    private val colorReturnBgPressed get() = palette.returnBackgroundPressed
    private val colorToneBg get() = palette.toneBackground

    init {
        applyPalette(palette)
        paintKeyText.textAlign = Paint.Align.CENTER
        paintKeyStroke.style = Paint.Style.STROKE
        paintKeyStroke.strokeWidth = dp(metrics.keyStrokeWidth)
        paintCandidateText.textAlign = Paint.Align.CENTER
        paintControlText.textAlign = Paint.Align.CENTER
        paintToneText.textAlign = Paint.Align.CENTER
        // Visible keys are always active, like Apple's dynamic Zhuyin keyboard.
    }

    fun applySystemTheme() {
        applyPalette(ThemePalette.keyboard(context))
        invalidate()
    }

    private fun applyPalette(next: KeyboardPalette) {
        palette = next
        // The IME view can occupy the screen above its bottom-aligned keys.
        // Paint only the keyboard bounds in onDraw so the editor stays visible.
        setBackgroundColor(Color.TRANSPARENT)
        paintKeyText.color = colorKeyText
        paintKeyBg.color = colorKeyBg
        paintKeyBgDisabled.color = colorKeyBgDisabled
        paintKeyStroke.color = colorKeyStroke
        paintCandidateText.color = colorKeyText
        paintCandidateBg.color = palette.candidateBackground
        paintBar.color = palette.candidateBar
        paintControlText.color = colorControlText
        paintToneBg.color = colorToneBg
        paintToneText.color = palette.toneText
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        metricsPrefs.registerOnSharedPreferenceChangeListener(metricsListener)
        requestApplyInsets()
    }

    override fun onDetachedFromWindow() {
        cancelAllContacts()
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
        TOGGLE_FINALS, ENGLISH, NUMBER, SYMBOL, SPACE, RETURN, BACKSPACE, TONE_SELECT
    }

    private val zhuyinKeys = mutableListOf<ZhuyinKey>()
    private var keyHitBoxes: List<KeyboardHitBox> = emptyList()
    private var controlHitBoxes: List<KeyboardHitBox> = emptyList()
    private val controlKeys = mutableListOf<ControlKey>()
    private data class ToolKey(val label: String, val rect: RectF)
    private val toolKeys = mutableListOf<ToolKey>()
    private var toolbarRect = RectF()
    private val paintToolbar = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private var candidateBarRect: RectF = RectF()
    private var candidateToggleRect: RectF = RectF()
    private data class CandidateCell(val candidateIndex: Int, val rect: RectF)
    private val candidateCells = mutableListOf<CandidateCell>()
    private var candidateContentHeight = 0f
    private var candidateScrollOffset = 0f
    private val candidateTouchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val toneRects = mutableListOf<RectF>()
    private var systemBottomInset = 0f
    internal val navigationBottomInset: Int get() = systemBottomInset.toInt()

    val keyboardContentTop: Float get() {
        val headerTop = when {
            toolbarRect.height() > 0f -> toolbarRect.top
            candidateBarRect.height() > 0f -> candidateBarRect.top
            else -> return zhuyinKeys.firstOrNull()?.rect?.top ?: 0f
        }
        return (headerTop - dp(metrics.keyboardTopPadding) * layoutHeightScale).coerceAtLeast(0f)
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

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applySystemTheme()
    }

    private fun relayout() {
        metrics = KeyboardMetrics.current(context)
        val width = width.toFloat()
        val height = height.toFloat()
        if (width <= 0 || height <= 0) return

        // Reserve editor space on short/landscape displays even with maximum settings.
        val requestedHeight = metrics.keyboardTopPadding + metrics.candidateBarHeight + metrics.verticalGap +
            metrics.keyHeight * 3 + metrics.verticalGap * 2 + metrics.controlHeight +
            metrics.horizontalGap + metrics.keyboardBottomPadding
        val availableKeyboardHeight = ((height - systemBottomInset) * 0.7f).coerceAtLeast(1f)
        layoutHeightScale = min(1f, availableKeyboardHeight / dp(requestedHeight))

        val previousKeyLabels = zhuyinKeys.map { it.label }
        val previousPressedKeyIdx = pressedKeyIdx

        val padX = dp(metrics.keyboardHorizontalPadding)
        val padTopMetric = dp(metrics.keyboardTopPadding) * layoutHeightScale
        val padBottomMetric = dp(metrics.keyboardBottomPadding) * layoutHeightScale + systemBottomInset
        val rowSpacing = dp(metrics.verticalGap) * layoutHeightScale
        val keySpacing = dp(metrics.horizontalGap)
        val controlSpacing = dp(metrics.horizontalGap)

        val keyboardTotalH = keyH * 3 + rowSpacing * 2 + controlH + controlSpacing * layoutHeightScale
        // One fixed-height header switches between tools and candidates without moving keys.
        val contentH = padTopMetric + candidateBarHeight + rowSpacing + keyboardTotalH + padBottomMetric
        val padTop = (height - contentH).coerceAtLeast(0f) + padTopMetric

        var y = padTop
        val showingCandidates = composingActive || candidates.isNotEmpty()
        toolbarRect = if (showingCandidates) RectF() else RectF(padX, y, width - padX, y + candidateBarHeight)
        val toolLabels = when {
            zhuyinModeAllowed && editTools -> EDIT_TOOL_LABELS
            zhuyinModeAllowed -> listOf(
            if (mode in setOf(Mode.ENGLISH, Mode.HALF_WIDTH_NUMBER, Mode.HALF_WIDTH_SYMBOL)) "半形" else "全形",
            if (mode == Mode.EMOJI) "返回" else "😊", "剪貼簿", "編輯", "設定"
            )
            else -> listOf("設定")
        }
        toolKeys.clear()
        val toolWidth = toolbarRect.width() / toolLabels.size
        if (!showingCandidates) toolLabels.forEachIndexed { index, label ->
            toolKeys += ToolKey(label, RectF(padX + index * toolWidth, y,
                padX + (index + 1) * toolWidth, y + candidateBarHeight))
        }
        if (showingCandidates) {
            val candidateBottom = if (candidateExpanded) {
                (height - padBottomMetric).coerceAtLeast(y + candidateBarHeight)
            } else {
                y + candidateBarHeight
            }
            candidateBarRect = RectF(padX, y, width - padX, candidateBottom)
            y += candidateBarRect.height() + rowSpacing
        } else {
            candidateBarRect = RectF()
            y += candidateBarHeight + rowSpacing
        }

        if (candidateExpanded && candidateBarRect.height() > 0f) {
            zhuyinKeys.clear()
            keyHitBoxes = emptyList()
            controlHitBoxes = emptyList()
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
        y -= rowSpacing  // 最後一列不加分隔
        y += controlSpacing * layoutHeightScale

        // ABC / 注在本鍵盤內往返；系統導覽列保留切換其他輸入法的入口。
        controlKeys.clear()
        val (actions, labels) = when {
            mode == Mode.ZHUYIN && showFinalPage -> Pair(
                listOf(ControlAction.NUMBER, ControlAction.ENGLISH,
                    ControlAction.SPACE, ControlAction.TONE_SELECT),
                ImeBehavior.zhuyinControlLabels(showFinalPage = true)
            )
            mode == Mode.ZHUYIN -> Pair(
                listOf(ControlAction.NUMBER, ControlAction.ENGLISH,
                    ControlAction.SPACE, ControlAction.RETURN),
                ImeBehavior.zhuyinControlLabels(
                    showFinalPage = false,
                    returnLabel = returnKeyLabel
                )
            )
            mode == Mode.ENGLISH || mode == Mode.EMOJI -> if (zhuyinModeAllowed) {
                Pair(
                    listOf(ControlAction.NUMBER, ControlAction.TOGGLE_FINALS,
                        ControlAction.SPACE, ControlAction.RETURN),
                    listOf("123", "注", "space", returnKeyLabel)
                )
            } else {
                Pair(
                    listOf(ControlAction.NUMBER, ControlAction.SPACE, ControlAction.RETURN),
                    listOf("123", "space", returnKeyLabel)
                )
            }
            mode in setOf(
                Mode.NUMBER,
                Mode.SYMBOL,
                Mode.HALF_WIDTH_NUMBER,
                Mode.HALF_WIDTH_SYMBOL
            ) -> Pair(
                listOf(
                    if (zhuyinModeAllowed) ControlAction.TOGGLE_FINALS else ControlAction.ENGLISH,
                    *if (zhuyinModeAllowed) {
                        arrayOf(ControlAction.ENGLISH, ControlAction.SPACE, ControlAction.RETURN)
                    } else {
                        arrayOf(ControlAction.SPACE, ControlAction.RETURN)
                    }
                ),
                listOf(
                    if (zhuyinModeAllowed) "注" else "ABC",
                    *if (zhuyinModeAllowed) {
                        arrayOf("ABC", "空白", returnKeyLabel)
                    } else {
                        arrayOf("space", returnKeyLabel)
                    }
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
        val keyTop = zhuyinKeys.minOfOrNull { it.rect.top } ?: y
        val keyBottom = zhuyinKeys.maxOfOrNull { it.rect.bottom } ?: y
        val controlTop = controlKeys.minOfOrNull { it.rect.top } ?: (height - systemBottomInset)
        val rowSplit = if (zhuyinKeys.isNotEmpty() && controlKeys.isNotEmpty()) {
            (keyBottom + controlTop) / 2f
        } else {
            height - systemBottomInset
        }
        keyHitBoxes = KeyboardHitTesting.expandEnabledHits(
            boxes = zhuyinKeys.map { key ->
                KeyboardHitBox(
                    left = key.rect.left,
                    top = key.rect.top,
                    right = key.rect.right,
                    bottom = key.rect.bottom,
                    enabled = key.label.isNotEmpty()
                )
            },
            rows = zhuyinKeys.map { it.row },
            leftBound = 0f,
            rightBound = width.toFloat(),
            topBound = keyTop - rowSpacing,
            bottomBound = rowSplit
        )
        controlHitBoxes = KeyboardHitTesting.expandEnabledHits(
            boxes = controlKeys.map { key ->
                KeyboardHitBox(
                    left = key.rect.left,
                    top = key.rect.top,
                    right = key.rect.right,
                    bottom = key.rect.bottom,
                    enabled = true
                )
            },
            rows = List(controlKeys.size) { 0 },
            leftBound = 0f,
            rightBound = width.toFloat(),
            topBound = rowSplit,
            bottomBound = height - systemBottomInset
        )
        remapHeldContacts(previousKeyLabels, currentKeyLabels)
        layoutCandidateCells()
        updateTextSizes()
    }

    /** Keep a finger attached to the label it pressed when a page change moves that key. */
    private fun remapHeldContacts(previousKeyLabels: List<String>, currentKeyLabels: List<String>) {
        for (contact in contacts.values) {
            when (val target = contact.target) {
                is TouchTarget.ZhuyinKey -> {
                    val previousIndex = previousKeyLabels.indexOf(target.label).takeIf { it >= 0 }
                        ?: target.idx
                    contact.target = target.copy(
                        idx = PressedKeyRemapping.remapIndex(
                            previousKeyLabels,
                            currentKeyLabels,
                            previousIndex
                        )
                    )
                }
                is TouchTarget.ControlKey -> {
                    contact.target = target.copy(
                        idx = controlKeys.indexOfFirst { it.action == target.action }
                    )
                }
                else -> Unit
            }
        }
        if (contacts.isNotEmpty()) {
            applyHighlight(contacts.values.lastOrNull { !it.resolved }?.target)
        }
    }

    private fun updateTextSizes() {
        paintKeyText.textSize = min(keyH * 0.70f, dp(metrics.keyFontSize))
        paintControlText.textSize = min(controlH * 0.50f, dp(metrics.controlFontSize))
        paintCandidateText.textSize = min(dp(metrics.candidateFontSize), candidateBarHeight * 0.8f)
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

        val columns = min(candidates.size, ImeBehavior.candidatePageSize(candidates))
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
        Mode.EMOJI -> listOf(
            rowSpec(listOf("😀", "😄", "😂", "😊", "😍", "🥰", "😘", "😎", "🥳", "😭"), 0f),
            rowSpec(listOf("😅", "🤣", "😢", "😡", "🤔", "🙏", "👍", "👏", "💪", "❤️"), 0f),
            rowSpec(listOf("🎉", "🎂", "🎁", "✨", "🔥", "✅", "👋", "🌹", "☕", "⌫"), 0f)
        )
        Mode.ZHUYIN -> {
            val sourceRows = if (showFinalPage) {
                ZhuyinDynamicLayout.FINAL_PAGE_ROWS
            } else {
                ZhuyinDynamicLayout.INITIAL_PAGE_ROWS
            }
            val startSlots = ZhuyinDynamicLayout.rowStartSlots(
                showFinalPage = showFinalPage,
                configuredOffsets = listOf(
                    metrics.rowOffset1,
                    metrics.rowOffset2,
                    metrics.rowOffset3
                )
            )
            listOf(
                rowSpec(sourceRows[0], startSlot = startSlots[0]),
                rowSpec(sourceRows[1], startSlot = startSlots[1]),
                if (showFinalPage) toneRowSpec(sourceRows[2]) else {
                    rowSpec(sourceRows[2], startSlot = startSlots[2])
                }
            )
        }
        Mode.ENGLISH -> listOf(
            rowSpec(englishLabels("qwertyuiop"), startSlot = 0f),
            rowSpec(englishLabels("asdfghjkl"), startSlot = 0.5f),
            KeyRowSpec(
                listOf(KeySpec("⇧", 0f, 1.25f)) +
                    englishLabels("zxcvbnm").mapIndexed { index, label ->
                        KeySpec(label, 1.5f + index)
                    } + listOf(KeySpec("⌫", 8.75f, 1.25f))
            )
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
        Mode.HALF_WIDTH_NUMBER -> listOf(
            rowSpec(IosAuxiliaryLayout.HALF_WIDTH_NUMBER_ROWS[0], startSlot = 0f),
            rowSpec(IosAuxiliaryLayout.HALF_WIDTH_NUMBER_ROWS[1], startSlot = 0f),
            auxiliaryThirdRowSpec(IosAuxiliaryLayout.HALF_WIDTH_NUMBER_ROWS[2])
        )
        Mode.HALF_WIDTH_SYMBOL -> listOf(
            rowSpec(IosAuxiliaryLayout.HALF_WIDTH_SYMBOL_ROWS[0], startSlot = 0f),
            rowSpec(IosAuxiliaryLayout.HALF_WIDTH_SYMBOL_ROWS[1], startSlot = 0f),
            auxiliaryThirdRowSpec(IosAuxiliaryLayout.HALF_WIDTH_SYMBOL_ROWS[2])
        )
    }

    private fun rowSpec(labels: List<String>, startSlot: Float): KeyRowSpec =
        KeyRowSpec(labels.mapIndexed { index, label -> KeySpec(label, startSlot + index) })

    private fun englishLabels(letters: String): List<String> =
        letters.map { if (englishShifted) it.uppercaseChar().toString() else it.toString() }

    private fun auxiliaryThirdRowSpec(labels: List<String>): KeyRowSpec {
        val slots = ZhuyinDynamicLayout.evenlyFilledRow(
            keyCount = labels.size,
            columnCount = columnCountForCurrentMode()
        )
        return KeyRowSpec(labels.zip(slots) { label, geometry ->
            KeySpec(label, geometry.slot, geometry.span)
        })
    }

    private fun toneRowSpec(labels: List<String>): KeyRowSpec {
        val slots = ZhuyinDynamicLayout.evenlyFilledRow(labels.size)
        return KeyRowSpec(labels.zip(slots) { label, geometry ->
            KeySpec(label, geometry.slot, geometry.span)
        })
    }

    private fun columnCountForCurrentMode(): Int = when (mode) {
        Mode.ZHUYIN -> ZhuyinDynamicLayout.COLUMN_COUNT
        Mode.ENGLISH,
        Mode.EMOJI,
        Mode.NUMBER,
        Mode.SYMBOL,
        Mode.HALF_WIDTH_NUMBER,
        Mode.HALF_WIDTH_SYMBOL -> 10
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
        val bgTop = keyboardContentTop
        val bgBottom = if (controlKeys.isNotEmpty()) controlKeys.last().rect.bottom
                        else if (zhuyinKeys.isNotEmpty()) zhuyinKeys.last().rect.bottom
                        else height.toFloat()
        if (bgBottom > bgTop) {
            paintRoundRect.color = palette.background
            canvas.drawRect(0f, bgTop.coerceAtLeast(0f), width.toFloat(),
                height.toFloat(), paintRoundRect)
            // 頂部分隔線
            if (bgTop > 0f) {
                paintRoundRect.color = palette.topDivider
                canvas.drawRect(0f, bgTop, width.toFloat(), bgTop + 1f, paintRoundRect)
            }
        }

        paintToolbar.color = colorControlText
        paintToolbar.textSize = dp(15f) * layoutHeightScale
        paintToolbar.typeface = Typeface.DEFAULT
        for (tool in toolKeys) {
            drawRoundRect(canvas, tool.rect, palette.candidateBar, cornerRadius)
            val cy = tool.rect.centerY() - (paintToolbar.ascent() + paintToolbar.descent()) / 2
            canvas.drawText(tool.label, tool.rect.centerX(), cy, paintToolbar)
        }

        // Candidate bar
        if (candidateBarRect.height() > 0) {
            drawRect(canvas, candidateBarRect, palette.candidateBar)
            if (composingActive && candidates.isEmpty()) {
                val cy = candidateBarRect.centerY() - (paintToolbar.ascent() + paintToolbar.descent()) / 2
                canvas.drawText("無候選字", candidateBarRect.centerX(), cy, paintToolbar)
            }
            canvas.save()
            canvas.clipRect(candidateBarRect)
            for (cell in candidateCells) {
                if (!RectF.intersects(candidateBarRect, cell.rect)) continue
                val bg = if (cell.candidateIndex == selectedCandidateIndex) {
                    palette.candidateSelected
                } else {
                    palette.candidateBackground
                }
                drawRoundRect(canvas, cell.rect, bg, dp(metrics.candidateCornerRadius))
                paintCandidateText.typeface = KeyboardFont.choose(candidates[cell.candidateIndex], customTypeface, Typeface.DEFAULT)
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
                    palette.candidateToggle,
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
            labelPaint.typeface = KeyboardFont.choose(k.label, customTypeface,
                if (k.label.all { it in 'ㄅ'..'ㄩ' || it in TONE_CHARS }) bopomofoTypeface else Typeface.DEFAULT)
            val cy = k.rect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
            if (k.label.isNotEmpty()) {
                canvas.drawText(k.label, k.rect.centerX(), cy, labelPaint)
            }

        }

        // 控制列
        for ((i, k) in controlKeys.withIndex()) {
            paintControlText.typeface = KeyboardFont.choose(k.label, customTypeface, Typeface.DEFAULT)
            val isPressed = (i == pressedControlIdx)
            when (k.action) {
                ControlAction.SPACE -> {
                    val c = if (isPressed) palette.spaceBackgroundPressed else colorSpaceBg
                    drawRoundRect(canvas, k.rect, c, cornerRadius)
                    paintControlText.color = colorSpaceText
                    val cy = k.rect.centerY() - (paintControlText.ascent() + paintControlText.descent()) / 2
                    val label = if (spaceCursorGesture.active) "←  →" else k.label
                    canvas.drawText(label, k.rect.centerX(), cy, paintControlText)
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
                    val cy = k.rect.centerY() -
                        (paintControlText.ascent() + paintControlText.descent()) / 2
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

    // ============================================================
    // 觸控
    // 每個手指各自記住按下的鍵。手指抬起時，短距離滾動仍算原鍵；
    // 明顯滑到另一鍵才改送那一鍵；滑出鍵盤則取消。
    // ============================================================
    private class Contact(
        val pointerId: Int,
        val downX: Float,
        val downY: Float,
        var target: TouchTarget?,
        var trackingSpace: Boolean,
        var hapticArmed: Boolean,
        var backspaceEngaged: Boolean,
        var resolved: Boolean = false,
        var candidateDragging: Boolean,
        var candidateSwipeDirection: Int,
        val candidateDragStartX: Float,
        val candidateDragStartY: Float,
        val candidateDragStartOffset: Float
    )

    private val contacts = LinkedHashMap<Int, Contact>()
    private var committingContact: Contact? = null
    private val spaceCursorGesture = SpaceCursorGesture(
        holdMillis = ViewConfiguration.getLongPressTimeout().toLong(),
        touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat(),
        stepPixels = dp(12f),
        schedule = { task, delay -> postDelayed(task, delay) },
        unschedule = { task -> removeCallbacks(task) },
        activate = { onCursorGestureStart?.invoke() == true },
        moveCursor = { steps -> onCursorMove?.invoke(steps) },
        changed = { invalidate() }
    )

    fun cancelCursorGesture() {
        if (spaceCursorGesture.ownsTouch) {
            contacts.values.filter { it.trackingSpace }.forEach { contact ->
                contact.trackingSpace = false
                contact.resolved = true
                contact.target = null
            }
            pressedControlIdx = -1
        }
        spaceCursorGesture.cancel()
    }

    private fun cancelAllContacts() {
        val releaseBackspace = contacts.values.any { it.backspaceEngaged }
        contacts.clear()
        committingContact = null
        if (releaseBackspace) onBackspaceRelease?.invoke()
        spaceCursorGesture.cancel()
        pressedKeyIdx = -1
        pressedControlIdx = -1
        pressedToneIdx = -1
    }

    private sealed class TouchTarget {
        data class Tool(val label: String) : TouchTarget()
        data class Candidate(val idx: Int) : TouchTarget()
        object MorePage : TouchTarget()
        object CandidatePanel : TouchTarget()
        data class ZhuyinKey(val idx: Int, val label: String) : TouchTarget()
        data class ControlKey(val idx: Int, val action: ControlAction) : TouchTarget()
        data class ToneKey(val idx: Int) : TouchTarget()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.actionMasked == MotionEvent.ACTION_DOWN) cancelAllContacts()
                val index = event.actionIndex
                return beginContact(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                if (contacts.isEmpty()) return false
                for (index in 0 until event.pointerCount) {
                    moveContact(event.getPointerId(index), event.getX(index), event.getY(index))
                }
                return true
            }
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                endContact(event.getPointerId(index), event.getX(index), event.getY(index))
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelAllContacts()
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun beginContact(pointerId: Int, x: Float, y: Float): Boolean {
        val landed = findTarget(x, y)
        val contact = landed?.let { target ->
            Contact(
                pointerId = pointerId,
                downX = x,
                downY = y,
                target = target,
                trackingSpace = target is TouchTarget.ControlKey && target.action == ControlAction.SPACE,
                hapticArmed = target !is TouchTarget.CandidatePanel,
                backspaceEngaged = false,
                candidateDragging = false,
                candidateSwipeDirection = 0,
                candidateDragStartX = x,
                candidateDragStartY = y,
                candidateDragStartOffset = candidateScrollOffset
            ).also { created ->
                contacts[pointerId] = created
                // Feedback before dictionary, editor IPC, or backspace repeat work.
                if (created.hapticArmed) {
                    onKeyFeedback?.invoke()
                    playKeyClick(target)
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                applyHighlight(target)
            }
        }
        val previousSpace = contacts.values.firstOrNull { existing ->
            existing.pointerId != pointerId && existing.trackingSpace && !existing.resolved &&
                spaceCursorGesture.ownsTouch
        }
        if (previousSpace != null) {
            val tap = spaceCursorGesture.release()
            previousSpace.trackingSpace = false
            previousSpace.resolved = true
            previousSpace.target = null
            if (tap) {
                commitContact(previousSpace) {
                    onSpace?.invoke()
                    performClick()
                }
            }
        }
        contact?.let { created ->
            if (created.trackingSpace) spaceCursorGesture.press(x, y)
            if (isBackspaceTarget(created.target)) {
                created.backspaceEngaged = true
                commitContact(created) { onBackspace?.invoke() }
            }
        }
        invalidate()
        return contacts.isNotEmpty()
    }

    private fun moveContact(pointerId: Int, x: Float, y: Float) {
        val contact = contacts[pointerId] ?: return
        if (contact.resolved) return
        if (contact.trackingSpace && spaceCursorGesture.ownsTouch) {
            spaceCursorGesture.move(x, y)
            return
        }
        if (isCandidateContact(contact) && !candidateExpanded) {
            val dragX = x - contact.candidateDragStartX
            val dragY = y - contact.candidateDragStartY
            if (contact.candidateDragging ||
                (abs(dragX) > candidateTouchSlop && abs(dragX) > abs(dragY))
            ) {
                contact.candidateDragging = true
                contact.candidateSwipeDirection = if (dragX < 0f) 1 else -1
                invalidate()
                return
            }
        }
        if (isCandidateContact(contact) && candidateExpanded && candidateBarRect.contains(x, y)) {
            val dragDistance = y - contact.candidateDragStartY
            if (contact.candidateDragging || abs(dragDistance) > candidateTouchSlop) {
                contact.candidateDragging = true
                candidateScrollOffset = CandidatePanelBehavior.clampScroll(
                    contact.candidateDragStartOffset - dragDistance,
                    candidateContentHeight,
                    candidateBarRect.height()
                )
                layoutCandidateCells()
                invalidate()
                return
            }
        }
        val preview = previewTarget(contact, x, y)
        if (contact.backspaceEngaged && !isBackspaceTarget(preview)) {
            onBackspaceRelease?.invoke()
            contact.backspaceEngaged = false
        }
        applyHighlight(preview)
    }

    private fun endContact(pointerId: Int, x: Float, y: Float) {
        val contact = contacts.remove(pointerId) ?: return
        try {
            if (contact.resolved) return
            if (contact.trackingSpace && spaceCursorGesture.ownsTouch) {
                spaceCursorGesture.move(x, y)
                val tap = spaceCursorGesture.release()
                if (tap && releaseDecision(contact, x, y) == KeyboardTouchPolicy.KeyRelease.ORIGINAL) {
                    contact.target?.let { fire(contact, it) }
                }
                return
            }
            if (contact.candidateDragging && contact.candidateSwipeDirection != 0) {
                commitContact(contact) {
                    onCandidatePageSwipe?.invoke(contact.candidateSwipeDirection)
                }
                return
            }
            if (contact.candidateDragging) return
            when (releaseDecision(contact, x, y)) {
                KeyboardTouchPolicy.KeyRelease.ORIGINAL -> contact.target?.let { fire(contact, it) }
                KeyboardTouchPolicy.KeyRelease.UNDER_FINGER -> {
                    findTarget(x, y)
                        ?.takeIf { slideCompatible(contact.target, it) }
                        ?.let { fire(contact, it) }
                }
                KeyboardTouchPolicy.KeyRelease.CANCEL -> Unit
            }
        } finally {
            if (contact.backspaceEngaged) {
                onBackspaceRelease?.invoke()
                contact.backspaceEngaged = false
            }
            contact.hapticArmed = false
            if (committingContact === contact) committingContact = null
            refreshHighlights()
            invalidate()
        }
    }

    private fun fire(contact: Contact, target: TouchTarget) {
        commitContact(contact) {
            handleTarget(target)
            if (target !is TouchTarget.CandidatePanel) performClick()
        }
    }

    private fun commitContact(contact: Contact, block: () -> Unit) {
        committingContact = contact
        try {
            block()
        } finally {
            contact.hapticArmed = false
            if (committingContact === contact) committingContact = null
        }
    }

    /** True when finger-down already played the haptic for the key this commit is finishing. */
    internal fun consumeCommittingHaptic(): Boolean {
        val contact = committingContact ?: return false
        if (!contact.hapticArmed) return false
        contact.hapticArmed = false
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun findTarget(x: Float, y: Float): TouchTarget? {
        toolKeys.firstOrNull { it.rect.contains(x, y) }?.let { return TouchTarget.Tool(it.label) }
        if (candidateBarRect.height() > 0 && candidateBarRect.contains(x, y)) {
            if (candidateToggleRect.contains(x, y)) {
                return TouchTarget.MorePage
            }
            candidateCells.firstOrNull { cell ->
                RectF.intersects(candidateBarRect, cell.rect) && cell.rect.contains(x, y)
            }?.let { return TouchTarget.Candidate(it.candidateIndex) }
            return TouchTarget.CandidatePanel
        }
        // A touch inside a drawn control stays there. Aim correction applies to the
        // gaps and key grid, where the contact centroid sits below the fingertip.
        for ((index, key) in controlKeys.withIndex()) {
            if (key.rect.contains(x, y)) return TouchTarget.ControlKey(index, key.action)
        }
        val biasedY = y - dp(KeyboardTouchPolicy.TOUCH_AIM_OFFSET_UP_DP)
        val controlIndex = KeyboardHitTesting.nearestExpandedIndex(
            x = x,
            y = biasedY,
            boxes = controlHitBoxes,
            horizontalSlop = 0f,
            verticalSlop = 0f
        )
        if (controlIndex >= 0) {
            return TouchTarget.ControlKey(controlIndex, controlKeys[controlIndex].action)
        }
        val keyFloor = keyHitBoxes.minOfOrNull { it.top } ?: biasedY
        val keyIndex = KeyboardHitTesting.nearestExpandedIndex(
            x = x,
            y = biasedY.coerceAtLeast(keyFloor),
            boxes = keyHitBoxes,
            horizontalSlop = 0f,
            verticalSlop = 0f
        )
        if (keyIndex >= 0) {
            val key = zhuyinKeys[keyIndex]
            return TouchTarget.ZhuyinKey(keyIndex, key.label)
        }
        return null
    }

    private fun releaseDecision(contact: Contact, x: Float, y: Float): KeyboardTouchPolicy.KeyRelease {
        val under = findTarget(x, y)
        return KeyboardTouchPolicy.keyRelease(
            downX = contact.downX,
            downY = contact.downY,
            upX = x,
            upY = y,
            hadTarget = contact.target != null,
            stillOnOriginal = sameTarget(contact.target, under),
            fingerOnTypingKey = !contact.trackingSpace && slideCompatible(contact.target, under),
            holdRadiusPx = dp(KeyboardTouchPolicy.TAP_HOLD_RADIUS_DP)
        )
    }

    private fun previewTarget(contact: Contact, x: Float, y: Float): TouchTarget? =
        when (releaseDecision(contact, x, y)) {
            KeyboardTouchPolicy.KeyRelease.ORIGINAL -> contact.target
            KeyboardTouchPolicy.KeyRelease.UNDER_FINGER -> findTarget(x, y)
            KeyboardTouchPolicy.KeyRelease.CANCEL -> null
        }

    private fun sameTarget(down: TouchTarget?, up: TouchTarget?): Boolean = when {
        down is TouchTarget.ZhuyinKey && up is TouchTarget.ZhuyinKey -> down.label == up.label
        down is TouchTarget.ControlKey && up is TouchTarget.ControlKey -> down.action == up.action
        down is TouchTarget.Tool && up is TouchTarget.Tool -> down.label == up.label
        down is TouchTarget.Candidate && up is TouchTarget.Candidate -> down.idx == up.idx
        down is TouchTarget.MorePage && up is TouchTarget.MorePage -> true
        down is TouchTarget.CandidatePanel && up is TouchTarget.CandidatePanel -> true
        down is TouchTarget.ToneKey && up is TouchTarget.ToneKey -> down.idx == up.idx
        else -> false
    }

    private fun slideCompatible(down: TouchTarget?, up: TouchTarget?): Boolean {
        if (up == null || isBackspaceTarget(up) && !isBackspaceTarget(down)) return false
        return when (down) {
            is TouchTarget.ZhuyinKey, is TouchTarget.ControlKey ->
                up is TouchTarget.ZhuyinKey || up is TouchTarget.ControlKey
            is TouchTarget.Tool -> up is TouchTarget.Tool
            is TouchTarget.Candidate -> up is TouchTarget.Candidate
            is TouchTarget.MorePage -> up is TouchTarget.MorePage
            else -> false
        }
    }

    private fun isCandidateContact(contact: Contact): Boolean =
        contact.target is TouchTarget.Candidate || contact.target is TouchTarget.CandidatePanel

    private fun applyHighlight(target: TouchTarget?) {
        val newKey = (target as? TouchTarget.ZhuyinKey)?.idx ?: -1
        val newControl = (target as? TouchTarget.ControlKey)?.idx ?: -1
        val newTone = (target as? TouchTarget.ToneKey)?.idx ?: -1
        if (newKey != pressedKeyIdx || newControl != pressedControlIdx || newTone != pressedToneIdx) {
            pressedKeyIdx = newKey
            pressedControlIdx = newControl
            pressedToneIdx = newTone
            invalidate()
        }
    }

    private fun refreshHighlights() {
        applyHighlight(contacts.values.lastOrNull { !it.resolved }?.target)
    }

    private fun playKeyClick(target: TouchTarget) {
        val effect = when {
            isBackspaceTarget(target) -> AudioManager.FX_KEYPRESS_DELETE
            target is TouchTarget.ControlKey && target.action == ControlAction.SPACE ->
                AudioManager.FX_KEYPRESS_SPACEBAR
            target is TouchTarget.ControlKey && target.action == ControlAction.RETURN ->
                AudioManager.FX_KEYPRESS_RETURN
            else -> AudioManager.FX_KEYPRESS_STANDARD
        }
        try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            audio.playSoundEffect(effect)
        } catch (_: RuntimeException) {
        }
    }

    private fun handleTarget(t: TouchTarget) {
        when (t) {
            is TouchTarget.Tool -> when (t.label) {
                "全形", "半形" -> onWidthToggle?.invoke()
                "😊", "返回" -> onEmoji?.invoke()
                "剪貼簿" -> onPaste?.invoke()
                "設定" -> onSettings?.invoke()
                "編輯" -> { editTools = true; refresh() }
                "完成" -> { editTools = false; refresh() }
                in EDIT_ACTIONS -> onEditAction?.invoke(t.label)
            }
            is TouchTarget.Candidate -> {
                if (t.idx in candidates.indices) onCandidatePress?.invoke(t.idx)
            }
            is TouchTarget.MorePage -> onCandidateExpansionToggle?.invoke()
            is TouchTarget.CandidatePanel -> {}
            is TouchTarget.ControlKey -> {
                when (t.action) {
                    ControlAction.SPACE -> onSpace?.invoke()
                    ControlAction.RETURN -> onReturn?.invoke()
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
                        onCandidateConfirm?.invoke()
                    }
                }
            }
            is TouchTarget.ZhuyinKey -> {
                val key = zhuyinKeys.getOrNull(t.idx)?.takeIf { it.label == t.label }
                    ?: zhuyinKeys.firstOrNull { it.label == t.label }
                val label = key?.label ?: t.label
                if (label.isEmpty() || label == "⌫") return
                if (mode == Mode.ZHUYIN) {
                    when {
                        key?.isTone == true || label in ZhuyinDynamicLayout.TONES ->
                            onToneSelected?.invoke(label)
                        else -> onKeyPress?.invoke(label)
                    }
                } else {
                    when (label) {
                        "⇧" -> {
                            englishShifted = !englishShifted
                            refresh()
                        }
                        "#+=" -> onSymbolMode?.invoke()
                        "123" -> onNumberMode?.invoke()
                        else -> {
                            onSymbolChar?.invoke(label)
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
        is TouchTarget.ControlKey -> target.action == ControlAction.BACKSPACE
        is TouchTarget.ZhuyinKey -> target.label == "⌫"
        else -> false
    }

    private fun dp(px: Float): Float = px * resources.displayMetrics.density
    private fun dp(px: Int): Float = px * resources.displayMetrics.density

    companion object {
        private val EDIT_ACTIONS = setOf("全選", "複製", "剪下", "貼上", "◀", "▶")
        private val EDIT_TOOL_LABELS = listOf("完成") + EDIT_ACTIONS.toList()
    }
}
