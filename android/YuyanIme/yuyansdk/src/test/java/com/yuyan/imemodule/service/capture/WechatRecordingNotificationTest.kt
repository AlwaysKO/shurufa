package com.yuyan.imemodule.service.capture

import android.app.Notification
import android.os.Process
import android.service.notification.StatusBarNotification
import com.yuyan.imemodule.data.callrecording.WechatCallSignals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class WechatRecordingNotificationTest {
    @Test fun callEvidenceReachesWorkerWithoutDependingOnChatCaptureInitialization() =
        exerciseRemoval { listener, notification -> listener.onNotificationRemoved(notification, null, 1) }

    @Test fun legacySingleArgumentRemovalEndsTheCall() =
        exerciseRemoval { listener, notification -> listener.onNotificationRemoved(notification) }

    @Test fun legacyRankingRemovalEndsTheCall() =
        exerciseRemoval { listener, notification -> listener.onNotificationRemoved(notification, null) }

    private fun exerciseRemoval(remove: (PassiveNotificationListener, StatusBarNotification) -> Unit) {
        val listener = Robolectric.buildService(PassiveNotificationListener::class.java).get()
        val observed = LinkedBlockingQueue<Pair<Boolean, String>>()
        val observedThread = LinkedBlockingQueue<Thread>()
        val caller = Thread.currentThread()
        val connection = WechatCallSignals.connect { active, source ->
            observedThread.add(Thread.currentThread())
            observed.add(active to source)
        }
        try {
            val chatMessage = Notification().apply {
                flags = Notification.FLAG_ONGOING_EVENT
                extras.putCharSequence(Notification.EXTRA_TEXT, "视频通话中")
                extras.putString(Notification.EXTRA_TEMPLATE, "android.app.Notification\$MessagingStyle")
            }
            listener.onNotificationPosted(StatusBarNotification("com.tencent.mm", "com.tencent.mm", 8, null,
                0, 0, 0, chatMessage, Process.myUserHandle(), System.currentTimeMillis()))
            val notification = Notification().apply {
                flags = Notification.FLAG_ONGOING_EVENT
                extras.putCharSequence(Notification.EXTRA_TITLE, "联系人不会进入录音状态")
                extras.putCharSequence(Notification.EXTRA_TEXT, "语音通话中")
            }
            val sbn = StatusBarNotification("com.tencent.mm", "com.tencent.mm", 9, null,
                0, 0, 0, notification, Process.myUserHandle(), System.currentTimeMillis())
            listener.onNotificationPosted(sbn)
            assertEquals(true to "wechat_voice", observed.poll(2, TimeUnit.SECONDS))
            assertNotSame(caller, observedThread.poll(2, TimeUnit.SECONDS))
            remove(listener, sbn)
            assertEquals(false to "wechat_voice", observed.poll(2, TimeUnit.SECONDS))
        } finally {
            connection.close()
            WechatCallSignals.clear()
            listener.javaClass.getDeclaredField("scope").apply { isAccessible = true }.let {
                (it.get(listener) as CoroutineScope).cancel()
            }
            listener.javaClass.getDeclaredField("mediaDispatcher").apply { isAccessible = true }.let {
                (it.get(listener) as ExecutorCoroutineDispatcher).close()
            }
        }
    }
}
