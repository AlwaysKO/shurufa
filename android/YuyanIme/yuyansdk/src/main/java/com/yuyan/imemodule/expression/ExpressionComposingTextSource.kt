package com.yuyan.imemodule.expression

import com.yuyan.imemodule.service.DecodingInfo

/**
 * 从引擎的当前组合态取手动斗图查询文字。
 *
 * 候选列表本身不足以证明正在组合；必须同时有非空的引擎组合编码，
 * 且当前列表不是上屏后的联想候选。
 */
class ExpressionComposingTextSource(
    private val compositionText: () -> String?,
    private val isAssociate: () -> Boolean,
    private val candidateText: (Int) -> String?,
) {
    fun currentText(activeCandidateIndex: Int): String? {
        if (isAssociate() || compositionText().isNullOrBlank()) return null
        return (candidateText(activeCandidateIndex) ?: candidateText(0))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    companion object {
        fun fromEngine(): ExpressionComposingTextSource = ExpressionComposingTextSource(
            compositionText = { DecodingInfo.composingStrForDisplay },
            isAssociate = { DecodingInfo.isAssociate },
            candidateText = { index -> DecodingInfo.getCandidate(index)?.text },
        )
    }
}
