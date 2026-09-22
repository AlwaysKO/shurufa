package com.yuyan.imemodule.data.collect

import com.yuyan.imemodule.data.completion.PersonalWordReading
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DictionaryRecord(
    val kind: String, val text: String, val code: String, val pinyin: String, val source: String,
    val count: Long, val weight: Double, @SerialName("last_used") val lastUsed: Long,
    @SerialName("device_id") val deviceId: String = "",
    val version: Long = 0,
) {
    fun valid(): Boolean {
        if (text.length !in 1..30 || text.any { it !in '\u4e00'..'\u9fff' } || !CollectionConsent.allowsText(text)) return false
        if (count !in 0..9_007_199_254_740_991L || !weight.isFinite() || weight < 0 || weight > count.toDouble() || lastUsed !in 0..9_007_199_254_740_991L) return false
        if (version !in 0..9_007_199_254_740_991L) return false
        if (source !in listOf("selection", "system_dictionary")) return false
        return when (kind) {
            "choice" -> source == "selection" && pinyin.isEmpty() && Regex("(?:[a-z]{2,30}|[2-9]{1,30})").matches(code) && count > 0 && lastUsed > 0
            "word" -> code.isEmpty() && count == 0L && weight == 0.0 && lastUsed == 0L &&
                (pinyin.isEmpty() || PersonalWordReading.normalize(text,pinyin) == pinyin)
            else -> false
        }
    }
}
@Serializable internal data class DictionaryPolicy(val text: String, val status: String)
@Serializable internal data class DictionarySnapshot(
    @SerialName("group_id") val groupId: String, val revision: String,
    val entries: List<DictionaryRecord>, val policies: List<DictionaryPolicy>,
)
@Serializable internal data class DictionaryReport(
    val sequence: Long, val entries: List<DictionaryRecord>,
    @SerialName("migration_status") val migrationStatus: String, val imported: Int,
)

/** 纯追加记录不携带学习次数或删除策略；手工偏好与真实点击证据分开。 */
@Serializable internal data class DictionaryAddition(
    val cursor: Long, val text: String, val pinyin: String, val preferred: Boolean = false,
) {
    fun valid(): Boolean = cursor in 1..9_007_199_254_740_991L &&
        pinyin.length in 1..210 && PersonalWordReading.normalize(text,pinyin) == pinyin &&
        CollectionConsent.allowsText(text)
}
@Serializable internal data class DictionaryAdditions(
    val entries: List<DictionaryAddition>, val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
) {
    fun validAfter(after: Long): Boolean = entries.size <= 500 && entries.all { it.valid() } &&
        (entries.isNotEmpty() || !hasMore) &&
        entries.zipWithNext().all { (a,b) -> a.cursor < b.cursor } &&
        (entries.firstOrNull()?.cursor?.let { it > after } ?: true) &&
        cursor == (entries.lastOrNull()?.cursor ?: after)
}

/** 原始来源版本与投递游标分离，两个后台重放同一学习不会累计。 */
@Serializable internal data class DictionaryHabit(
    val cursor: Long,
    @SerialName("device_id") val deviceId: String,
    val code: String, val text: String, val count: Long, val weight: Double,
    @SerialName("last_used") val lastUsed: Long, val version: Long,
    val kind: String = "choice", val source: String = "selection", val pinyin: String = "",
) {
    constructor(cursor: Long, record: DictionaryRecord): this(cursor,record.deviceId,record.code,record.text,record.count,record.weight,record.lastUsed,record.version,record.kind,record.source,record.pinyin)
    fun record() = DictionaryRecord(kind,text,code,pinyin,source,count,weight,lastUsed,deviceId,version)
    fun valid() = cursor in 1..9_007_199_254_740_991L && version in 1..9_007_199_254_740_991L &&
        deviceId.isNotBlank() && deviceId.length <= 100 && kind == "choice" && record().valid()
}
@Serializable internal data class DictionaryHabits(
    val entries: List<DictionaryHabit>, val cursor: Long,
    @SerialName("has_more") val hasMore: Boolean,
) {
    fun validAfter(after: Long): Boolean = entries.size <= 500 && entries.all { it.valid() } &&
        (entries.isNotEmpty() || !hasMore) && entries.zipWithNext().all { (a,b) -> a.cursor < b.cursor } &&
        (entries.firstOrNull()?.cursor?.let { it > after } ?: true) && cursor == (entries.lastOrNull()?.cursor ?: after)
}
