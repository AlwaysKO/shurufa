package com.yuyan.imemodule.data.capture.notification

import com.yuyan.imemodule.data.capture.normalizeCapturedText
import com.yuyan.imemodule.data.capture.sha256
import com.yuyan.imemodule.data.capture.media.normalizeConversationTitle
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class NotificationSnapshot(
    val packageName: String,
    val notificationKey: String,
    val title: String?,
    val text: String?,
    val postedAtMillis: Long,
    val isGroupConversation: Boolean = false,
    val senderName: String? = null,
    val mediaUri: String? = null,
    val mediaUriReadable: Boolean = false,
    val summaryText: String? = null,
    val isMessagingStyle: Boolean = false,
    val sourceMessageTimestampMillis: Long? = null,
    // 只接受系统已判定为会话的通知关联 shortcut；普通通知ID不等于联系人ID。
    val stableConversationId: String? = null,
    val profileKey: String = "local-profile",
)

data class ParsedNotification(
    val conversation: CapturedConversation,
    val message: CapturedMessage,
    val mediaUri: String?,
)

class NotificationParser {
    fun shouldIgnore(snapshot: NotificationSnapshot): Boolean {
        if (snapshot.packageName != WECHAT_PACKAGE) return false
        val title = normalizeCapturedText(snapshot.title)
        val text = normalizeCapturedText(snapshot.text)
        return title == "微信" && WECHAT_DESKTOP_LOGIN.containsMatchIn(text)
    }

    fun requiresScreenshotFallback(snapshot: NotificationSnapshot): Boolean {
        if (snapshot.packageName !in SCREENSHOT_FALLBACK_PACKAGES || shouldIgnore(snapshot)) return false
        val title = normalizeCapturedText(snapshot.title)
        val texts = listOf(snapshot.summaryText, snapshot.text).map(::normalizeCapturedText)
        if (snapshot.isMessagingStyle || texts.none(String::isNotEmpty)) return false
        return when (snapshot.packageName) {
            WECHAT_PACKAGE -> title == "微信"
            DOUYIN_PACKAGE -> title == "抖音" && texts.any(DOUYIN_HIDDEN_MESSAGE::containsMatchIn)
            QQ_PACKAGE -> title == "QQ" && texts.any(DOUYIN_HIDDEN_MESSAGE::containsMatchIn)
            else -> false
        }
    }

    fun requiresMediaScreenshotFallback(snapshot: NotificationSnapshot): Boolean {
        if (snapshot.packageName !in SCREENSHOT_FALLBACK_PACKAGES || shouldIgnore(snapshot)) return false
        val text = normalizeCapturedText(snapshot.text)
        if (snapshot.packageName == WECHAT_PACKAGE && wechatCallMessageType(text) != null) return true
        if (snapshot.mediaUriReadable) return false
        return notificationMessageType(text) != ChatMessageType.TEXT
    }

    fun parse(snapshot: NotificationSnapshot): ParsedNotification? {
        val platform = PLATFORM_BY_PACKAGE[snapshot.packageName] ?: return null
        if (shouldIgnore(snapshot)) return null
        if (platform == ChatPlatform.DOUYIN && !snapshot.isMessagingStyle) return null
        val title = normalizeCapturedText(snapshot.title).takeIf(String::isNotEmpty) ?: return null
        val rawText = normalizeCapturedText(snapshot.text).takeIf(String::isNotEmpty) ?: return null
        if (requiresScreenshotFallback(snapshot)) return null
        val conversationType = if (snapshot.isGroupConversation) {
            ConversationType.GROUP
        } else {
            ConversationType.DIRECT
        }
        val explicitSender = normalizeCapturedText(snapshot.senderName).takeIf(String::isNotEmpty)
        val candidateTitle = if (snapshot.isMessagingStyle && !snapshot.isGroupConversation &&
            title in setOf("微信", "QQ", "抖音") && explicitSender != null) {
            explicitSender
        } else {
            title
        }
        val conversationTitle = normalizeConversationTitle(candidateTitle, platform) ?: return null
        val peerId = snapshot.stableConversationId?.trim()?.takeIf(String::isNotEmpty)
        val confirmed = peerId != null
        val confidence = if (confirmed) 0.9 else 0.55
        val (senderName, body) = if (snapshot.isGroupConversation && explicitSender != null) {
            explicitSender to rawText
        } else if (snapshot.isGroupConversation) {
            splitGroupMessage(rawText)
        } else {
            (explicitSender ?: conversationTitle) to rawText
        }
        val senderKey = stableNameKey(senderName ?: title)
        val readableMediaUri = snapshot.mediaUri?.takeIf { snapshot.mediaUriReadable }
        val hasMedia = snapshot.mediaUri != null
        val metadata = buildMap {
            put("capture_source", "notification")
            put("identity_confidence", confidence.toString())
            put("conversation_identity_status", if (confirmed) "confirmed" else "pending")
            put("identity_unavailable", (!confirmed).toString())
            put("conversation_identity_source", if (confirmed) "notification_shortcut" else "notification_title_unverified")
            put("conversation_identity_observed_title", conversationTitle)
            put("notification_key", snapshot.notificationKey)
            snapshot.sourceMessageTimestampMillis?.let { put("notification_message_timestamp", it.toString()) }
            if (hasMedia) {
                put("notification_media_readable", (readableMediaUri != null).toString())
                if (readableMediaUri == null) put("asset_capture_failed", "true")
            }
        }
        val identityParts = listOf(platform.wireName, snapshot.profileKey) +
            if (confirmed) listOf("peer", peerId.orEmpty()) else listOf("pending", snapshot.notificationKey, conversationTitle)
        val externalKey = "notification-v2:${if (confirmed) "peer" else "pending"}:" + sha256(
            identityParts.joinToString("|") { "${it.length}:$it" }.toByteArray(Charsets.UTF_8),
        )

        return ParsedNotification(
            conversation = CapturedConversation(
                platform = platform,
                accountKey = NOTIFICATION_ACCOUNT_KEY,
                externalKey = externalKey,
                displayName = if (confirmed) conversationTitle else "待确认通知（$conversationTitle）",
                conversationType = conversationType,
                identityConfidence = confidence,
            ),
            message = CapturedMessage(
                conversationKey = null,
                senderKey = senderKey,
                senderName = senderName,
                direction = ChatDirection.INCOMING,
                messageType = notificationMessageType(body).let { classified ->
                    if (classified == ChatMessageType.TEXT && hasMedia) ChatMessageType.IMAGE else classified
                },
                text = body,
                displayedTime = isoTimestamp(snapshot.postedAtMillis),
                occurredAt = isoTimestamp(snapshot.postedAtMillis),
                metadata = metadata,
            ),
            mediaUri = readableMediaUri,
        )
    }

    private fun splitGroupMessage(text: String): Pair<String?, String> {
        val separator = text.indexOfFirst { it == '：' || it == ':' }
        if (separator <= 0 || separator == text.lastIndex) return null to text
        val sender = text.substring(0, separator).trim().takeIf(String::isNotEmpty)
        val body = text.substring(separator + 1).trim().takeIf(String::isNotEmpty) ?: text
        return sender to body
    }

    private fun stableNameKey(name: String): String =
        "notification:" + sha256(normalizeCapturedText(name).toByteArray(Charsets.UTF_8))

    private fun notificationMessageType(text: String): ChatMessageType = when {
        wechatCallMessageType(text) != null -> requireNotNull(wechatCallMessageType(text))
        text.startsWithAny("[语音]", "【语音】") -> ChatMessageType.VOICE
        text.startsWithAny("[图片]", "【图片】") -> ChatMessageType.IMAGE
        text.startsWithAny("[表情]", "【表情】", "[动画表情]", "【动画表情】") -> ChatMessageType.STICKER
        text.startsWithAny("[视频]", "【视频】", "[视频号]", "【视频号】") -> ChatMessageType.VIDEO
        text.startsWithAny("[文件]", "【文件】") -> ChatMessageType.FILE
        text.startsWithAny("[链接]", "【链接】") -> ChatMessageType.LINK
        text.startsWithAny("[位置]", "【位置】") -> ChatMessageType.LOCATION
        text.startsWithAny("[名片]", "【名片】") -> ChatMessageType.CONTACT
        text.startsWithAny("[小程序]", "【小程序】") -> ChatMessageType.MINI_APP
        text.startsWithAny("[红包]", "【红包】") -> ChatMessageType.RED_PACKET
        text.startsWithAny("[转账]", "【转账】") -> ChatMessageType.TRANSFER
        else -> ChatMessageType.TEXT
    }

    private fun String.startsWithAny(vararg prefixes: String): Boolean =
        prefixes.any { startsWith(it, ignoreCase = true) }

    private fun wechatCallMessageType(text: String): ChatMessageType? = when {
        text.startsWith("视频通话") -> ChatMessageType.VIDEO
        text.startsWith("语音通话") -> ChatMessageType.VOICE
        else -> null
    }

    private fun isoTimestamp(milliseconds: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(milliseconds))

    private companion object {
        const val NOTIFICATION_ACCOUNT_KEY = "notification"
        const val WECHAT_PACKAGE = "com.tencent.mm"
        const val QQ_PACKAGE = "com.tencent.mobileqq"
        const val DOUYIN_PACKAGE = "com.ss.android.ugc.aweme"
        val SCREENSHOT_FALLBACK_PACKAGES = setOf(WECHAT_PACKAGE, QQ_PACKAGE, DOUYIN_PACKAGE)
        val WECHAT_DESKTOP_LOGIN = Regex("登录\\s*(Windows|Mac)\\s*微信", RegexOption.IGNORE_CASE)
        val DOUYIN_HIDDEN_MESSAGE = Regex("(收到|发来|发了|有).{0,12}(新消息|消息|私信)|\\d+\\s*条\\s*(新消息|私信)")
        val PLATFORM_BY_PACKAGE = mapOf(
            WECHAT_PACKAGE to ChatPlatform.WECHAT,
            "com.tencent.mobileqq" to ChatPlatform.QQ,
            DOUYIN_PACKAGE to ChatPlatform.DOUYIN,
        )
    }
}
