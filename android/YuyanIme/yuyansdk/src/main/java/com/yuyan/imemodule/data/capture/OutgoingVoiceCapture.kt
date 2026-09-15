package com.yuyan.imemodule.data.capture

import android.content.Context
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.net.CaptureUploader
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

data class OutgoingVoiceCapturePayload(
    val conversation: CapturedConversation,
    val message: CapturedMessage,
)

internal fun buildOutgoingVoiceCapture(
    packageName: String?,
    transcript: String,
    occurredAtMillis: Long,
): OutgoingVoiceCapturePayload? {
    if (packageName != WECHAT_PACKAGE) return null
    val text = normalizeCapturedText(transcript).takeIf(String::isNotEmpty) ?: return null
    val occurredAt = voiceIsoTimestamp(occurredAtMillis)
    return OutgoingVoiceCapturePayload(
        conversation = CapturedConversation(
            platform = ChatPlatform.WECHAT,
            accountKey = "notification-screenshot",
            externalKey = "wechat-hidden-notification",
            displayName = "微信（截图兜底）",
            conversationType = ConversationType.UNKNOWN,
            identityConfidence = 0.8,
        ),
        message = CapturedMessage(
            conversationKey = null,
            senderKey = "self",
            senderName = "用户",
            direction = ChatDirection.OUTGOING,
            messageType = ChatMessageType.TEXT,
            text = text,
            displayedTime = occurredAt,
            occurredAt = occurredAt,
            metadata = mapOf(
                "capture_source" to "ime_voice_input",
                "input_mode" to "voice",
                "delivery_status" to "transcribed_not_send_confirmed",
            ),
        ),
    )
}

object OutgoingVoiceCapture {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun record(context: Context, packageName: String?, transcript: String, occurredAtMillis: Long = System.currentTimeMillis()) {
        if (!CollectionConsent.enabled(context) || !CollectionConsent.allowsText(transcript)) return
        val payload = buildOutgoingVoiceCapture(packageName, transcript, occurredAtMillis) ?: return
        val app = context.applicationContext
        scope.launch {
            val database = CaptureDatabase.create(app)
            try {
                CaptureUploader.start(app)
                CaptureCoordinator(
                    store = RoomCaptureOutboxStore(database.captureDao()),
                    deviceId = { DataCollector.deviceId(app) },
                    wakeUploader = CaptureUploader::wake,
                    captureAllowed = { CollectionConsent.enabled(app) },
                ).captureParsed(payload.conversation, listOf(payload.message))
            } finally {
                database.close()
            }
        }
    }
}

private fun voiceIsoTimestamp(milliseconds: Long): String = SimpleDateFormat(
    "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
    Locale.US,
).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(milliseconds))

private const val WECHAT_PACKAGE = "com.tencent.mm"
