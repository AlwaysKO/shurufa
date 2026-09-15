package com.yuyan.imemodule.data.capture.notification

import com.yuyan.imemodule.data.capture.normalizeCapturedText
import com.yuyan.imemodule.data.capture.sha256

/** 同一个仍在通知栏中的状态通知只接收一次；真实消息以消息时间区分。 */
class NotificationEventDeduplicator(
    private val maximumActiveNotifications: Int = 100,
) {
    private val signaturesByNotification = linkedMapOf<String, MutableSet<String>>()

    @Synchronized
    fun shouldAccept(snapshot: NotificationSnapshot): Boolean {
        val signatures = signaturesByNotification.getOrPut(snapshot.notificationKey) { linkedSetOf() }
        val accepted = signatures.add(signature(snapshot))
        while (signaturesByNotification.size > maximumActiveNotifications) {
            signaturesByNotification.remove(signaturesByNotification.keys.first())
        }
        return accepted
    }

    @Synchronized
    fun remove(notificationKey: String) {
        signaturesByNotification.remove(notificationKey)
    }

    private fun signature(snapshot: NotificationSnapshot): String {
        val text = normalizeCapturedText(snapshot.text)
        val eventIdentity = when {
            snapshot.packageName == WECHAT_PACKAGE && text.startsWith("视频通话") -> "wechat-video-call"
            snapshot.packageName == WECHAT_PACKAGE && text.startsWith("语音通话") -> "wechat-voice-call"
            snapshot.isMessagingStyle -> "message:${snapshot.sourceMessageTimestampMillis ?: snapshot.postedAtMillis}"
            else -> "notification:${snapshot.postedAtMillis}"
        }
        return sha256(listOf(
            snapshot.packageName,
            normalizeCapturedText(snapshot.title),
            normalizeCapturedText(snapshot.senderName),
            text,
            eventIdentity,
        ).joinToString("|").toByteArray(Charsets.UTF_8))
    }

    private companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}
