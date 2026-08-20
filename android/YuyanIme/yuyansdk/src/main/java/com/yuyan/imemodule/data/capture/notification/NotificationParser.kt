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
)

data class ParsedNotification(
    val conversation: CapturedConversation,
    val message: CapturedMessage,
    val mediaUri: String?,
)

class NotificationParser {
    fun parse(snapshot: NotificationSnapshot): ParsedNotification? {
        val platform = PLATFORM_BY_PACKAGE[snapshot.packageName] ?: return null
        val title = normalizeCapturedText(snapshot.title).takeIf(String::isNotEmpty) ?: return null
        val rawText = normalizeCapturedText(snapshot.text).takeIf(String::isNotEmpty) ?: return null
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
                messageType = if (hasMedia) ChatMessageType.IMAGE else ChatMessageType.TEXT,
                text = body,
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

    private fun isoTimestamp(milliseconds: Long): String = SimpleDateFormat(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        Locale.US,
    ).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(milliseconds))

    private companion object {
        const val NOTIFICATION_ACCOUNT_KEY = "notification"
        const val NOTIFICATION_IDENTITY_CONFIDENCE = 0.8
        val PLATFORM_BY_PACKAGE = mapOf(
            "com.tencent.mm" to ChatPlatform.WECHAT,
            "com.tencent.mobileqq" to ChatPlatform.QQ,
            "com.ss.android.ugc.aweme" to ChatPlatform.DOUYIN,
        )
    }
}
