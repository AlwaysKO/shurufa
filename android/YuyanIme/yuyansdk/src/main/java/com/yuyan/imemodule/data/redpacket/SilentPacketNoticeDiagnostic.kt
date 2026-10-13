package com.yuyan.imemodule.data.redpacket

import android.app.Notification
import android.app.PendingIntent
import android.os.Build
import android.service.notification.StatusBarNotification
import com.yuyan.imemodule.BuildConfig

internal enum class SilentPacketNoticeDiagnosticReason {
    DEBUG_ONLY, SYSTEM_UNSUPPORTED, INVALID_SCOPE, INVALID_ARM_TIME, WRONG_PACKAGE, INVALID_UID,
    PROFILE_MISMATCH, BEFORE_ARM, FUTURE_NOTIFICATION, STALE_NOTIFICATION, GROUP_SUMMARY,
    MISSING_EXTRAS, PRIVATE_CONVERSATION, MISSING_INTENT, NON_ACTIVITY_INTENT,
    CREATOR_PACKAGE_MISMATCH, CREATOR_UID_MISMATCH, TITLE_INVALID, GROUP_NOT_ALLOWED,
    PACKET_EXCLUDED, READ_FAILED, ACCEPTED, WAIT_CANCELLED, WAIT_EXPIRED,
    FOREGROUND_UNKNOWN, GENERATION_MISMATCH, ALREADY_ACCEPTED,
}

internal class SilentPacketNoticeDiagnosticLogBudget(private val limit: Int = 64) {
    private val counts = LongArray(SilentPacketNoticeDiagnosticReason.entries.size)
    private var emitted = 0
    @Synchronized fun record(reason: SilentPacketNoticeDiagnosticReason): String? {
        if (emitted >= limit.coerceIn(0, 64)) return null
        emitted++
        counts[reason.ordinal]++
        return "NOTICE_READ_ONLY reason=${reason.name} count=${counts[reason.ordinal]}"
    }
}
internal val silentPacketNoticeDiagnosticLog = SilentPacketNoticeDiagnosticLogBudget()

/** 仅调试版使用新普通群通知的原入口做只读检查，不创建红包请求或改变红包识别规则。 */
internal fun silentPacketDiagnosticNotice(sbn: StatusBarNotification, selectedUser: Int,
    groups: Set<String>, armedAt: Long, now: Long,
    onDecision: (SilentPacketNoticeDiagnosticReason) -> Unit = {}): PendingIntent? {
    fun decision(reason: SilentPacketNoticeDiagnosticReason) {
        try { onDecision(reason) } catch (_: Exception) { }
    }
    fun reject(reason: SilentPacketNoticeDiagnosticReason): PendingIntent? { decision(reason); return null }
    // 与真实副屏后端使用相同版本边界，也避免旧版没有公开isActivity校验的情况。
    if (!BuildConfig.DEBUG) return reject(SilentPacketNoticeDiagnosticReason.DEBUG_ONLY)
    if (Build.VERSION.SDK_INT < 35) return reject(SilentPacketNoticeDiagnosticReason.SYSTEM_UNSUPPORTED)
    if (selectedUser < 0 || groups.isEmpty()) return reject(SilentPacketNoticeDiagnosticReason.INVALID_SCOPE)
    if (armedAt < 0 || now < armedAt) return reject(SilentPacketNoticeDiagnosticReason.INVALID_ARM_TIME)
    if (sbn.packageName != PACKET_WECHAT) return reject(SilentPacketNoticeDiagnosticReason.WRONG_PACKAGE)
    if (sbn.uid < 10000) return reject(SilentPacketNoticeDiagnosticReason.INVALID_UID)
    if (sbn.uid / 100_000 != selectedUser) return reject(SilentPacketNoticeDiagnosticReason.PROFILE_MISMATCH)
    if (sbn.postTime < armedAt) return reject(SilentPacketNoticeDiagnosticReason.BEFORE_ARM)
    if (sbn.postTime > now) return reject(SilentPacketNoticeDiagnosticReason.FUTURE_NOTIFICATION)
    if (now - sbn.postTime > PACKET_NOTICE_MAX_AGE) return reject(SilentPacketNoticeDiagnosticReason.STALE_NOTIFICATION)
    return try {
        val notification = sbn.notification
        if (notification.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            return reject(SilentPacketNoticeDiagnosticReason.GROUP_SUMMARY)
        val extras = notification.extras ?: return reject(SilentPacketNoticeDiagnosticReason.MISSING_EXTRAS)
        if (extras.containsKey(Notification.EXTRA_IS_GROUP_CONVERSATION) &&
            !extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION))
            return reject(SilentPacketNoticeDiagnosticReason.PRIVATE_CONVERSATION)
        val intent = notification.contentIntent ?: return reject(SilentPacketNoticeDiagnosticReason.MISSING_INTENT)
        if (!intent.isActivity) return reject(SilentPacketNoticeDiagnosticReason.NON_ACTIVITY_INTENT)
        if (intent.creatorPackage != PACKET_WECHAT) return reject(SilentPacketNoticeDiagnosticReason.CREATOR_PACKAGE_MISMATCH)
        if (intent.creatorUid != sbn.uid) return reject(SilentPacketNoticeDiagnosticReason.CREATOR_UID_MISMATCH)
        fun validTitle(value: CharSequence?): String? = value?.toString()?.trim()?.takeIf {
            it.isNotEmpty() && it !in setOf("微信", "WeChat") && !it.contains('…') && !it.endsWith("...")
        }
        val title = validTitle(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE))
            ?: validTitle(extras.getCharSequence(Notification.EXTRA_TITLE))
            ?: return reject(SilentPacketNoticeDiagnosticReason.TITLE_INVALID)
        if (title !in groups) return reject(SilentPacketNoticeDiagnosticReason.GROUP_NOT_ALLOWED)
        // 解析失败也不把异常数据当成普通消息；已识别红包绝不进入此只读通道。
        if (packetNotification(sbn, now) != null) return reject(SilentPacketNoticeDiagnosticReason.PACKET_EXCLUDED)
        decision(SilentPacketNoticeDiagnosticReason.ACCEPTED)
        intent
    } catch (_: Exception) { reject(SilentPacketNoticeDiagnosticReason.READ_FAILED) }
}
