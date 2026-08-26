package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset

enum class ExpressionPanelTab {
    RECOMMENDED,
    TEMPLATES,
    EMOJI,
}

class ExpressionPanelState {
    var query: String? = null
        private set
    var selectedTab: ExpressionPanelTab = ExpressionPanelTab.RECOMMENDED
        private set
    var results: List<ExpressionAsset> = emptyList()
        private set
    var isVisible: Boolean = false
        private set

    private var requestId = 0L
    private var dismissedQuery: String? = null

    fun beginQuery(query: String, requestId: Long) {
        val normalized = query.trim()
        require(normalized.isNotEmpty()) { "query must not be blank" }
        if (normalized != this.query) {
            dismissedQuery = null
            selectedTab = ExpressionPanelTab.RECOMMENDED
        }
        this.query = normalized
        this.requestId = requestId
        results = emptyList()
        isVisible = false
    }

    fun acceptResponse(requestId: Long): Boolean = requestId == this.requestId

    fun applyResults(requestId: Long, results: List<ExpressionAsset>): Boolean {
        if (!acceptResponse(requestId)) return false
        this.results = results
        isVisible = results.isNotEmpty() && dismissedQuery != query
        return true
    }

    fun selectTab(tab: ExpressionPanelTab) {
        selectedTab = tab
    }

    fun dismiss() {
        dismissedQuery = query
        isVisible = false
    }

    fun clear() {
        query = null
        selectedTab = ExpressionPanelTab.RECOMMENDED
        results = emptyList()
        isVisible = false
        dismissedQuery = null
        requestId += 1
    }
}
