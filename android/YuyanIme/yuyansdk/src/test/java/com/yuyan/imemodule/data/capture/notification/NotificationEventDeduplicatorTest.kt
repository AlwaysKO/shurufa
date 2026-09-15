package com.yuyan.imemodule.data.capture.notification

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationEventDeduplicatorTest {
    private val deduplicator = NotificationEventDeduplicator()

    @Test
    fun repeatedStateUpdatesForSameActiveNotificationAreAcceptedOnce() {
        val snapshot = callSnapshot()

        assertTrue(deduplicator.shouldAccept(snapshot))
        assertFalse(deduplicator.shouldAccept(snapshot.copy(postedAtMillis = 2_000L)))
    }

    @Test
    fun separateMessagingMessagesWithSameTextUseTheirMessageTimestamp() {
        val first = callSnapshot().copy(
            text = "收到",
            isMessagingStyle = true,
            sourceMessageTimestampMillis = 1_000L,
        )
        val second = first.copy(sourceMessageTimestampMillis = 2_000L, postedAtMillis = 2_000L)

        assertTrue(deduplicator.shouldAccept(first))
        assertTrue(deduplicator.shouldAccept(second))
    }

    @Test
    fun notificationRemovalAllowsASeparateLaterCall() {
        val snapshot = callSnapshot()
        assertTrue(deduplicator.shouldAccept(snapshot))

        deduplicator.remove(snapshot.notificationKey)

        assertTrue(deduplicator.shouldAccept(snapshot.copy(postedAtMillis = 9_000L)))
    }

    private fun callSnapshot() = NotificationSnapshot(
        packageName = "com.tencent.mm",
        notificationKey = "wechat-call",
        title = "龚林莉",
        text = "视频通话中",
        postedAtMillis = 1_000L,
    )
}
