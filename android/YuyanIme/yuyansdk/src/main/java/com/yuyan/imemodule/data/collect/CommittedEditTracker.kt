package com.yuyan.imemodule.data.collect

import android.text.Spanned
import android.view.inputmethod.ExtractedText
import java.util.UUID

/** 原始操作不覆盖；只有完整、连续的宿主文本才能共用一个编辑会话。 */
internal class CommittedEditTracker {
    private var sessionId = UUID.randomUUID().toString()
    private var sequence = 0L
    var lastText: String? = null
        private set

    fun reset() {
        sessionId = UUID.randomUUID().toString()
        sequence = 0
        lastText = null
    }

    fun record(before: String?, after: String?, successful: Boolean = true): CommittedEdit? {
        if (!successful) return null
        if (before != null && before == after) return null
        val complete = before != null && after != null
        if (!complete || (sequence > 0 && lastText != before)) reset()
        val change = if (complete) changedText(before!!, after!!) else (null to null)
        val result = CommittedEdit(sessionId, ++sequence, before, after, complete, change.first, change.second)
        lastText = after
        // 全删、发送清空或无法读取的操作均不能连到下一句。
        if (!complete || after == "") reset()
        return result
    }
}

internal data class CommittedEdit(
    val sessionId: String,
    val sequenceNo: Long,
    val before: String?,
    val after: String?,
    val complete: Boolean,
    val removedText: String?,
    val insertedText: String?,
)

/** 最小替换区间；不把 diff 当作光标或发送动作的证据。 */
private fun changedText(before: String, after: String): Pair<String, String> {
    var start = 0
    while (start < before.length && start < after.length && before[start] == after[start]) start++
    var oldEnd = before.length
    var newEnd = after.length
    while (oldEnd > start && newEnd > start && before[oldEnd - 1] == after[newEnd - 1]) { oldEnd--; newEnd-- }
    // 不在 UTF-16 代理对之间截断表情字符。
    if (start > 0 && start < before.length && Character.isLowSurrogate(before[start])) start--
    if (oldEnd < before.length && oldEnd > 0 && Character.isLowSurrogate(before[oldEnd])) { oldEnd++; newEnd++ }
    return before.substring(start, oldEnd) to after.substring(start, newEnd)
}

/** partialStartOffset=-1 才是全量结果；超限/局部/未提供样式的组合态宁可未知，不采集拼音。 */
internal fun committedSnapshot(extracted: ExtractedText?, composingExpected: Boolean = false): String? {
    if (extracted == null || extracted.startOffset != 0 || extracted.partialStartOffset != -1) return null
    val text = extracted.text ?: return null
    if (text.length > 5000) return null
    val ranges = (text as? Spanned)?.getSpans(0, text.length, Any::class.java)
        ?.filter { text.getSpanFlags(it) and Spanned.SPAN_COMPOSING != 0 }
        ?.map { text.getSpanStart(it) until text.getSpanEnd(it) }.orEmpty()
    if (composingExpected && ranges.isEmpty()) return null
    return text.indices.filterNot { index -> ranges.any { index in it } }.map { text[it] }.joinToString("")
}
