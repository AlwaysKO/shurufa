package com.yuyan.imemodule.data.collect

import com.yuyan.imemodule.data.completion.PersonalWordReading
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class DictionaryRecord(
    val kind: String, val text: String, val code: String, val pinyin: String, val source: String,
    val count: Long, val weight: Double, @SerialName("last_used") val lastUsed: Long,
    @SerialName("device_id") val deviceId: String = "",
) {
    fun valid(): Boolean {
        if (text.length !in 1..30 || text.any { it !in '\u4e00'..'\u9fff' } || !CollectionConsent.allowsText(text)) return false
        if (count !in 0..9_007_199_254_740_991L || !weight.isFinite() || weight < 0 || weight > count.toDouble() || lastUsed !in 0..9_007_199_254_740_991L) return false
        if (source !in listOf("selection", "system_dictionary")) return false
        return when (kind) {
            "choice" -> source == "selection" && pinyin.isEmpty() && Regex("(?:[a-z]{2,30}|[2-9]{3,30})").matches(code) && count > 0 && lastUsed > 0
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
