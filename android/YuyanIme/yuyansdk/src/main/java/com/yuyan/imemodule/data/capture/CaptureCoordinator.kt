package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.adapter.AdapterRegistry
import com.yuyan.imemodule.data.capture.adapter.ChatAppAdapter
import com.yuyan.imemodule.data.capture.adapter.ParseResult
import com.yuyan.imemodule.data.capture.db.CaptureDao
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.stableKeyOrNull
import com.yuyan.imemodule.data.capture.media.MediaAssetCapturer
import com.yuyan.imemodule.data.capture.media.MediaCaptureRequest
import com.yuyan.imemodule.data.capture.net.PendingMessageUploadPayload
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

interface CaptureOutboxStore {
    suspend fun enqueueIfNew(
        seenMessage: SeenMessageEntity,
        pendingMessage: PendingMessageEntity,
        pendingAssets: List<PendingAssetEntity> = emptyList(),
    ): Boolean
}

class RoomCaptureOutboxStore(
    private val dao: CaptureDao,
) : CaptureOutboxStore {
    override suspend fun enqueueIfNew(
        seenMessage: SeenMessageEntity,
        pendingMessage: PendingMessageEntity,
        pendingAssets: List<PendingAssetEntity>,
    ): Boolean = dao.enqueueIfNew(seenMessage, pendingMessage, pendingAssets)
}

class CaptureCoordinator(
    private val adapterForPackage: (String) -> ChatAppAdapter? = AdapterRegistry::forPackage,
    private val store: CaptureOutboxStore,
    private val deviceId: () -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val wakeUploader: () -> Unit,
    private val mediaCapturer: MediaAssetCapturer? = null,
) {
    val internalFailureCount = AtomicLong(0)

    suspend fun capture(packageName: String, snapshot: UiNodeSnapshot, windowId: Int? = null) {
        try {
            val adapter = adapterForPackage(packageName) ?: return
            if (adapter.packageName != packageName) return
            val result = adapter.parse(snapshot)
            if (result !is ParseResult.Success) return
            val conversation = result.viewport.conversation
            val rawMessages = result.viewport.messages
            val mediaRequests = rawMessages.mapIndexedNotNull { index, message ->
                message.mediaBounds?.let { bounds ->
                    MediaCaptureRequest(index, bounds, message.inputAreaBounds)
                }
            }
            val capturedAssets = if (mediaRequests.isNotEmpty() && windowId != null && mediaCapturer != null) {
                try {
                    mediaCapturer.capture(windowId, snapshot.bounds, mediaRequests)
                } catch (_: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            enqueueParsed(conversation, rawMessages, capturedAssets)
        } catch (_: Exception) {
            internalFailureCount.incrementAndGet()
        }
    }

    suspend fun captureParsed(
        conversation: CapturedConversation,
        messages: List<CapturedMessage>,
        pendingAssetsByMessage: Map<Int, PendingAssetEntity> = emptyMap(),
    ) {
        try {
            enqueueParsed(conversation, messages, pendingAssetsByMessage)
        } catch (_: Exception) {
            internalFailureCount.incrementAndGet()
        }
    }

    private suspend fun enqueueParsed(
        conversation: CapturedConversation,
        rawMessages: List<CapturedMessage>,
        capturedAssets: Map<Int, PendingAssetEntity>,
    ) {
        if (conversation.identityConfidence < MIN_IDENTITY_CONFIDENCE) return
        val conversationKey = conversation.stableKeyOrNull() ?: return
        var insertedAny = false
        for ((index, rawMessage) in rawMessages.withIndex()) {
            val asset = capturedAssets[index]
            val message = rawMessage.copy(
                conversationKey = conversationKey,
                assetSha256 = if (asset == null) rawMessage.assetSha256 else
                    (rawMessage.assetSha256 + asset.sha256).distinct(),
                metadata = if (rawMessage.mediaBounds != null && asset == null) {
                    rawMessage.metadata + ("asset_capture_failed" to "true")
                } else {
                    rawMessage.metadata
                },
            )
            val fingerprint = messageFingerprint(message) ?: continue
            val capturedAt = clock()
            val pending = pendingMessage(conversation, message, fingerprint, capturedAt)
            if (store.enqueueIfNew(
                    SeenMessageEntity(fingerprint, capturedAt),
                    pending,
                    listOfNotNull(asset),
                )
            ) {
                insertedAny = true
            }
        }
        if (insertedAny) wakeUploader()
    }

    private fun pendingMessage(
        conversation: CapturedConversation,
        message: CapturedMessage,
        fingerprint: String,
        capturedAt: Long,
    ): PendingMessageEntity {
        val id = UUID.randomUUID().toString()
        val contentFingerprint = contentFingerprint(message)
        val messageJson = buildJsonObject {
            put("id", id)
            put("fingerprint", fingerprint)
            put("content_fingerprint", contentFingerprint)
            put("sender_key", message.senderKey)
            message.senderName?.let { put("sender_name", it) }
            put("direction", message.direction.wireName)
            put("message_type", message.messageType.wireName)
            message.text?.let { put("text", it) }
            message.displayedTime?.let { put("displayed_time", it) }
            message.occurredAt?.let { put("occurred_at", it) }
            put("captured_at", isoTimestamp(capturedAt))
            if (message.assetSha256.isNotEmpty()) {
                put("asset_sha256", JsonArray(message.assetSha256.map(::JsonPrimitive)))
            }
            if (message.metadata.isNotEmpty()) {
                put("metadata", buildJsonObject {
                    message.metadata.forEach { (key, value) -> put(key, value) }
                })
            }
        }
        val conversationJson = buildJsonObject {
            put("platform", conversation.platform.wireName)
            put("account_key", conversation.accountKey)
            put("external_key", conversation.externalKey.orEmpty())
            conversation.displayName?.let { put("display_name", it) }
            put("conversation_type", conversation.conversationType.wireName)
            put("identity_confidence", conversation.identityConfidence)
        }
        val payload = PendingMessageUploadPayload(
            deviceId = deviceId(),
            conversation = conversationJson,
            message = messageJson,
        )
        return PendingMessageEntity(
            id = id,
            fingerprint = fingerprint,
            conversationKey = message.conversationKey.orEmpty(),
            payloadJson = Json.encodeToString(payload),
            requiredAssetHashesJson = Json.encodeToString(message.assetSha256),
        )
    }

    private fun isoTimestamp(milliseconds: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(milliseconds))

    private companion object {
        const val MIN_IDENTITY_CONFIDENCE = 0.8
    }
}
