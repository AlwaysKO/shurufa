package com.yuyan.imemodule.expression

sealed interface ExpressionManualSearchDecision {
    data object MissingText : ExpressionManualSearchDecision
    data class Query(val text: String) : ExpressionManualSearchDecision
}

/**
 * AI 斗图手动搜索的会话内决策与动作。
 *
 * 只保留本次输入会话中最近一次上屏文字，不读取宿主编辑框或聊天历史。
 */
class ExpressionManualSearch(
    private val showMissingText: () -> Unit,
    private val preparePanel: () -> Unit,
    private val searchImmediately: (String) -> Unit,
) {
    private var recentCommittedText: String? = null

    fun onCommitted(text: String?) {
        recentCommittedText = text.searchableTextOrNull() ?: recentCommittedText
    }

    fun resetSession() {
        recentCommittedText = null
    }

    fun perform(
        activeComposingText: String?,
        panelLastQuery: String?,
    ): ExpressionManualSearchDecision {
        return when (val decision = resolve(activeComposingText, recentCommittedText, panelLastQuery)) {
            ExpressionManualSearchDecision.MissingText -> {
                showMissingText()
                decision
            }

            is ExpressionManualSearchDecision.Query -> {
                preparePanel()
                searchImmediately(decision.text)
                decision
            }
        }
    }

    companion object {
        fun resolve(
            activeComposingText: String?,
            recentCommittedText: String?,
            panelLastQuery: String?,
        ): ExpressionManualSearchDecision {
            val query = activeComposingText.searchableTextOrNull()
                ?: recentCommittedText.searchableTextOrNull()
                ?: panelLastQuery.searchableTextOrNull()
            return query?.let(ExpressionManualSearchDecision::Query)
                ?: ExpressionManualSearchDecision.MissingText
        }
    }
}

private fun String?.searchableTextOrNull(): String? = this
    ?.trim()
    ?.takeIf { text -> text.any(Char::isLetterOrDigit) }
