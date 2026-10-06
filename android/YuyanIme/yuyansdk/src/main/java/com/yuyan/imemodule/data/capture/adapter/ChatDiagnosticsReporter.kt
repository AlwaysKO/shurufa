package com.yuyan.imemodule.data.capture.adapter
import okhttp3.Call
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.serialization.json.*

/** 最多八次、五秒启动预算；404/失败保持最新槽，不进入 Room、不阻断业务队列。 */
class ChatDiagnosticsReporter(private val buffer: ChatDiagnosticsBuffer,
    private val prepare: (Request) -> Call?, private val finish: (Call) -> Unit = {}) {
    fun flush(source: String, allowed: () -> Boolean, now: () -> Long): Int {
        val started = now()
        var requests = 0
        repeat(8) {
            if (!allowed() || now() - started >= 5_000) return requests
            val snapshot = buffer.nextDue(now()) ?: return requests
            val request = Request.Builder().url("$source/api/v1/mobile/chat/diagnostics")
                .header("X-Device-Id", snapshot.deviceId)
                .post(snapshot.toJson().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
            val call = prepare(request) ?: return requests
            requests++
            var success = false
            var absent = false
            try {
                call.execute().use { response ->
                    absent = response.code == 404
                    // 响应也有界，200 HTML 不作有效回执。
                    val body = response.body
                    if (response.isSuccessful && body != null && body.contentLength() <= 4096) {
                        val input = body.source()
                        if (!input.request(4097)) {
                            val raw = input.readUtf8()
                            success = runCatching { Json.parseToJsonElement(raw).jsonObject["ok"]?.jsonPrimitive?.booleanOrNull == true }.getOrDefault(false)
                        }
                    }
                }
            } catch (_: Exception) { /* 诊断失败不会冒充聊天失败或影响持久队列。 */ }
            finally { finish(call); buffer.complete(snapshot, success) }
            if (absent) return requests
        }
        return requests
    }
}
