package com.yuyan.imemodule.data.completion

import com.yuyan.imemodule.data.collect.CollectionConsent
import kotlinx.serialization.json.*

/** 点击时的展示位置均为零基绝对索引；source 只表示候选执行路线，不推断词库来源。 */
internal data class CandidateDiagnosticItem(
    val index: Int,
    val text: String,
    val pinyin: String,
    val nativeIndex: Int?,
    val source: String,
    val redacted: Boolean = false,
)

/** 仅随成功上屏一次性消费，由既有采集同意和编辑框门禁决定是否持久化。 */
internal data class CandidateCommitDiagnostic(
    val code: String,
    val candidates: List<CandidateDiagnosticItem>,
    val selected: CandidateDiagnosticItem,
) {
    companion object {
        const val MAX_CANDIDATES = 5
        const val MAX_SEGMENTS = 8
        const val MAX_TEXT = 64
        const val MAX_PINYIN = 256
        const val MAX_CODE = 128
    }
}

/** 调用方只在既有同意/编辑框/完整正文门禁通过后写入成功上屏事件。 */
internal fun T9CommitSelection.diagnosticJson(): JsonObject? {
    if (diagnostics.isEmpty() || code.isEmpty() ||
        (!code.all { it in '2'..'9' } && !CollectionConsent.allowsText(code))) return null
    fun item(value: CandidateDiagnosticItem) = buildJsonObject {
        put("index", value.index)
        put("source", value.source)
        value.nativeIndex?.let { put("native_index", it) }
        if (!value.redacted && CollectionConsent.allowsText(value.text) && CollectionConsent.allowsText(value.pinyin)) {
            put("text", value.text)
            put("pinyin", value.pinyin)
        } else put("redacted", true)
    }
    return buildJsonObject {
        put("protocol", 1)
        put("index_base", 0)
        put("code_scope", "composition")
        put("selection_count", diagnosticSelectionCount)
        put("truncated", diagnosticSelectionCount > diagnostics.size)
        put("selections", buildJsonArray {
            diagnostics.forEach { evidence ->
                add(buildJsonObject {
                    put("code", evidence.code)
                    put("candidates", buildJsonArray { evidence.candidates.forEach { add(item(it)) } })
                    put("selected", item(evidence.selected))
                })
            }
        })
    }
}
