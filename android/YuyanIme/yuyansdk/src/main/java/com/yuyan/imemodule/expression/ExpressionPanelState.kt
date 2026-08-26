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

class ExpressionPanelState(aiStickerEnabled: Boolean = true) {
    var query: String? = null
        private set
    var selectedTab: ExpressionPanelTab = ExpressionPanelTab.RECOMMENDED
        private set
    var results: List<ExpressionAsset> = emptyList()
        private set
    val isVisible: Boolean
        get() = true
    var isContentVisible: Boolean = false
        private set
    var aiStickerEnabled: Boolean = aiStickerEnabled
        private set
    var presentation: ExpressionPanelPresentation = ExpressionPanelPresentation.COMPACT
        private set

    private var requestId = 0L
    fun beginQuery(query: String, requestId: Long) {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "query must not be blank" }
        if (normalized != this.query) {
            selectedTab = ExpressionPanelTab.RECOMMENDED
            presentation = ExpressionPanelPresentation.COMPACT
        }
        this.query = normalized
        this.requestId = requestId
        results = emptyList()
        isContentVisible = false
    }

    fun acceptResponse(requestId: Long): Boolean = requestId == this.requestId

    fun applyResults(requestId: Long, results: List<ExpressionAsset>): Boolean {
        if (!acceptResponse(requestId)) return false
        this.results = results
        isContentVisible = aiStickerEnabled && results.isNotEmpty()
        return true
    }

    fun selectTab(tab: ExpressionPanelTab) {
        if (!aiStickerEnabled) return
        selectedTab = tab
        presentation = ExpressionPanelPresentation.EXPANDED
    }

    fun setAiStickerEnabled(enabled: Boolean) {
        aiStickerEnabled = enabled
        isContentVisible = enabled && results.isNotEmpty()
        if (!enabled) presentation = ExpressionPanelPresentation.COMPACT
    }

    fun dismiss() {
        setAiStickerEnabled(false)
        presentation = ExpressionPanelPresentation.COMPACT
    }

    fun clear() {
        query = null
        selectedTab = ExpressionPanelTab.RECOMMENDED
        results = emptyList()
        isContentVisible = false
        presentation = ExpressionPanelPresentation.COMPACT
        requestId += 1
    }
}
