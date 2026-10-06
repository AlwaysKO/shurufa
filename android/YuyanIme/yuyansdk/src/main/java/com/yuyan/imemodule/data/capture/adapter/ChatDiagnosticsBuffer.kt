package com.yuyan.imemodule.data.capture.adapter
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
@Serializable data class CaptureDiagnosticSnapshot(
    @SerialName("device_id") val deviceId: String,
    val platform: String,
    @SerialName("app_version_code") val appVersionCode: Long,
    @SerialName("app_version_name") val appVersionName: String,
    @SerialName("config_revision") val configRevision: Long,
    val stage: String,
    val status: String,
    @SerialName("error_code") val errorCode: Int?,
    @SerialName("observed_at") val observedAt: Long,
) { fun toJson(): String = Json.encodeToString(serializer(), this) }
class ChatDiagnosticsBuffer {
    private val snapshots = linkedMapOf<String, CaptureDiagnosticSnapshot>()
    private val dirty = mutableSetOf<String>()
    private val attempts = mutableMapOf<String, Long>()
    private fun key(s: CaptureDiagnosticSnapshot) = "${s.platform}:${s.stage}"
    @Synchronized fun record(s: CaptureDiagnosticSnapshot): Boolean {
        val statuses = mapOf("page" to setOf("matched", "rejected", "empty_tree"),
            "screenshot" to setOf("ready", "failed", "cancelled"), "persist" to setOf("inserted", "duplicate", "failed"),
            "upload" to setOf("acknowledged", "failed", "waiting"))
        if (s.platform !in setOf("wechat", "douyin") || s.status !in statuses[s.stage].orEmpty() ||
            !Regex("^[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}$").matches(s.deviceId) ||
            s.observedAt !in 1..9007199254740991L || s.appVersionCode !in 0..9007199254740991L ||
            s.configRevision !in 0..9007199254740991L || s.appVersionName.isBlank() || s.appVersionName.length > 80 ||
            s.appVersionName.any { it.code < 32 || it.code == 127 }) return false
        val key = key(s)
        val previous = snapshots[key]
        if (previous != null && previous.observedAt > s.observedAt) return false
        snapshots[key] = s
        dirty += key
        return true
    }
    @Synchronized fun nextDue(now: Long, enabled: Boolean = true): CaptureDiagnosticSnapshot? {
        if (!enabled) return null
        val key = dirty.filter { attempts[it]?.let { at -> now - at in 0 until 60_000 } != true }
            .minByOrNull { attempts[it] ?: Long.MIN_VALUE } ?: return null
        attempts[key] = now
        return snapshots[key]
    }
    @Synchronized fun complete(snapshot: CaptureDiagnosticSnapshot, success: Boolean) {
        if (success && snapshots[key(snapshot)] == snapshot) dirty.remove(key(snapshot))
    }
    @Synchronized fun latest(): List<CaptureDiagnosticSnapshot> = snapshots.values.toList()
}
