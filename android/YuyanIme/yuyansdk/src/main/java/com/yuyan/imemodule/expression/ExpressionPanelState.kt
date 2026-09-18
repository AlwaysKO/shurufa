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
    var isPreparing: Boolean = false
    var preparationStage = com.yuyan.imemodule.expression.send.ExpressionSendStage.PREPARING

    var query: String? = null
        private set
    var selectedTab: ExpressionPanelTab = ExpressionPanelTab.RECOMMENDED
        private set
    var results: List<ExpressionAsset> = emptyList()
        private set
    val isVisible: Boolean
        get() = isRecommendationVisible
    /** 候选拼音共行内的恢复按钮；不是独立工具行。 */
    val isRecommendationActionVisible: Boolean
        get() = chatEditor && aiStickerEnabled && (manualQuery || synthesisAvailable || results.isNotEmpty())
    val isRecommendationVisible: Boolean
        get() = isContentVisible && !recommendationsHidden
    val recommendationsPaused: Boolean
        get() = recommendationsHidden
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

    private var tabChosenByUser = false
    private var synthesisAvailable = false
    private var manualQuery = false
    private var recommendationsHidden = false
    private var requestId = 0L
    fun beginQuery(query: String, requestId: Long, manual: Boolean = false) {
        if (!chatEditor) {
            clear()
            return
        }
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "query must not be blank" }
        if (normalized != this.query) {
            tabChosenByUser = false
            selectedTab = ExpressionPanelTab.RECOMMENDED
            collapse()
        }
        manualQuery = manual
        this.query = normalized
        this.requestId = requestId
        results = emptyList()
        synthesisAvailable = false
        isContentVisible = false
    }

    fun acceptResponse(requestId: Long): Boolean =
        chatEditor && aiStickerEnabled && !recommendationsHidden && requestId == this.requestId

    fun applyResults(requestId: Long, results: List<ExpressionAsset>): Boolean {
        if (!chatEditor) return false
        if (!acceptResponse(requestId)) return false
        this.results = results.filter { it.type == "prebuilt" }
        synthesisAvailable = results.any { it.type == "synthesis-template" }
        isContentVisible = aiStickerEnabled && !recommendationsHidden &&
            (manualQuery || synthesisAvailable || this.results.isNotEmpty())
        if (!tabChosenByUser && this.results.isEmpty() && (manualQuery || synthesisAvailable) &&
            selectedTab == ExpressionPanelTab.RECOMMENDED) {
            selectedTab = ExpressionPanelTab.AI_SYNTHESIS
            tabChosenByUser = false
        }
        if (!tabChosenByUser) {
            if (this.results.isNotEmpty() && !manualQuery) {
                selectedTab = ExpressionPanelTab.RECOMMENDED
            } else if (this.results.isEmpty() && (manualQuery || synthesisAvailable)) {
                selectedTab = ExpressionPanelTab.AI_SYNTHESIS
            }
        }
        return true
    }

    fun selectTab(tab: ExpressionPanelTab) {
        if (!aiStickerEnabled) return
        tabChosenByUser = true
        selectedTab = tab
    }

    /** 临时收起结果区；与总开关不同，保留查询、结果及当前标签。 */
    fun hideRecommendations() {
        isPreparing = false
        recommendationsHidden = true
        requestId += 1
        collapse()
    }

    /** 从拼音共行按钮恢复同一查询的结果区。 */
    fun restoreRecommendations() {
        if (chatEditor && aiStickerEnabled) {
            recommendationsHidden = false
            isContentVisible = manualQuery || synthesisAvailable || results.isNotEmpty()
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
            isContentVisible = chatEditor && (manualQuery || synthesisAvailable || results.isNotEmpty())
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
        isPreparing = false
        manualQuery = false
        tabChosenByUser = false
        query = null
        selectedTab = ExpressionPanelTab.RECOMMENDED
        results = emptyList()
        synthesisAvailable = false
        isContentVisible = false
        recommendationsHidden = false
        collapse()
        requestId += 1
    }
}
