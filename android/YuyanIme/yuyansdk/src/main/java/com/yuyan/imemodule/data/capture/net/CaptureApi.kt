package com.yuyan.imemodule.data.capture.net

import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    fun decodeMessagePayload(payloadJson: String): PendingMessageUploadPayload =
        json.decodeFromString(payloadJson)

    fun uploadAsset(asset: PendingAssetEntity): Boolean {
        val file = File(asset.localPath)
        if (!file.isFile) return false
        val body = AssetUploadRequest(
            sha256 = asset.sha256,
            mimeType = asset.mimeType,
            fileBase64 = file.readBytes().toByteString().base64(),
            perceptualHash = asset.perceptualHash,
            width = asset.width,
            height = asset.height,
        )
        return post("/api/v1/mobile/chat/assets", json.encodeToString(body))
    }

    fun uploadMessages(messages: List<PendingMessageUploadPayload>): Boolean {
        require(messages.isNotEmpty()) { "message batch must not be empty" }
        require(messages.size <= MAX_MESSAGE_BATCH) { "message batch must not exceed 200" }
        val first = messages.first()
        require(messages.all { it.deviceId == first.deviceId && it.conversation == first.conversation }) {
            "message batch must belong to one device and conversation"
        }
        val body = MessageBatchRequest(
            deviceId = first.deviceId,
            conversation = first.conversation,
            messages = messages.map { it.message },
        )
        return post("/api/v1/mobile/chat/messages/batch", json.encodeToString(body))
    }

    private fun post(path: String, jsonBody: String): Boolean {
        val request = Request.Builder()
            .url(baseUrl + path)
            .header("X-Device-Id", deviceId)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return http.newCall(request).execute().use { response -> response.isSuccessful }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_MESSAGE_BATCH = 200
    }
}
