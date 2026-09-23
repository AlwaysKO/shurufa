package com.yuyan.imemodule.data.completion

/** 只关联本次明确上屏及连续键盘退格；未知/歧义/外部编辑宁可漏判，不扣历史学习。 */
internal data class LearningCorrection(val rewardId: String, val retainedParts: List<T9SelectedPart>, val kind: String)

internal class CorrectionLearningTracker {
    private data class Pending(
        val selection: T9CommitSelection, val before: String, val prefix: String, val suffix: String,
        val reward: String, val committedAt: Long, var current: String,
        var remaining: Int, var firstDeleteAt: Long? = null,
    )
    private var pending: Pending? = null

    fun reset() { pending = null }

    /** 兼容原有无读音的整词同码识别。 */
    fun commit(code: String, text: String, before: String?, after: String?, reward: String?, at: Long): String? =
        commitSelection(T9CommitSelection(code, text), before, after, reward, at)?.rewardId

    fun commitSelection(selection: T9CommitSelection, before: String?, after: String?, reward: String?, at: Long): LearningCorrection? {
        val old = pending
        reset()
        val position = insertion(selection.text, before, after) ?: return null
        val correction = old?.takeIf {
            val deletedAt = it.firstDeleteAt
            deletedAt != null && at >= deletedAt && at - deletedAt <= REPLACE_WINDOW_MS &&
                before == it.current && position == it.prefix.length + it.remaining
        }?.let {
            when {
                it.remaining == 0 && before == it.before && selection.code == it.selection.code && selection.text != it.selection.text ->
                    LearningCorrection(it.reward, emptyList(), "whole_same_code")
                it.remaining > 0 && sameSuffixReading(it, selection) ->
                    LearningCorrection(it.reward, retainedParts(it), "suffix_same_reading")
                else -> null
            }
        }
        if (reward != null) pending = Pending(selection, before!!, before.take(position),
            before.drop(position), reward, at, after!!, selection.text.length)
        return correction
    }

    private fun sameSuffixReading(old: Pending, replacement: T9CommitSelection): Boolean {
        val reading = PersonalWordReading.normalize(old.selection.text, old.selection.pinyin) ?: return false
        if (!PersonalWordReading.matches(old.selection.code, reading)) return false
        val removed = old.selection.text.drop(old.remaining)
        if (replacement.text == removed || replacement.text.length != removed.length) return false
        val newReading = PersonalWordReading.normalize(replacement.text, replacement.pinyin) ?: return false
        if (newReading != reading.split(' ').drop(old.remaining).joinToString(" ")) return false
        // 单字已知读音允许其1～2键前缀；多字仍须通过已有完整/简拼边界校验。
        return PersonalWordReading.matches(replacement.code, newReading) ||
            (replacement.text.length == 1 && replacement.code.length in 1..2 &&
                replacement.code.all { it in '2'..'9' } && T9Lexicon.digits(newReading).startsWith(replacement.code))
    }

    private fun retainedParts(old: Pending): List<T9SelectedPart> {
        val parts = old.selection.parts
        if (parts.size <= 1 || parts.joinToString("") { it.text } != old.selection.text ||
            parts.joinToString(" ") { it.pinyin } != old.selection.pinyin) return emptyList()
        var length = 0
        return parts.takeWhile { part ->
            length += part.text.length
            length <= old.remaining && PersonalWordReading.normalize(part.text, part.pinyin) != null
        }
    }

    fun delete(before: String?, after: String?, at: Long) {
        val old = pending ?: return
        val first = old.firstDeleteAt
        if (before != old.current || after == null || at < old.committedAt ||
            (first == null && at - old.committedAt > DELETE_WINDOW_MS) ||
            (first != null && (at < first || at - first > REPLACE_WINDOW_MS))) { reset(); return }
        val remaining = after.length - old.prefix.length - old.suffix.length
        if (remaining !in 0 until old.remaining ||
            after != old.prefix + old.selection.text.take(remaining) + old.suffix) { reset(); return }
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
