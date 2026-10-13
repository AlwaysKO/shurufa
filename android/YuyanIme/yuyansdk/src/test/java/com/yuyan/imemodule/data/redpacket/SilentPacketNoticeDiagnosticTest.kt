package com.yuyan.imemodule.data.redpacket

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SilentPacketNoticeDiagnosticTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private var serial = 0
    private fun notice(user: Int = 128, uid: Int = user * 100_000 + 10001, creatorUid: Int = uid,
        creatorPackage: String = PACKET_WECHAT, packageName: String = PACKET_WECHAT,
        activity: Boolean = true, title: String = "测试群", conversation: String? = null,
        group: Boolean? = true, summary: Boolean = false, postTime: Long = 1000,
        text: String = "成员: 普通新消息", withIntent: Boolean = true): StatusBarNotification {
        val request = ++serial
        val intent = if (activity) PendingIntent.getActivity(context, request, Intent("diagnostic-$request"), PendingIntent.FLAG_IMMUTABLE)
            else PendingIntent.getBroadcast(context, request, Intent("diagnostic-$request"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(intent).setCreatorPackage(creatorPackage)
        shadowOf(intent).setCreatorUid(creatorUid)
        val notification = Notification.Builder(context, "diagnostic").setContentTitle(title).setContentText(text)
            .setContentIntent(if (withIntent) intent else null).setGroup("diagnostic-group").setGroupSummary(summary).build()
        if (group != null) notification.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, group)
        if (conversation != null) notification.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, conversation)
        return StatusBarNotification(packageName, packageName, request, null, uid, 0, 0,
            notification, UserHandle.getUserHandleForUid(uid), postTime)
    }
    private fun inspect(sbn: StatusBarNotification, selected: Int = 128, groups: Set<String> = setOf("测试群"),
        armed: Long = 1000, now: Long = 1100) = silentPacketDiagnosticNotice(sbn, selected, groups, armed, now)

    @Test fun rejectionCallbackExplainsEachGateUsingOnlyOneFixedEnum() {
        val cases = listOf(
            notice(packageName = "unrelated.app") to SilentPacketNoticeDiagnosticReason.WRONG_PACKAGE,
            notice(uid = 9999, creatorUid = 9999) to SilentPacketNoticeDiagnosticReason.INVALID_UID,
            notice(user = 0) to SilentPacketNoticeDiagnosticReason.PROFILE_MISMATCH,
            notice(postTime = 999) to SilentPacketNoticeDiagnosticReason.BEFORE_ARM,
            notice(postTime = 1101) to SilentPacketNoticeDiagnosticReason.FUTURE_NOTIFICATION,
            notice(summary = true) to SilentPacketNoticeDiagnosticReason.GROUP_SUMMARY,
            notice(group = false) to SilentPacketNoticeDiagnosticReason.PRIVATE_CONVERSATION,
            notice(withIntent = false) to SilentPacketNoticeDiagnosticReason.MISSING_INTENT,
            notice(activity = false) to SilentPacketNoticeDiagnosticReason.NON_ACTIVITY_INTENT,
            notice(creatorPackage = "private.creator") to SilentPacketNoticeDiagnosticReason.CREATOR_PACKAGE_MISMATCH,
            notice(creatorUid = 128 * 100_000 + 10002) to SilentPacketNoticeDiagnosticReason.CREATOR_UID_MISMATCH,
            notice(title = "微信") to SilentPacketNoticeDiagnosticReason.TITLE_INVALID,
            notice(title = "不应输出的群名") to SilentPacketNoticeDiagnosticReason.GROUP_NOT_ALLOWED,
            notice(text = "成员: [微信红包]不应输出的正文") to SilentPacketNoticeDiagnosticReason.PACKET_EXCLUDED)
        for ((sbn, expected) in cases) {
            val decisions = mutableListOf<SilentPacketNoticeDiagnosticReason>()
            assertNull(silentPacketDiagnosticNotice(sbn, 128, setOf("测试群"), 1000, 1100, decisions::add))
            assertEquals(listOf(expected), decisions)
        }
        val decisions = mutableListOf<SilentPacketNoticeDiagnosticReason>()
        assertNull(silentPacketDiagnosticNotice(notice(), 128, setOf("测试群"), 1000, 16001, decisions::add))
        assertEquals(listOf(SilentPacketNoticeDiagnosticReason.STALE_NOTIFICATION), decisions)
    }
    @Test fun acceptedNoticeReportsOnceAndObserverFailureCannotChangeDecision() {
        val sbn = notice()
        val decisions = mutableListOf<SilentPacketNoticeDiagnosticReason>()
        assertSame(sbn.notification.contentIntent,
            silentPacketDiagnosticNotice(sbn, 128, setOf("测试群"), 1000, 1100, decisions::add))
        assertEquals(listOf(SilentPacketNoticeDiagnosticReason.ACCEPTED), decisions)
        assertSame(sbn.notification.contentIntent,
            silentPacketDiagnosticNotice(sbn, 128, setOf("测试群"), 1000, 1100) { throw IllegalStateException("private") })
    }
    @Test fun diagnosticLogHasFixedVocabularyPerReasonCountsAndFiniteOutputBudget() {
        val log = SilentPacketNoticeDiagnosticLogBudget(limit = 3)
        assertEquals("NOTICE_READ_ONLY reason=FOREGROUND_UNKNOWN count=1", log.record(SilentPacketNoticeDiagnosticReason.FOREGROUND_UNKNOWN))
        assertEquals("NOTICE_READ_ONLY reason=FOREGROUND_UNKNOWN count=2", log.record(SilentPacketNoticeDiagnosticReason.FOREGROUND_UNKNOWN))
        assertEquals("NOTICE_READ_ONLY reason=GROUP_NOT_ALLOWED count=1", log.record(SilentPacketNoticeDiagnosticReason.GROUP_NOT_ALLOWED))
        repeat(100) { assertNull(log.record(SilentPacketNoticeDiagnosticReason.ACCEPTED)) }
    }
    @Test fun freshOrdinaryGroupNoticeReturnsOnlyItsOriginalActivityIntent() {
        for (group in listOf(true, null)) {
            val sbn = notice(group = group)
            assertSame(sbn.notification.contentIntent, inspect(sbn))
        }
    }
    @Test fun selectedProfileAndCompleteCreatorUidMustAgreeWithNotification() {
        assertNull(inspect(notice(), selected = 0))
        assertNull(inspect(notice(), selected = -1))
        assertNull(inspect(notice(uid = 9999, creatorUid = 9999)))
        assertNull(inspect(notice(creatorUid = 128 * 100_000 + 10002)))
        assertNull(inspect(notice(creatorUid = 10001)))
        assertNull(inspect(notice(creatorPackage = "unrelated.app")))
    }
    @Test fun wrongPackagePrivateSummaryBroadcastOrMissingIntentAreRejected() {
        assertNull(inspect(notice(packageName = "unrelated.app")))
        assertNull(inspect(notice(group = false)))
        assertNull(inspect(notice(summary = true)))
        assertNull(inspect(notice(activity = false)))
        assertNull(inspect(notice(withIntent = false)))
    }
    @Test fun notificationMustBePostedAfterArmingAndWithinFifteenSeconds() {
        val sbn = notice()
        assertSame(sbn.notification.contentIntent, inspect(sbn, now = 16_000))
        assertNull(inspect(sbn, now = 16_001))
        assertNull(inspect(notice(postTime = 999)))
        assertNull(inspect(notice(postTime = 1101)))
        assertNull(inspect(sbn, armed = 1200))
    }
    @Test fun titleRequiresNonemptyExactWhitelistAndCompleteConversationName() {
        assertNull(inspect(notice(), groups = emptySet()))
        assertNull(inspect(notice(title = "测试群二")))
        for (title in listOf("", " ", "微信", "WeChat", "测试群…", "测试群..."))
            assertNull(inspect(notice(title = title), groups = setOf(title)))
    }
    @Test fun validConversationTitleWinsAndOnlyInvalidTitleFallsBack() {
        val preferred = notice(title = "其他群", conversation = "测试群")
        assertSame(preferred.notification.contentIntent, inspect(preferred))
        for (invalid in listOf("", " ", "微信", "WeChat", "测试群…")) {
            val fallback = notice(conversation = invalid)
            assertSame(fallback.notification.contentIntent, inspect(fallback))
        }
        assertNull(inspect(notice(conversation = "其他群")))
    }
    @Test fun packetNotificationsAreExcludedFromReadOnlyOrdinaryNoticeDiagnostic() {
        for (group in listOf(true, null)) {
            val sbn = notice(group = group, text = "成员: [微信红包]恭喜发财")
            assertNotNull(packetNotification(sbn, 1100))
            assertNull(inspect(sbn))
        }
    }
    @Test @Config(sdk = [30]) fun olderApiCannotUseDiagnosticNotice() {
        assertNull(inspect(notice()))
    }
}
