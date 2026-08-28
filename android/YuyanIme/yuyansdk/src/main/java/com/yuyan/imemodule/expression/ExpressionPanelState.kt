package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset

enum class ExpressionPanelTab {
    RECOMMENDED,
    AI_SYNTHESIS,
    EMOJI_SYNTHESIS,
}

enum class ExpressionPanelPresentation {
    COMPACT,
    EXPANDED,
}

class ExpressionPanelState(
    aiStickerEnabled: Boolean = true,
    chatEditor: Boolean = true,
) {
    var query: String? = null
        private set
    var selectedTab: ExpressionPanelTab = ExpressionPanelTab.RECOMMENDED
        private set
    var results: List<ExpressionAsset> = emptyList()
        private set
    val isVisible: Boolean
        get() = true
    val isToolRowVisible: Boolean
        get() = true
    val isRecommendationVisible: Boolean
        get() = isContentVisible && !recommendationsHidden
    var isContentVisible: Boolean = false
        private set
    var aiStickerEnabled: Boolean = aiStickerEnabled
        private set
    var chatEditor: Boolean = chatEditor
        private set
    var presentation: ExpressionPanelPresentation = ExpressionPanelPresentation.COMPACT
        private set
    var keyboardVisible: Boolean = true
        private set

    private var recommendationsHidden = false
    private var requestId = 0L
    fun beginQuery(query: String, requestId: Long) {
        if (!chatEditor) {
            clear()
            return
        }
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "query must not be blank" }
        if (normalized != this.query) {
            selectedTab = ExpressionPanelTab.RECOMMENDED
            collapse()
        }
        this.query = normalized
        this.requestId = requestId
        results = emptyList()
        isContentVisible = false
    }

    fun acceptResponse(requestId: Long): Boolean = requestId == this.requestId

    fun applyResults(requestId: Long, results: List<ExpressionAsset>): Boolean {
        if (!chatEditor) return false
        if (!acceptResponse(requestId)) return false
        this.results = results
        isContentVisible = aiStickerEnabled && results.isNotEmpty()
        return true
    }

    fun selectTab(tab: ExpressionPanelTab) {
        if (!aiStickerEnabled) return
        selectedTab = tab
    }

    /** 临时收起结果区；与总开关不同，保留查询、结果及当前标签。 */
    fun hideRecommendations() {
        recommendationsHidden = true
        collapse()
    }

    /** 从工具行恢复同一查询的结果区。 */
    fun restoreRecommendations() {
        if (chatEditor && aiStickerEnabled && results.isNotEmpty()) {
            recommendationsHidden = false
        }
    }

    fun expand() {
        if (!chatEditor || !aiStickerEnabled || !isContentVisible) return
        presentation = ExpressionPanelPresentation.EXPANDED
        keyboardVisible = false
    }

    fun collapse() {
        presentation = ExpressionPanelPresentation.COMPACT
        keyboardVisible = true
    }

    fun setAiStickerEnabled(enabled: Boolean) {
        aiStickerEnabled = enabled
        if (!enabled) {
            clear()
        } else {
            isContentVisible = chatEditor && results.isNotEmpty()
        }
    }

    fun setChatEditor(enabled: Boolean) {
        if (chatEditor == enabled) return
        chatEditor = enabled
        clear()
    }

    fun dismiss() {
        setAiStickerEnabled(false)
    }

    fun clear() {
        query = null
        selectedTab = ExpressionPanelTab.RECOMMENDED
        results = emptyList()
        isContentVisible = false
        recommendationsHidden = false
        collapse()
        requestId += 1
    }
}
