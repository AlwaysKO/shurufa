package com.yuyan.imemodule.expression.ui

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.yuyan.imemodule.R
import com.yuyan.imemodule.expression.ExpressionCatalog
import com.yuyan.imemodule.expression.ExpressionPanelState
import com.yuyan.imemodule.expression.ExpressionPanelTab
import com.yuyan.imemodule.expression.model.ExpressionAsset

class ExpressionPanel @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {
    private val recommendedTab: TextView
    private val templatesTab: TextView
    private val emojiTab: TextView
    private val assetList: RecyclerView
    private val emojiPicker: EmojiCombinationPicker
    private val adapter = ExpressionAssetAdapter { onAssetClick?.invoke(it) }

    var onDismiss: (() -> Unit)? = null
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
        findViewById<ImageButton>(R.id.expression_close).setOnClickListener { onDismiss?.invoke() }
        assetList = findViewById<RecyclerView>(R.id.expression_asset_list).apply {
            layoutManager = LinearLayoutManager(context, RecyclerView.HORIZONTAL, false)
            adapter = this@ExpressionPanel.adapter
        }
        emojiPicker = findViewById(R.id.expression_emoji_picker)
        recommendedTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.RECOMMENDED) }
        templatesTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.TEMPLATES) }
        emojiTab.setOnClickListener { onTabSelected?.invoke(ExpressionPanelTab.EMOJI) }
    }

    fun render(state: ExpressionPanelState, catalog: ExpressionCatalog) {
        visibility = if (state.isVisible) View.VISIBLE else View.GONE
        recommendedTab.isSelected = state.selectedTab == ExpressionPanelTab.RECOMMENDED
        templatesTab.isSelected = state.selectedTab == ExpressionPanelTab.TEMPLATES
        emojiTab.isSelected = state.selectedTab == ExpressionPanelTab.EMOJI
        val showingEmoji = state.selectedTab == ExpressionPanelTab.EMOJI
        assetList.visibility = if (showingEmoji) View.GONE else View.VISIBLE
        emojiPicker.visibility = if (showingEmoji) View.VISIBLE else View.GONE
        if (showingEmoji) emojiPicker.render(catalog)
        adapter.submitList(
            when (state.selectedTab) {
                ExpressionPanelTab.RECOMMENDED -> state.results
                ExpressionPanelTab.TEMPLATES -> catalog.document.templates
                ExpressionPanelTab.EMOJI -> emptyList()
            },
        )
    }

    fun resetEmojiSelection() = emojiPicker.reset()
}
