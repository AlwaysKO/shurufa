package com.yuyan.imemodule.data.completion

internal data class T9SelectedPart(val text: String, val pinyin: String)
internal data class T9CommitSelection(
    val code: String, val text: String, val pinyin: String = "",
    val parts: List<T9SelectedPart> = emptyList(),
)

/** 分段选择保留原始码；最终宿主成功上屏才由调用方持久化，一次性消费。 */
internal class T9CommitTracker {
    private var pending: T9CommitSelection? = null
    private var segments: T9CommitSelection? = null

    fun selected(code: String, text: String, pinyin: String = "") {
        clear()
        pending = code.takeIf { it.isNotEmpty() }?.let { T9CommitSelection(it, text, pinyin) }
    }

    fun segment(code: String, text: String, pinyin: String, committed: String?) {
        pending = null
        if (segments == null && code.isNotEmpty()) segments = T9CommitSelection(code, "")
        if (code.isNotEmpty() && segments?.code != code) {
            clear()
            return
        }
        val previous = segments ?: return
        val reading = PersonalWordReading.normalize(text, pinyin)
        if (reading == null) {
            clear()
            // 保留旧全键/无读音整词的同码学习，不用它推断九宫格读音。
            if (committed == text && code.isNotEmpty()) selected(code, text)
            return
        }
        val combined = previous.copy(text = previous.text + text,
            pinyin = listOf(previous.pinyin, reading).filter { it.isNotEmpty() }.joinToString(" "),
            parts = previous.parts + T9SelectedPart(text, reading))
        if (committed == null) {
            segments = combined
        } else {
            clear()
            if (committed == combined.text && PersonalWordReading.matches(combined.code, combined.pinyin)) pending = combined
            else if (committed == text && code.isNotEmpty()) selected(code, text)
        }
    }

    fun clear() { pending = null; segments = null }
    fun consumeSelection(text: String, committed: Boolean): T9CommitSelection? {
        val choice = pending
        clear()
        return choice?.takeIf { committed && it.text == text }
    }
    fun consume(text: String, committed: Boolean): String? = consumeSelection(text, committed)?.code
}
