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
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PacketNotificationTitleTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private fun parse(conversation: String, title: String = "我自己的", group: Boolean? = null,
                      text: String = "成员: [微信红包]恭喜发财"): PacketRequest? {
        val notification = Notification.Builder(context, "chat")
            .setContentTitle(title).setContentText(text)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent("packet-title-test"), PendingIntent.FLAG_IMMUTABLE))
            .build()
        notification.extras.putCharSequence(Notification.EXTRA_CONVERSATION_TITLE, conversation)
        group?.let { notification.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, it) }
        val uid = 128 * 100_000 + 10001
        val sbn = StatusBarNotification("com.tencent.mm", "com.tencent.mm", 1, null, uid, 0, 0,
            notification, UserHandle.getUserHandleForUid(uid), 1000)
        return packetNotification(sbn, 1100)
    }
    @Test fun emptyConversationTitleFallsBackToValidTitle() {
        assertEquals("我自己的", parse("")?.candidate?.chatName)
    }
    @Test fun whitespaceConversationTitleFallsBackToValidTitle() {
        assertEquals("我自己的", parse(" \t\n ")?.candidate?.chatName)
    }
    @Test fun nonblankConversationTitleRemainsAuthoritative() {
        assertEquals("另一个群", parse("另一个群")?.candidate?.chatName)
        assertNull(parse("微信"))
    }
    @Test fun missingUsableTitlesRemainRejected() {
        assertNull(parse(" ", title = "\t"))
    }
    @Test fun titleFallbackDoesNotRelaxGroupOrTextPolicy() {
        assertNull(parse(" ", group = false))
        assertNull(parse(" ", text = "成员: 普通文字"))
    }
}
