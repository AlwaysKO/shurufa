package com.yuyan.imemodule.expression

import com.yuyan.imemodule.service.DecodingInfo
import com.yuyan.inputmethod.core.Kernel

/**
 * 从引擎的当前组合态取手动斗图查询文字。
 *
 * 候选列表和候选栏展示文字都是缓存，不足以证明正在组合；必须同时满足
 * librime 的 `isComposing` 状态与非空 raw preedit，
 * 且当前列表不是上屏后的联想候选。
 */
class ExpressionComposingTextSource(
    private val isComposing: () -> Boolean,
    private val rawInput: () -> String?,
    private val isAssociate: () -> Boolean,
    private val candidateText: (Int) -> String?,
    private val clearComposition: () -> Unit = {},
) {
    fun currentText(activeCandidateIndex: Int): String? {
        if (!isComposing() || rawInput().isNullOrBlank() || isAssociate()) return null
        return (candidateText(activeCandidateIndex) ?: candidateText(0))
            ?.trim()
            ?.takeIf(String::isNotEmpty)
    }

    fun clear() = clearComposition()

    companion object {
        fun fromEngine(): ExpressionComposingTextSource = ExpressionComposingTextSource(
            isComposing = { Kernel.isComposing },
            rawInput = { Kernel.rawComposition },
            isAssociate = { DecodingInfo.isAssociate },
            candidateText = { index -> DecodingInfo.getCandidate(index)?.text },
            clearComposition = DecodingInfo::reset,
        )
    }
}
