package com.yuyan.imemodule.expression.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.data.theme.ThemeManager
import com.yuyan.imemodule.expression.ExpressionCatalog
import com.yuyan.imemodule.expression.ExpressionPanelState
import com.yuyan.imemodule.expression.ExpressionPanelTab
import com.yuyan.imemodule.expression.ExpressionPanelPresentation
import com.yuyan.imemodule.expression.model.ExpressionAsset

class ExpressionPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val recommendedTab: TextView
    private val templatesTab: TextView
    private val emojiTab: TextView
    private val moreButton: ImageButton
    private val closeButton: ImageButton
    private val enableButton: TextView
    private val recommendationSection: View
    private val toolRow: View
    private val tabBar: View
    private val actions: View
    private val content: View
    private val assetList: RecyclerView
    private val emojiPicker: EmojiCombinationPicker
    private val adapter = ExpressionAssetAdapter(
        onClick = { onAssetClick?.invoke(it) },
        onLongPress = { requestExpand(withHaptic = true) },
    )
    private var expandedContentHeightPx = 0
    private var layoutMetrics: ExpressionLayoutMetrics? = null
    private var isExpanded = false
    private var aiStickerEnabled = true
    private var recommendationVisible = false
    private var canExpand = false
    private var gestureDownX = 0f
    private var gestureDownY = 0f
    private var gestureDownTime = 0L
    private var gesturePointerId = MotionEvent.INVALID_POINTER_ID
    private var gestureCancelled = false
    private var availableLayoutHeightPx = Int.MAX_VALUE
    private var reservedKeyboardHeightPx = 0

    var onDismiss: (() -> Unit)? = null
    var onAiStickerEnabledChange: ((Boolean) -> Unit)? = null
    var onRecommendationVisibilityChange: ((Boolean) -> Unit)? = null
    var onAnimationPreviewChange: ((Boolean) -> Unit)? = null
    var onClearCache: (() -> Unit)? = null
    var onExpandRequested: (() -> Unit)? = null
    var onTabSelected: ((ExpressionPanelTab) -> Unit)? = null
    var onAssetClick: ((ExpressionAsset) -> Unit)? = null
    var onEmojiCombinationMissing: ((com.yuyan.imemodule.expression.model.EmojiCombination, (java.io.File?) -> Unit) -> Unit)?
        get() = emojiPicker.onCombinationMissing
        set(value) {
            emojiPicker.onCombinationMissing = value
        }
    var onEmojiCombinationClick: ((com.yuyan.imemodule.expression.model.EmojiCombination, java.io.File?) -> Unit)?
        get() = emojiPicker.onCombinationClick
        set(value) {
            emojiPicker.onCombinationClick = value
        }

    init {
        orientation = VERTICAL
        LayoutInflater.from(context).inflate(R.layout.sdk_expression_panel, this, true)
        recommendedTab = findViewById(R.id.expression_tab_recommended)
        templatesTab = findViewById(R.id.expression_tab_templates)
        emojiTab = findViewById(R.id.expression_tab_emoji)
        content = findViewById(R.id.expression_content)
        moreButton = findViewById(R.id.expression_more)
        closeButton = findViewById(R.id.expression_close)
        enableButton = findViewById(R.id.expression_enable)
        recommendationSection = findViewById(R.id.expression_recommendation_section)
        toolRow = findViewById(R.id.expression_tool_row)
        tabBar = findViewById(R.id.expression_tab_bar)
        actions = findViewById(R.id.expression_actions)
        closeButton.setOnClickListener {
            when {
                onRecommendationVisibilityChange != null -> onRecommendationVisibilityChange?.invoke(false)
                onAiStickerEnabledChange != null -> onAiStickerEnabledChange?.invoke(false)
                else -> onDismiss?.invoke()
            }
        }
        enableButton.setOnClickListener {
            when {
                !aiStickerEnabled -> onAiStickerEnabledChange?.invoke(true)
                recommendationVisible -> onRecommendationVisibilityChange?.invoke(false)
                !recommendationVisible -> onRecommendationVisibilityChange?.invoke(true)
            }
        }
        moreButton.setOnClickListener { showSettingsMenu() }
        assetList = findViewById<RecyclerView>(R.id.expression_asset_list).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = this@ExpressionPanel.adapter
            setOnLongClickListener {
                if (canExpand) requestExpand(withHaptic = true)
                canExpand
            }
            addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
                override fun onInterceptTouchEvent(rv: RecyclerView, event: MotionEvent): Boolean {
                    handleExpandGesture(event)
                    return false
                }
            })
        }
        emojiPicker = findViewById(R.id.expression_emoji_picker)
        recommendedTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.RECOMMENDED) }
        templatesTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.AI_SYNTHESIS) }
        emojiTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.EMOJI_SYNTHESIS) }
    }

    fun render(state: ExpressionPanelState, catalog: ExpressionCatalog) {
        aiStickerEnabled = state.aiStickerEnabled
        recommendationVisible = state.isRecommendationVisible
        canExpand = state.results.isNotEmpty() && recommendationVisible
        isExpanded = state.presentation == ExpressionPanelPresentation.EXPANDED
        applyLayoutMetrics()
        visibility = View.VISIBLE
        recommendationSection.visibility = if (state.isRecommendationVisible) View.VISIBLE else View.GONE
        toolRow.visibility = View.VISIBLE
        enableButton.visibility = View.VISIBLE
        enableButton.contentDescription = context.getString(
            when {
                state.isRecommendationVisible -> R.string.expression_tool_hide_recommendations
                state.results.isNotEmpty() -> R.string.expression_tool_restore_recommendations
                else -> R.string.ai_sticker_search
            },
        )
        recommendedTab.isSelected = state.selectedTab == ExpressionPanelTab.RECOMMENDED
        templatesTab.isSelected = state.selectedTab == ExpressionPanelTab.AI_SYNTHESIS
        emojiTab.isSelected = state.selectedTab == ExpressionPanelTab.EMOJI_SYNTHESIS
        applyTheme()
        val expanded = isExpanded
        content.layoutParams = content.layoutParams.apply {
            height = if (expanded && expandedContentHeightPx > 0) {
                expandedContentHeightPx
            } else {
                if (expanded) dp(EXPANDED_CONTENT_HEIGHT_DP) else requireNotNull(layoutMetrics).contentHeightPx
            }
        }
        content.visibility = View.VISIBLE
        ensureAssetLayoutManager(expanded)
        adapter.setExpanded(expanded)
        val showingEmoji = state.selectedTab == ExpressionPanelTab.EMOJI_SYNTHESIS
        assetList.visibility = if (showingEmoji) View.GONE else View.VISIBLE
        emojiPicker.visibility = if (showingEmoji) View.VISIBLE else View.GONE
        if (showingEmoji) emojiPicker.render(catalog)
        adapter.submitList(
            when (state.selectedTab) {
                ExpressionPanelTab.RECOMMENDED -> state.results
                ExpressionPanelTab.AI_SYNTHESIS -> catalog.document.templates.filter {
                    it.type == "synthesis-template"
                }
                ExpressionPanelTab.EMOJI_SYNTHESIS -> emptyList()
            },
        )
    }

    fun resetEmojiSelection() = emojiPicker.reset()

    fun clearCallbacks() {
        onDismiss = null
        onAiStickerEnabledChange = null
        onRecommendationVisibilityChange = null
        onAnimationPreviewChange = null
        onClearCache = null
        onExpandRequested = null
        onTabSelected = null
        onAssetClick = null
        onEmojiCombinationMissing = null
        onEmojiCombinationClick = null
    }

    fun setExpandedContentHeight(heightPx: Int) {
        expandedContentHeightPx = heightPx.coerceAtLeast(0)
    }

    /** 约束紧凑面板，不占用候选栏和键盘已经保留的高度。 */
    fun setAvailableLayoutHeight(availableHeightPx: Int, reservedKeyboardHeightPx: Int) {
        require(availableHeightPx >= 0) { "available height must not be negative" }
        require(reservedKeyboardHeightPx >= 0) { "reserved keyboard height must not be negative" }
        if (availableLayoutHeightPx == availableHeightPx &&
            this.reservedKeyboardHeightPx == reservedKeyboardHeightPx
        ) return
        availableLayoutHeightPx = availableHeightPx
        this.reservedKeyboardHeightPx = reservedKeyboardHeightPx
        applyLayoutMetrics()
        requestLayout()
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && width != oldWidth) applyLayoutMetrics(width)
    }

    private fun applyLayoutMetrics(measuredWidth: Int = width) {
        val availableWidth = measuredWidth.takeIf { it > 0 }
            ?: resources.displayMetrics.widthPixels
        val metrics = ExpressionLayoutMetrics.calculate(
            widthPx = availableWidth,
            density = resources.displayMetrics.density,
            landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE,
            availableHeightPx = availableLayoutHeightPx,
            reservedKeyboardHeightPx = reservedKeyboardHeightPx,
        )
        layoutMetrics = metrics
        tabBar.layoutParams = tabBar.layoutParams.apply { height = metrics.tabRowHeightPx }
        tabBar.visibility = if (metrics.tabRowHeightPx >= dp(MINIMUM_TOUCH_TARGET_DP)) {
            View.VISIBLE
        } else {
            View.GONE
        }
        listOf(recommendedTab, templatesTab, emojiTab).forEach { tab ->
            tab.minimumWidth = dp(MINIMUM_TOUCH_TARGET_DP)
            tab.minimumHeight = dp(MINIMUM_TOUCH_TARGET_DP)
        }
        toolRow.layoutParams = toolRow.layoutParams.apply { height = metrics.toolRowHeightPx }
        enableButton.minimumWidth = dp(MINIMUM_TOUCH_TARGET_DP)
        enableButton.minimumHeight = metrics.toolRowHeightPx
        enableButton.layoutParams = enableButton.layoutParams.apply { height = metrics.toolRowHeightPx }
        layoutParams?.let { params ->
            params.height = when {
                isExpanded -> ViewGroup.LayoutParams.WRAP_CONTENT
                recommendationVisible -> metrics.compactPanelHeightPx
                else -> metrics.toolRowHeightPx
            }
            layoutParams = params
        }
        actions.layoutParams = actions.layoutParams.apply {
            width = metrics.actionWidthPx
            height = metrics.actionHeightPx
        }
        if (!isExpanded) {
            content.layoutParams = content.layoutParams.apply { height = metrics.contentHeightPx }
        }
        assetList.setPadding(
            metrics.horizontalPaddingPx,
            assetList.paddingTop,
            metrics.horizontalPaddingPx,
            assetList.paddingBottom,
        )
        adapter.setLayoutMetrics(metrics)
    }

    private fun ensureAssetLayoutManager(expanded: Boolean) {
        val current = assetList.layoutManager
        if (expanded) {
            if (current !is GridLayoutManager || current.spanCount != EXPANDED_SPAN_COUNT) {
                assetList.layoutManager = GridLayoutManager(context, EXPANDED_SPAN_COUNT)
            }
        } else if (current !is LinearLayoutManager || current is GridLayoutManager ||
            current.orientation != RecyclerView.HORIZONTAL
        ) {
            assetList.layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        }
    }

    private fun requestExpand(withHaptic: Boolean) {
        if (!canExpand || isExpanded) return
        if (withHaptic) performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onExpandRequested?.invoke()
    }

    private fun handleExpandGesture(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gesturePointerId = event.getPointerId(event.actionIndex)
                gestureDownX = event.getX(event.actionIndex)
                gestureDownY = event.getY(event.actionIndex)
                gestureDownTime = event.eventTime
                gestureCancelled = event.pointerCount != 1
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                gestureCancelled = true
            }
            MotionEvent.ACTION_UP -> {
                val pointerIndex = event.findPointerIndex(gesturePointerId)
                if (!gestureCancelled && pointerIndex >= 0 && gestureDownTime > 0L) {
                    val dx = event.getX(pointerIndex) - gestureDownX
                    val dy = event.getY(pointerIndex) - gestureDownY
                    val elapsed = (event.eventTime - gestureDownTime).coerceAtLeast(1L)
                    val threshold = dp(SWIPE_EXPAND_THRESHOLD_DP).toFloat()
                    val upwardSpeed = -dy * 1000f / elapsed
                    if (dy <= -threshold && kotlin.math.abs(dy) > kotlin.math.abs(dx) * 1.25f &&
                        upwardSpeed >= SWIPE_EXPAND_MIN_SPEED_PX_PER_SECOND
                    ) {
                        requestExpand(withHaptic = false)
                    }
                }
                resetExpandGesture()
            }
            MotionEvent.ACTION_CANCEL -> resetExpandGesture()
        }
    }

    private fun resetExpandGesture() {
        gesturePointerId = MotionEvent.INVALID_POINTER_ID
        gestureDownTime = 0L
        gestureCancelled = true
    }

    private fun applyTheme() {
        val theme = ThemeManager.activeTheme
        setBackgroundColor(theme.keyboardColor)
        recommendationSection.setBackgroundColor(theme.keyboardColor)
        content.setBackgroundColor(theme.keyboardColor)
        toolRow.background = ColorDrawable(theme.barColor)
        enableButton.setTextColor(theme.accentKeyBackgroundColor)
        enableButton.background = roundedBackground(theme.keyBackgroundColor, 8)
        actions.background = roundedBackground(theme.keyBackgroundColor, 16)
        listOf(moreButton, closeButton).forEach { button ->
            button.drawable?.mutate()?.setTint(theme.keyTextColor)
        }
        listOf(
            recommendedTab to recommendedTab.isSelected,
            templatesTab to templatesTab.isSelected,
            emojiTab to emojiTab.isSelected,
        ).forEach { (tab, selected) ->
            tab.setTextColor(if (selected) theme.accentKeyBackgroundColor else theme.keyTextColor)
            tab.background = tabBackground(selected, theme.accentKeyBackgroundColor)
        }
    }

    /** 主题在面板显示期间切换时，立即刷新所有可见颜色与图标。 */
    fun updateTheme() {
        applyTheme()
    }

    private fun roundedBackground(color: Int, radiusDp: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
    }

    private fun tabBackground(selected: Boolean, accent: Int): android.graphics.drawable.Drawable {
        if (!selected) return ColorDrawable(Color.TRANSPARENT)
        val metrics = layoutMetrics
        val height = metrics?.tabRowHeightPx ?: dp(36)
        return LayerDrawable(arrayOf(ColorDrawable(Color.TRANSPARENT), ColorDrawable(accent))).apply {
            setLayerInset(1, 0, (height - dp(2)).coerceAtLeast(0), 0, 0)
        }
    }

    private fun showSettingsMenu() {
        PopupMenu(context, moreButton).apply {
            menu.add(0, MENU_AI_STICKER, 0, "AI斗图开关").apply {
                isCheckable = true
                isChecked = true
            }
            menu.add(0, MENU_ANIMATION, 1, "动画预览").apply {
                isCheckable = true
                isChecked = animationPreviewEnabled
            }
            menu.add(0, MENU_CLEAR_CACHE, 2, "清理缓存")
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    MENU_AI_STICKER -> onAiStickerEnabledChange?.invoke(false)
                    MENU_ANIMATION -> {
                        animationPreviewEnabled = !animationPreviewEnabled
                        onAnimationPreviewChange?.invoke(animationPreviewEnabled)
                    }
                    MENU_CLEAR_CACHE -> onClearCache?.invoke()
                }
                true
            }
            show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val EXPANDED_CONTENT_HEIGHT_DP = 300
        const val EXPANDED_SPAN_COUNT = 3
        const val MENU_AI_STICKER = 1
        const val MENU_ANIMATION = 2
        const val MENU_CLEAR_CACHE = 3
        const val SWIPE_EXPAND_THRESHOLD_DP = 48
        const val SWIPE_EXPAND_MIN_SPEED_PX_PER_SECOND = 180f
        const val MINIMUM_TOUCH_TARGET_DP = 44
        var animationPreviewEnabled = false
    }
}
