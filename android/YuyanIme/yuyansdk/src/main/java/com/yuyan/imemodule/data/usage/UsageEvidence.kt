package com.yuyan.imemodule.data.usage

import org.json.JSONObject
import java.util.UUID

internal data class UsageEvidenceEvent(val atMs: Long, val source: String) {
    fun toJson() = JSONObject().put("at_ms",atMs).put("source",source)
}

/** Bounded original-event evidence, not inferred session ends or physical touch. */
internal data class UsageEvidence(
    val version: String,
    val appActivity: UsageEvidenceEvent? = null,
    val screenOff: UsageEvidenceEvent? = null,
    val lock: UsageEvidenceEvent? = null,
) {
    fun toJson(deviceId: String? = null) = JSONObject().put("version",version).apply {
        deviceId?.let { put("device_id",it) }
        appActivity?.let { put("app_activity",it.toJson()) }
        screenOff?.let { put("screen_off",it.toJson()) }
        lock?.let { put("lock",it.toJson()) }
    }
    companion object {
        fun fromJson(j: JSONObject): UsageEvidence {
            fun event(key: String) = j.optJSONObject(key)?.let { UsageEvidenceEvent(it.getLong("at_ms"),it.getString("source")) }
            return UsageEvidence(j.getString("version"),event("app_activity"),event("screen_off"),event("lock"))
        }
        fun advance(previous: UsageEvidence?, events: List<UsageEvent>, from: Long, until: Long): UsageEvidence? {
            var app=previous?.appActivity; var off=previous?.screenOff; var lock=previous?.lock
            for(e in events.filter { it.time>=maxOf(from,946684800000L) && it.time<until }.sortedBy { it.time }) {
                when(e.type) {
                    "resume", "pause" -> if(!e.packageName.isNullOrBlank() && e.time>(app?.atMs ?: 0)) app=UsageEvidenceEvent(e.time,"usage_${e.type}")
                    "off" -> if(e.time>(off?.atMs ?: 0)) off=UsageEvidenceEvent(e.time,"usage_screen_off")
                    "lock" -> if(e.time>(lock?.atMs ?: 0)) lock=UsageEvidenceEvent(e.time,"usage_keyguard_shown")
                }
            }
            if(app==previous?.appActivity && off==previous?.screenOff && lock==previous?.lock) return previous
            return UsageEvidence(UUID.randomUUID().toString(),app,off,lock)
        }
    }
}
