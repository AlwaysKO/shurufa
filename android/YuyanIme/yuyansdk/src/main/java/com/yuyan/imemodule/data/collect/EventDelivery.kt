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
import java.util.concurrent.CancellationException

/** 每个目标独立确认；网络调用只能由 IO 线程调用，失败保持数据库原状。 */
internal class EventDelivery(
    private val store: LocalInputStore,
    private val http: OkHttpClient,
    private val deviceId: String,
    private val deviceJson: String,
    private val onlineTarget: () -> String? = { null },
    private val allowed: (String?) -> Boolean = { true },
    private val maxImageBytes: (String) -> Long = { Long.MAX_VALUE },
    private val beginImageRead: () -> java.io.Closeable? = { java.io.Closeable {} },
    private val tryStartImage: (String, Long) -> java.io.Closeable? = { _, _ -> java.io.Closeable {} },
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val registered = ConcurrentHashMap.newKeySet<String>()
    private val reportsFirstNext = ConcurrentHashMap.newKeySet<String>()
    private val json = Json { ignoreUnknownKeys = true }

    /** 小批次串行补传；空队列、无进展或失败立即结束，避免忙循环与全量加载。 */
    fun drain(
        target: String,
        maxBatches: Int = 100,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        beforeBatch: () -> Unit = {},
        beforeRequest: () -> Unit = {},
    ): Boolean = synchronized(locks.getOrPut(target) { Any() }) {
        require(maxBatches > 0)
        val started = nowMillis()
        repeat(maxBatches) { batch ->
            if (batch > 0 && nowMillis() - started >= 5_000) return@synchronized true
            beforeBatch() // 取消必须传播，不能在网络失败捕获中吞掉。
            var acknowledged = 0
            val ok = flushBatch(target, canStartRequest = {
                beforeRequest()
                nowMillis() - started < 5_000
            }) { acknowledged += it }
            if (!ok) return@synchronized false
            if (acknowledged == 0) return@synchronized true
        }
        true
    }

    fun flush(target: String): Boolean = synchronized(locks.getOrPut(target) { Any() }) {
        flushBatch(target) {}
    }

    private fun flushBatch(
        target: String,
        canStartRequest: () -> Boolean = { true },
        confirmed: (Int) -> Unit,
    ): Boolean {
        return try {
            if (!allowed(null)) return false
            if (!canStartRequest()) return true
            if (target !in registered) {
                ReportingTrace.record(ReportingStage.REGISTER, target == onlineTarget())
                if (!post(target, "/api/v1/mobile/device", deviceJson)) return false
                registered.add(target)
            }
            // 两类队列轮换先手，防止慢失败总是耗尽5秒预算、饿死另一类。
            val reportsFirst = reportsFirstNext.remove(target)
            if (!reportsFirst) reportsFirstNext.add(target)
            val eventsOk = if (reportsFirst) true else flushEvents(target, canStartRequest, confirmed)
            var reportsOk = true
            ReportingTrace.record(ReportingStage.READ_REPORTS, target == onlineTarget())
            val reports = store.pendingReports(target, includeLocation = allowed("location"),
                maxImageBytes = { maxImageBytes(target) }, beginImageRead = beginImageRead)
            ReportingTrace.record(ReportingStage.REPORTS_READY, target == onlineTarget(), reports.size)
            for (report in reports) {
                if (!allowed(report.kind)) continue
                if (!canStartRequest()) return eventsOk && reportsOk
                var imagePermit: java.io.Closeable? = null
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
                    if (report.kind == "chat_asset") {
                        // 读取队列后可能再次开始打字；暂停不是失败，不改变重试次序。
                        imagePermit = tryStartImage(target, payload.toByteArray(Charsets.UTF_8).size.toLong())
                            ?: continue
                    }
                    post(target, path, payload, if (isChat) null else report.id)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    ReportingTrace.record(ReportingStage.DELIVERY_ERROR, target == onlineTarget())
                    false
                } finally {
                    imagePermit?.close()
                }
                if (sent) {
                    store.acknowledgeReports(target, listOf(report.id), onlineTarget())
                    confirmed(1)
                    ReportingTrace.record(ReportingStage.STORE_ACK, target == onlineTarget(), 1)
                }
                else {
                    store.deferReport(target, report.id) // 保留失败项，但给后续正常报告发送机会。
                    registered.remove(target)
                    reportsOk = false
                }
            }
            val finalEventsOk = if (reportsFirst) flushEvents(target, canStartRequest, confirmed) else eventsOk
            finalEventsOk && reportsOk
        } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) {
            ReportingTrace.record(ReportingStage.DELIVERY_ERROR, target == onlineTarget())
            false
        }
    }
    private fun flushEvents(target: String, canStartRequest: () -> Boolean, confirmed: (Int) -> Unit): Boolean {
        ReportingTrace.record(ReportingStage.READ_EVENTS, target == onlineTarget())
        var eventsOk = true
        // 事件与聊天是独立队列；事件接口或单个事件异常不能阻塞聊天补传。
        try {
            val pending = store.pending(target)
            if (pending.isNotEmpty() && allowed("events")) {
                val batch = boundedEventBatch(pending)
                // 保留超大事件，不截断或假确认。
                if (batch.isEmpty()) eventsOk = false
                else {
                    val body = json.encodeToString(EventBatch.serializer(), EventBatch(deviceId, batch))
                    if (!canStartRequest()) return true
                    if (post(target, "/api/v1/mobile/events/batch", body)) {
                        store.acknowledge(target, batch.map { it.id })
                        confirmed(batch.size)
                        ReportingTrace.record(ReportingStage.STORE_ACK, target == onlineTarget(), batch.size)
                    } else eventsOk = false
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            ReportingTrace.record(ReportingStage.DELIVERY_ERROR, target == onlineTarget())
            eventsOk = false
        }
        if (!eventsOk) registered.remove(target)
        return eventsOk
    }

    /** Include the JSON envelope, escaping, commas and UTF-8 bytes, not Kotlin character counts. */
    private fun boundedEventBatch(pending: List<MobileEvent>): List<MobileEvent> {
        val budget = 1024 * 1024 // Stay well below both the server JSON limit and proxy limits.
        var bytes = json.encodeToString(EventBatch.serializer(), EventBatch(deviceId, emptyList()))
            .toByteArray(Charsets.UTF_8).size
        var count = 0
        for (event in pending) {
            val eventBytes = json.encodeToString(MobileEvent.serializer(), event).toByteArray(Charsets.UTF_8).size
            val addition = eventBytes + if (count == 0) 0 else 1
            if (addition > budget - bytes) break
            bytes += addition
            count++
        }
        return pending.take(count)
    }

    private fun post(target: String, path: String, body: String, reportId: String? = null): Boolean {
        val stage = when (path) {
            "/api/v1/mobile/device" -> ReportingStage.POST_DEVICE
            "/api/v1/mobile/events/batch" -> ReportingStage.POST_EVENTS
            "/api/v1/mobile/chat/assets" -> ReportingStage.POST_ASSET
            "/api/v1/mobile/chat/messages/batch" -> ReportingStage.POST_MESSAGES
            else -> ReportingStage.POST_OTHER
        }
        ReportingTrace.record(stage, target == onlineTarget())
        val request = Request.Builder().url(target + path).header("X-Device-Id", deviceId)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        return http.newCall(request).execute().use { response ->
            ReportingTrace.record(ReportingStage.HTTP_RESULT, target == onlineTarget(), response.code)
            // 避免把反向代理返回的 200 HTML 登录页当成入库成功。
            if (!response.isSuccessful) false else {
                val result = json.parseToJsonElement(response.body?.string() ?: "")
                (result.jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true &&
                    (reportId == null || result.jsonObject["id"]?.jsonPrimitive?.content == reportId)).also {
                    ReportingTrace.record(ReportingStage.ACK_RESULT, target == onlineTarget(), flag = it)
                }
            }
        }
    }
}
