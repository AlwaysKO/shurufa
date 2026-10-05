package com.yuyan.imemodule.data.redpacket

import android.app.Notification
import android.app.PendingIntent
import android.service.notification.StatusBarNotification

internal data class PacketRequest(
    val candidate: PacketCandidate,
    val intent: PendingIntent,
    val postedAt: Long,
    val deferredByGame: Boolean = false,
) {
    // 本地暂存上限；不表示微信保证红包在此期间仍可领取。
    fun isValidAt(now: Long): Boolean = now - postedAt in
        0..if (deferredByGame) 24 * 60 * 60_000L else PACKET_NOTICE_MAX_AGE
}

internal fun packetNotification(sbn: StatusBarNotification, now: Long): PacketRequest? {
    if (android.os.Build.VERSION.SDK_INT < 28 || sbn.packageName != PACKET_WECHAT) return null
    val notification = sbn.notification
    if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
    val intent = notification.contentIntent ?: return null
    val extras = notification.extras
    val message = extras.getParcelableArray(Notification.EXTRA_MESSAGES)?.let {
        Notification.MessagingStyle.Message.getMessagesFromBundleArray(it).maxByOrNull { message -> message.timestamp }
    }
    val title = extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE)?.toString()
        ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
    val text = sequenceOf(
        message?.text,
        extras.getCharSequence(Notification.EXTRA_TEXT),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull { it.isNotBlank() },
    ).firstOrNull { !it.isNullOrBlank() }?.toString().orEmpty()
    val group = if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION))
        extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION) else null
    val postedAt = message?.timestamp?.takeIf { it > 0 } ?: sbn.postTime
    val notice = PacketNotice(sbn.packageName, sbn.key, title, text, postedAt, group)
    return groupPacketCandidate(notice, now)?.let { PacketRequest(it, intent, postedAt) }
}
