package com.yuyan.imemodule.data.capture.notification

import com.yuyan.imemodule.data.capture.normalizeCapturedText
import com.yuyan.imemodule.data.capture.sha256
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
)

data class ParsedNotification(
    val conversation: CapturedConversation,
    val message: CapturedMessage,
    val mediaUri: String?,
)

class NotificationParser {
    fun requiresScreenshotFallback(snapshot: NotificationSnapshot): Boolean {
        if (snapshot.packageName != WECHAT_PACKAGE) return false
        val title = normalizeCapturedText(snapshot.title)
        val texts = listOf(snapshot.summaryText, snapshot.text).map(::normalizeCapturedText)
        return title == "微信" && texts.any(WECHAT_AGGREGATE_SUMMARY::matches)
    }

    fun requiresMediaScreenshotFallback(snapshot: NotificationSnapshot): Boolean {
        if (snapshot.packageName != WECHAT_PACKAGE) return false
        val text = normalizeCapturedText(snapshot.text)
        if (wechatCallMessageType(text) != null) return true
        if (snapshot.mediaUriReadable) return false
        return notificationMessageType(text) != ChatMessageType.TEXT
    }

    fun parse(snapshot: NotificationSnapshot): ParsedNotification? {
        val platform = PLATFORM_BY_PACKAGE[snapshot.packageName] ?: return null
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
        val (senderName, body) = if (snapshot.isGroupConversation && explicitSender != null) {
            explicitSender to rawText
        } else if (snapshot.isGroupConversation) {
            splitGroupMessage(rawText)
        } else {
            title to rawText
        }
        val senderKey = stableNameKey(senderName ?: title)
        val readableMediaUri = snapshot.mediaUri?.takeIf { snapshot.mediaUriReadable }
        val hasMedia = snapshot.mediaUri != null
        val metadata = buildMap {
            put("capture_source", "notification")
            put("identity_confidence", NOTIFICATION_IDENTITY_CONFIDENCE.toString())
            put("notification_key", snapshot.notificationKey)
            snapshot.sourceMessageTimestampMillis?.let { put("notification_message_timestamp", it.toString()) }
            if (hasMedia) {
                put("notification_media_readable", (readableMediaUri != null).toString())
                if (readableMediaUri == null) put("asset_capture_failed", "true")
            }
        }
        val externalKey = "notification:" + sha256(
            "${platform.wireName}|$conversationType|$title".toByteArray(Charsets.UTF_8),
        )

        return ParsedNotification(
            conversation = CapturedConversation(
                platform = platform,
                accountKey = NOTIFICATION_ACCOUNT_KEY,
                externalKey = externalKey,
                displayName = title,
                conversationType = conversationType,
                identityConfidence = NOTIFICATION_IDENTITY_CONFIDENCE,
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
        const val NOTIFICATION_IDENTITY_CONFIDENCE = 0.8
        const val WECHAT_PACKAGE = "com.tencent.mm"
        val WECHAT_AGGREGATE_SUMMARY = Regex(
            "^(?:(?:[\\d一二三四五六七八九十]+)\\s*个联系人(?:给你)?发来(?:了)?\\s*(?:[\\d一二三四五六七八九十]+)\\s*条(?:新)?消息|你收到(?:了)?\\s*(?:[\\d一二三四五六七八九十]+)\\s*条(?:新)?消息)[。！!]?${'$'}",
        )
        val PLATFORM_BY_PACKAGE = mapOf(
            WECHAT_PACKAGE to ChatPlatform.WECHAT,
            "com.tencent.mobileqq" to ChatPlatform.QQ,
            "com.ss.android.ugc.aweme" to ChatPlatform.DOUYIN,
        )
    }
}
