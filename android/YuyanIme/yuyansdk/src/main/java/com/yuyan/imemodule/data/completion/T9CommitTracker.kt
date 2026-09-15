package com.yuyan.imemodule.data.completion

/** 选择和宿主成功上屏之间的单次交接，不从展示拼音反推用户编码。 */
internal class T9CommitTracker {
    private var pending: Pair<String, String>? = null
    fun selected(code: String, text: String) { pending = code.takeIf { it.isNotEmpty() }?.let { it to text } }
    fun clear() { pending = null }
    fun consume(text: String, committed: Boolean): String? {
        val choice = pending
        pending = null
        return choice?.takeIf { committed && it.second == text }?.first
    }
}
