package com.yuyan.imemodule.data.completion

/** 只关联本次明确上屏及连续键盘退格；未知/歧义/外部编辑宁可漏判，不扣历史学习。 */
internal class CorrectionLearningTracker {
    private data class Pending(
        val code: String, val text: String, val before: String, val prefix: String, val suffix: String,
        val reward: String, val committedAt: Long, var current: String,
        var remaining: Int, var firstDeleteAt: Long? = null,
    )
    private var pending: Pending? = null

    fun reset() { pending = null }

    /** 返回应取消的旧奖励ID；新奖励由调用方创建，重复回调不能重复撤销。 */
    fun commit(code: String, text: String, before: String?, after: String?, reward: String?, at: Long): String? {
        val old = pending
        reset()
        val position = insertion(text, before, after) ?: return null
        val canceled = old?.takeIf {
            val deletedAt = it.firstDeleteAt
            deletedAt != null && at >= deletedAt && at - deletedAt <= REPLACE_WINDOW_MS &&
                it.remaining == 0 && before == it.before && before == it.current &&
                position == it.prefix.length && code == it.code && text != it.text
        }?.reward
        if (reward != null) pending = Pending(code, text, before!!, before.take(position),
            before.drop(position), reward, at, after!!, text.length)
        return canceled
    }

    fun delete(before: String?, after: String?, at: Long) {
        val old = pending ?: return
        val first = old.firstDeleteAt
        if (before != old.current || after == null || at < old.committedAt ||
            (first == null && at - old.committedAt > DELETE_WINDOW_MS) ||
            (first != null && (at < first || at - first > REPLACE_WINDOW_MS))) { reset(); return }
        val remaining = after.length - old.prefix.length - old.suffix.length
        if (remaining !in 0 until old.remaining ||
            after != old.prefix + old.text.take(remaining) + old.suffix) { reset(); return }
        old.firstDeleteAt = first ?: at
        old.remaining = remaining
        old.current = after
    }

    companion object {
        const val DELETE_WINDOW_MS = 2_000L
        const val REPLACE_WINDOW_MS = 15_000L
        const val REWARD_WINDOW_MS = DELETE_WINDOW_MS + REPLACE_WINDOW_MS

        fun canTrack(text: String, before: String?, after: String?): Boolean = insertion(text, before, after) != null

        private fun insertion(text: String, before: String?, after: String?): Int? {
            if (text.isEmpty() || before == null || after == null || after.length != before.length + text.length) return null
            // 重复字符可产生多个同样的diff，不能把猜测位置当作光标证据。
            var found: Int? = null
            for (position in 0..before.length) {
                if (after.regionMatches(position, text, 0, text.length) &&
                    after.removeRange(position, position + text.length) == before) {
                    if (found != null) return null
                    found = position
                }
            }
            return found
        }
    }
}
