package com.yuyan.imemodule.expression.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
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
    private val disabledSpacer: View
    private val content: View
    private val assetList: RecyclerView
    private val emojiPicker: EmojiCombinationPicker
    private val adapter = ExpressionAssetAdapter { onAssetClick?.invoke(it) }

    var onDismiss: (() -> Unit)? = null
    var onAiStickerEnabledChange: ((Boolean) -> Unit)? = null
    var onAnimationPreviewChange: ((Boolean) -> Unit)? = null
    var onClearCache: (() -> Unit)? = null
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
        disabledSpacer = findViewById(R.id.expression_disabled_spacer)
        closeButton.setOnClickListener {
            onAiStickerEnabledChange?.invoke(false) ?: onDismiss?.invoke()
        }
        enableButton.setOnClickListener { onAiStickerEnabledChange?.invoke(true) }
        moreButton.setOnClickListener { showSettingsMenu() }
        assetList = findViewById<RecyclerView>(R.id.expression_asset_list).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = this@ExpressionPanel.adapter
        }
        emojiPicker = findViewById(R.id.expression_emoji_picker)
        recommendedTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.RECOMMENDED) }
        templatesTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.AI_SYNTHESIS) }
        emojiTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.EMOJI_SYNTHESIS) }
    }

    fun render(state: ExpressionPanelState, catalog: ExpressionCatalog) {
        visibility = if (state.isVisible) View.VISIBLE else View.GONE
        val controlsVisibility = if (state.aiStickerEnabled) View.VISIBLE else View.GONE
        recommendedTab.visibility = controlsVisibility
        templatesTab.visibility = controlsVisibility
        emojiTab.visibility = controlsVisibility
        moreButton.visibility = controlsVisibility
        closeButton.visibility = controlsVisibility
        disabledSpacer.visibility = if (state.aiStickerEnabled) View.GONE else View.VISIBLE
        enableButton.visibility = if (state.aiStickerEnabled) View.GONE else View.VISIBLE
        recommendedTab.isSelected = state.selectedTab == ExpressionPanelTab.RECOMMENDED
        templatesTab.isSelected = state.selectedTab == ExpressionPanelTab.AI_SYNTHESIS
        emojiTab.isSelected = state.selectedTab == ExpressionPanelTab.EMOJI_SYNTHESIS
        val expanded = state.presentation == ExpressionPanelPresentation.EXPANDED
        content.layoutParams = content.layoutParams.apply {
            height = dp(if (expanded) EXPANDED_CONTENT_HEIGHT_DP else COMPACT_CONTENT_HEIGHT_DP)
        }
        content.visibility = if (state.isContentVisible) View.VISIBLE else View.GONE
        assetList.layoutManager = if (expanded) {
            GridLayoutManager(context, EXPANDED_SPAN_COUNT)
        } else {
            LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
        }
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
        const val COMPACT_CONTENT_HEIGHT_DP = 116
        const val EXPANDED_CONTENT_HEIGHT_DP = 300
        const val EXPANDED_SPAN_COUNT = 3
        const val MENU_AI_STICKER = 1
        const val MENU_ANIMATION = 2
        const val MENU_CLEAR_CACHE = 3
        var animationPreviewEnabled = false
    }
}
