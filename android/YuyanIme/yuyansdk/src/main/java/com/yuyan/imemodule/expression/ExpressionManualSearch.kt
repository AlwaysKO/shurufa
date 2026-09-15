package com.yuyan.imemodule.expression

sealed interface ExpressionManualSearchDecision {
    data object MissingText : ExpressionManualSearchDecision
    data class Query(val text: String) : ExpressionManualSearchDecision
}

/** 宿主文本的提交形态：候选/语音是整段，关闭英文补全时是逐字。 */
enum class ExpressionCommitKind {
    COMPLETE,
    INCREMENTAL,
}

/**
 * AI 斗图手动搜索的会话内决策与动作。
 *
 * 只保留本次输入会话最多 100 UTF-16 单位的上屏片段，不读取宿主编辑框或聊天历史。
 */
class ExpressionManualSearch(
    private val showMissingText: () -> Unit,
    private val preparePanel: () -> Unit,
    private val searchImmediately: (String) -> Unit,
) {
    private var recentCommittedText: String? = null
    private val incrementalText = StringBuilder()
    private var chineseText = ""

    fun onCommitted(text: String?) {
        text?.let { onHostCommitted(it, ExpressionCommitKind.COMPLETE) }
    }

    /**
     * 仅在 InputConnection 真实提交成功后调用。
     *
     * @return 本次确实更新的有效查询；纯空白/标点返回 null，不重复触发自动搜索。
     */
    fun onHostCommitted(text: String, kind: ExpressionCommitKind): String? {
        val next = when (kind) {
            ExpressionCommitKind.COMPLETE -> {
                incrementalText.clear()
                val searchable = text.searchableTextOrNull()
                if (searchable == null) {
                    chineseText = ""
                    return null
                }
                // 允许最后一个中文片段连同句末标点进入本句，再切断下一句的续接。
                val chinesePart = searchable.trimEnd { it in "，。！？!?；;、…～~" }
                var isChinese = chinesePart.isNotEmpty()
                chinesePart.forEachCodePoint { codePoint ->
                    if (!Character.isIdeographic(codePoint)) isChinese = false
                }
                if (isChinese) {
                    val combined = (chineseText + searchable).boundedTail()
                    chineseText = if (chinesePart == searchable) combined else ""
                    combined
                } else {
                    chineseText = ""
                    searchable.boundedTail()
                }
            }

            ExpressionCommitKind.INCREMENTAL -> {
                chineseText = ""
                accumulateIncrementalText(text) ?: return null
            }
        }
        if (next == recentCommittedText) return null
        recentCommittedText = next
        return next
    }

    fun resetSession() {
        recentCommittedText = null
        incrementalText.clear()
        chineseText = ""
    }

    /** 光标/选区变动后无法再安全推断宿主文本，丢弃本地 fallback。 */
    fun invalidateCommittedText() = resetSession()

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

    private fun accumulateIncrementalText(text: String): String? {
        text.forEachCodePoint { codePoint ->
            when {
                Character.isLetterOrDigit(codePoint) -> incrementalText.appendCodePoint(codePoint)
                Character.isWhitespace(codePoint) && incrementalText.isNotEmpty() && incrementalText.last() != ' ' -> {
                    incrementalText.append(' ')
                }
            }
        }
        val bounded = incrementalText.toString().boundedTail()
        incrementalText.clear().append(bounded)
        return bounded.searchableTextOrNull()
    }
}

private fun String?.searchableTextOrNull(): String? = this
    ?.trim()
    ?.takeIf(String::hasLetterOrDigitCodePoint)

private fun String.hasLetterOrDigitCodePoint(): Boolean {
    var found = false
    forEachCodePoint { codePoint ->
        if (Character.isLetterOrDigit(codePoint)) found = true
    }
    return found
}

private inline fun String.forEachCodePoint(action: (Int) -> Unit) {
    var offset = 0
    while (offset < length) {
        val codePoint = Character.codePointAt(this, offset)
        action(codePoint)
        offset += Character.charCount(codePoint)
    }
}

/** 与接口的 UTF-16 长度上限一致；如果截断落在代理对中间，完整丢弃该字符。 */
private fun String.boundedTail(): String {
    if (length <= 100) return this
    var start = length - 100
    if (Character.isLowSurrogate(this[start]) && Character.isHighSurrogate(this[start - 1])) start++
    return substring(start)
}
