package com.yuyan.imemodule.data.completion

/** 候选刷新内的读音规范化；不缓存动态排序或跨刷新保存个人状态。 */
internal class CandidateReadingMemo(
    private val normalizer: (String, String) -> String? = PersonalWordReading::normalize,
) {
    private val values = HashMap<Pair<String, String>, String?>()
    // 普通候选与展开分页可跨线程访问；锁仅覆盖本次缓存和纯读音计算，不含数据库/IO。
    @Synchronized fun normalize(text: String, reading: String): String? {
        val key = text to reading
        if (values.containsKey(key)) return values[key]
        val result = normalizer(text, reading)
        // 分页闭包可能延长生命周期；上限只限制缓存，不裁剪候选或结果。
        if (values.size < 512) values[key] = result
        return result
    }
}
