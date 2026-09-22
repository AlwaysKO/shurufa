package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.capture.adapter.AdapterRegistry
import com.yuyan.imemodule.data.capture.adapter.ChatAppAdapter
import com.yuyan.imemodule.data.capture.adapter.ParseResult
import com.yuyan.imemodule.data.capture.adapter.ParsedViewport
import com.yuyan.imemodule.data.capture.db.CaptureDao
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.stableKeyOrNull
import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentity
import com.yuyan.imemodule.data.capture.media.ConversationTitleStabilizer
import com.yuyan.imemodule.data.capture.media.capturedTitlePixelSignature
import com.yuyan.imemodule.data.capture.media.unresolvedWechatScreenshotIdentity
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

enum class CapturePersistResult {
    INSERTED,
    ALREADY_PERSISTED,
    FAILED,
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
    private val captureAllowed: () -> Boolean = { true },
    private val onViewportParsed: (ParsedViewport) -> Unit = {},
    private val identityStore: com.yuyan.imemodule.data.capture.media.ConversationIdentityStore = com.yuyan.imemodule.data.capture.media.MemoryConversationIdentityStore(),
    private val captureGeneration: () -> Long = { 0L },
    private val titleSignature: (PendingAssetEntity) -> String? = ::capturedTitlePixelSignature,
) {
    val internalFailureCount = AtomicLong(0)
    private val identityLock = Any()
    private var identityGeneration = 0L
    private var identityScope: String? = null
    private var identityTracker: ConversationTitleStabilizer? = null
    private val unresolvedFrames = linkedMapOf<String, ScreenshotConversationIdentity>()

    fun resetConversationIdentity() = synchronized(identityLock) {
        identityGeneration++
        identityScope = null
        identityTracker = null
        unresolvedFrames.clear()
    }

    suspend fun capture(packageName: String, snapshot: UiNodeSnapshot, windowId: Int? = null): Boolean {
        if (!captureAllowed()) return false
        val captureToken = captureGeneration()
        try {
            val adapter = adapterForPackage(packageName) ?: return false
            if (adapter.packageName != packageName) return false
            val result = adapter.parse(snapshot)
            if (result !is ParseResult.Success) return false
            var conversation = result.viewport.conversation
            val identityVersion = synchronized(identityLock) { identityGeneration }
            val titleBounds = result.viewport.titleBounds
            var rawMessages = result.viewport.messages.filter { message ->
                CollectionConsent.allowsText(message.text) &&
                    message.metadata.values.all { CollectionConsent.allowsText(it) }
            }
            val mediaRequests = rawMessages.mapIndexedNotNull { index, message ->
                if (!CollectionConsent.allowsText(message.text)) return@mapIndexedNotNull null
                message.mediaBounds?.let { bounds ->
                    MediaCaptureRequest(
                        index,
                        bounds,
                        message.inputAreaBounds,
                        lossyWebp = message.metadata["capture_kind"] == "conversation_screenshot",
                    )
                }
            }
            val screenshotWithTitle = titleBounds != null && rawMessages.isNotEmpty() &&
                rawMessages.all { it.metadata["capture_kind"] == "conversation_screenshot" }
            val requests = if (screenshotWithTitle) mediaRequests + MediaCaptureRequest(-1, titleBounds!!) else mediaRequests
            val capturedAssets = if (requests.isNotEmpty() && windowId != null && mediaCapturer != null) {
                try {
                    mediaCapturer.capture(windowId, snapshot.bounds, requests)
                } catch (_: Exception) {
                    emptyMap()
                }
            } else {
                emptyMap()
            }
            if (!captureAllowed() || captureGeneration() != captureToken) return false
            // 图片失败不能落一个没有附件的占位消息；让上层有限重试，而不是等下一次用户操作。
            if (rawMessages.all { it.metadata["capture_kind"] == "conversation_screenshot" } &&
                mediaRequests.any { capturedAssets[it.messageIndex] == null }) return true
            if (screenshotWithTitle) {
                val visualKey = capturedAssets[-1]?.let(titleSignature)
                val identity = synchronized(identityLock) {
                    if (identityVersion != identityGeneration) {
                        unresolvedWechatScreenshotIdentity().let { it.copy(externalKey = it.externalKey.replace("screenshot-pending:", "capture-pending:")) }
                    } else {
                        val scope = "${conversation.platform.wireName}|${conversation.accountKey}|$windowId"
                        if (identityScope != scope) {
                            identityScope = scope
                            unresolvedFrames.clear()
                            identityTracker = ConversationTitleStabilizer(conversation.platform, conversation.accountKey, "accessibility_title", identityStore = identityStore)
                        }
                        val observed = identityTracker!!.observe(conversation.displayName, visualKey, clock())
                        // 只在本次连续页面内复用完全相同的未知帧，导航后不据此认定同一联系人。
                        if (observed.externalKey.startsWith("capture-pending:") && capturedAssets.isNotEmpty()) {
                            val frameKey = scope + "|" + capturedAssets.toSortedMap().entries.joinToString("|") { "${it.key}:${it.value.sha256}" }
                            unresolvedFrames.getOrPut(frameKey) { observed }.also {
                                if (unresolvedFrames.size > 64) unresolvedFrames.remove(unresolvedFrames.keys.first())
                            }
                        } else observed
                    }
                }
                conversation = conversation.copy(externalKey = identity.externalKey,
                    displayName = identity.displayName, identityConfidence = identity.confidence)
                rawMessages = rawMessages.map { it.copy(metadata = it.metadata + mapOf(
                    "conversation_identity_status" to identity.status,
                    "identity_unavailable" to (identity.status != "confirmed").toString(),
                    "conversation_identity_source" to identity.source,
                    "conversation_identity_observed_title" to identity.observedTitle.orEmpty(),
                    "conversation_identity_previous_key" to identity.previousKey.orEmpty(),
                )) }
            }
            onViewportParsed(result.viewport.copy(conversation = conversation, messages = rawMessages))
            val persisted = enqueueParsed(conversation, rawMessages, capturedAssets.filterKeys { it >= 0 }, captureToken)
            CaptureTrace.record(CaptureStage.PERSIST_RESULT, value = persisted.ordinal, layer = CaptureLayer.COORDINATOR)
            return screenshotWithTitle && (persisted == CapturePersistResult.FAILED || conversation.identityConfidence < 0.8)
        } catch (_: Exception) {
            internalFailureCount.incrementAndGet()
            CaptureTrace.record(CaptureStage.PIPELINE_FAILED, layer = CaptureLayer.COORDINATOR)
            return false
        }
    }

    suspend fun captureParsed(
        conversation: CapturedConversation,
        messages: List<CapturedMessage>,
        pendingAssetsByMessage: Map<Int, PendingAssetEntity> = emptyMap(),
        captureToken: Long = captureGeneration(),
    ): CapturePersistResult = try {
            enqueueParsed(conversation, messages, pendingAssetsByMessage, captureToken).also {
                CaptureTrace.record(CaptureStage.PERSIST_RESULT, value = it.ordinal, layer = CaptureLayer.COORDINATOR)
            }
        } catch (_: Exception) {
            internalFailureCount.incrementAndGet()
            CaptureTrace.record(CaptureStage.PIPELINE_FAILED, layer = CaptureLayer.COORDINATOR)
            CapturePersistResult.FAILED
        }

    private suspend fun enqueueParsed(
        conversation: CapturedConversation,
        rawMessages: List<CapturedMessage>,
        capturedAssets: Map<Int, PendingAssetEntity>,
        captureToken: Long = captureGeneration(),
    ): CapturePersistResult {
        if (!captureAllowed() || captureGeneration() != captureToken) return CapturePersistResult.FAILED
        if (conversation.identityConfidence < MIN_IDENTITY_CONFIDENCE &&
            !isIsolatedPendingScreenshot(conversation, rawMessages, capturedAssets) &&
            !isPendingNotification(conversation, rawMessages) &&
            !isPendingNotificationScreenshot(conversation, rawMessages, capturedAssets)) return CapturePersistResult.FAILED
        val conversationKey = conversation.stableKeyOrNull() ?: return CapturePersistResult.FAILED
        var insertedAny = false
        var persistableAny = false
        for ((index, rawMessage) in rawMessages.withIndex()) {
            if (!captureAllowed() || captureGeneration() != captureToken) return CapturePersistResult.FAILED
            if (!CollectionConsent.allowsText(rawMessage.text) || rawMessage.metadata.values.any { !CollectionConsent.allowsText(it) }) continue
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
            val previousKey = message.metadata["conversation_identity_previous_key"]?.takeIf {
                isConfirmedScreenshot(conversation, message) && it.matches(Regex("(?:screenshot-v2|capture-v3):pending:[a-f0-9-]{36}"))
            }
            // 已保存的第一张确认重放必须使用原指纹；归属走新身份，但不能重复插图或等待已清理的原图。
            val fingerprintMessage = if (previousKey == null) message else message.copy(
                conversationKey = conversation.copy(externalKey = previousKey).stableKeyOrNull(),
                senderKey = if (message.senderKey.startsWith("${conversation.externalKey}:"))
                    previousKey + message.senderKey.removePrefix(conversation.externalKey.orEmpty()) else message.senderKey,
            )
            val fingerprint = messageFingerprint(fingerprintMessage) ?: continue
            persistableAny = true
            val capturedAt = clock()
            val pending = pendingMessage(conversation, message, fingerprint, capturedAt)
            if (store.enqueueIfNew(
                    SeenMessageEntity(fingerprint, capturedAt),
                    pending,
                    listOfNotNull(asset),
                )
            ) {
                insertedAny = true
            } else if (isConfirmedScreenshot(conversation, message)) {
                // 复用原图片指纹重放，仅更新新会话的确认名。服务端先更新会话再去重，不新增图片。
                // 使用独立的已见标识，确认补传有持久化重试且每个确认结果最多入队一次。
                val confirmationKey = "identity:" + sha256("$fingerprint|${conversation.displayName}|${conversation.identityConfidence}".toByteArray(Charsets.UTF_8))
                // 原图仍在队列时会自动等待；已上传时不要重新入队可能已清理的缓存文件。
                if (store.enqueueIfNew(SeenMessageEntity(confirmationKey, capturedAt), pending, emptyList())) insertedAny = true
            }
        }
        if (insertedAny) wakeUploader()
        return when {
            insertedAny -> CapturePersistResult.INSERTED
            persistableAny -> CapturePersistResult.ALREADY_PERSISTED
            else -> CapturePersistResult.FAILED
        }
    }

    private fun isConfirmedScreenshot(conversation: CapturedConversation, message: CapturedMessage): Boolean =
        conversation.identityConfidence >= 0.8 &&
            (conversation.externalKey.orEmpty().startsWith("capture-v3:") || conversation.externalKey.orEmpty().startsWith("screenshot-v2:")) &&
            message.direction == ChatDirection.SYSTEM && message.messageType == ChatMessageType.IMAGE &&
            message.metadata["conversation_identity_status"] == "confirmed"

    // 待确认例外只对新标识和对应平台的明确来源开放，不放宽任意低置信度数据。
    private fun isIsolatedPendingScreenshot(
        conversation: CapturedConversation,
        messages: List<CapturedMessage>,
        assets: Map<Int, PendingAssetEntity>,
    ): Boolean {
        val key = conversation.externalKey.orEmpty()
        val oldWechat = conversation.platform == ChatPlatform.WECHAT && conversation.accountKey == "wechat-empty-tree" &&
            key.matches(Regex("screenshot-(?:v2:(?:[a-f0-9]{64}|pending:[a-f0-9-]{36})|pending:[a-f0-9-]{36})"))
        val common = conversation.accountKey == "${conversation.platform.wireName}-local" &&
            key.matches(Regex("capture-(?:v3:(?:[a-f0-9]{64}|pending:[a-f0-9-]{36})|pending:[a-f0-9-]{36})"))
        if (!oldWechat && !common) return false
        val source = if (oldWechat) "wechat_empty_tree_screenshot" else "${conversation.platform.wireName}_screenshot"
        return messages.isNotEmpty() && messages.withIndex().all { (index, message) ->
            message.direction == ChatDirection.SYSTEM && message.messageType == ChatMessageType.IMAGE &&
                message.text.isNullOrBlank() && assets[index] != null &&
                message.metadata["capture_source"] == source && message.metadata["conversation_identity_status"] == "pending"
        }
    }

    private fun isPendingNotification(conversation: CapturedConversation, messages: List<CapturedMessage>): Boolean =
        conversation.accountKey == "notification" && conversation.externalKey.orEmpty().matches(Regex("notification-v2:pending:[a-f0-9]{64}")) &&
            messages.isNotEmpty() && messages.all { message ->
                message.direction == ChatDirection.INCOMING && !message.text.isNullOrBlank() &&
                    message.metadata["capture_source"] == "notification" &&
                    message.metadata["conversation_identity_status"] == "pending" &&
                    !message.metadata["notification_key"].isNullOrBlank()
            }

    private fun isPendingNotificationScreenshot(
        conversation: CapturedConversation, messages: List<CapturedMessage>, assets: Map<Int, PendingAssetEntity>,
    ): Boolean {
        val packageName = when (conversation.platform) {
            ChatPlatform.WECHAT -> "com.tencent.mm"
            ChatPlatform.DOUYIN -> "com.ss.android.ugc.aweme"
            ChatPlatform.QQ -> "com.tencent.mobileqq"
        }
        return conversation.accountKey == "notification-screenshot" &&
            conversation.externalKey.orEmpty().matches(Regex("notification-fallback-v2:[a-f0-9]{64}")) &&
            messages.isNotEmpty() && messages.withIndex().all { (index, message) ->
                assets[index] != null && message.messageType == ChatMessageType.IMAGE && message.direction == ChatDirection.INCOMING &&
                    message.metadata["capture_source"] == "notification_screenshot_fallback" &&
                    message.metadata["source_package"] == packageName &&
                    message.metadata["conversation_identity_status"] == "pending" && !message.metadata["notification_key"].isNullOrBlank()
            }
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
