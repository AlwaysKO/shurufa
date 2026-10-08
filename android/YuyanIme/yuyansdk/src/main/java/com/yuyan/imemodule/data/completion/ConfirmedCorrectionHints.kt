package com.yuyan.imemodule.data.completion

/** 仅严格纠错成功后记录。进程内短期提示，不写假点击、不新增按键路径 IO。 */
internal class ConfirmedCorrectionHints {
    private data class Hint(val code: String, val text: String, val at: Long)
    private val hints = linkedMapOf<String, Hint>()

    @Synchronized fun record(id: String, code: String, text: String, now: Long) {
        if (id in hints) return
        hints.entries.removeAll { now >= it.value.at && now - it.value.at > PersonalCandidateRanker.RECENT_CHOICE_MS }
        hints[id] = Hint(code, text, now)
        while (hints.size > 128) hints.remove(hints.keys.first())
    }
    @Synchronized fun at(code: String, text: String, now: Long): Long? {
        var latest: Long? = null
        for (hint in hints.values) {
            if (hint.code == code && hint.text == text && hint.at <= now &&
                now - hint.at in 0..PersonalCandidateRanker.RECENT_CHOICE_MS &&
                (latest == null || hint.at > latest)) latest = hint.at
        }
        return latest
    }
    @Synchronized fun cancel(id: String) { hints.remove(id) }
    @Synchronized fun clear() { hints.clear() }
}
