package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LocalInputStorePendingTest {
    @Test fun typingMetadataCannotHidePreparedImagesBehindTheBatchLimit() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "typing-confirmation-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val target = "https://online"
        val hash = "a".repeat(64)
        try {
            repeat(20) { index ->
                store.enqueueReport(PendingReport("confirmation-$index", "chat_messages",
                    """{"messages":[{"asset_sha256":["$hash"]}]}"""), listOf(target))
            }
            store.enqueueReport(PendingReport("new-image", "chat_asset",
                """{"sha256":"${"b".repeat(64)}","file_base64":"AA=="}"""), listOf(target))
            val selection = CollectorUploadPlan { 0L }.select(true, false,
                store.deliverablePendingKinds(listOf(target)), false, true)
            assertEquals(listOf("new-image"), store.pendingReports(target, selection = selection).map { it.id })
            assertFalse(store.hasPendingImages("https://other"))
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun screenshotConfirmationFollowsImageReceiptWithoutWaitingForTextBatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "confirmation-delivery-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val target = "https://online"
        val hash = "a".repeat(64)
        val images = DeliverySelection(false, false, false, false, true, imageMessages = true)
        try {
            store.enqueueReport(PendingReport("asset", "chat_asset", """{"sha256":"$hash","file_base64":"AA=="}"""), listOf(target))
            store.enqueueReport(PendingReport("confirmation", "chat_messages", """{"messages":[{"message_type":"image","asset_sha256":["$hash"]}]}"""), listOf(target))
            store.enqueueReport(PendingReport("text", "chat_messages", """{"messages":[{"message_type":"text","asset_sha256":[]}]}"""), listOf(target))
            assertEquals(listOf("asset"), store.pendingReports(target, selection = images).map { it.id })
            store.acknowledgeReports(target, listOf("asset"), target)
            assertTrue("Image receipt must not strand its confirmation", store.hasPendingImages(target))
            assertEquals(listOf("confirmation"), store.pendingReports(target, selection = images).map { it.id })
            store.acknowledgeReports(target, listOf("confirmation"), target)
            assertFalse(store.hasPendingImages(target))
            assertEquals(listOf("text"), store.pendingReports(target).map { it.id })
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun imagePendingQuerySeparatesTargetsWithoutReadingOrDeletingPayloads() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "image-target-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val online = "https://online"
        val local = "http://127.0.0.1:3000"
        try {
            store.enqueueReport(PendingReport("local-image", "chat_asset", "{}"), listOf(local))
            store.writableDatabase.execSQL("UPDATE pending_report SET payload='not-json' WHERE id='local-image'")
            assertFalse(store.hasPendingImages(online))
            assertTrue(store.hasPendingImages(local))
            store.enqueueReport(PendingReport("online-image", "chat_asset", "{}"), listOf(online))
            assertTrue(store.hasPendingImages(online))
            store.acknowledgeReports(online, listOf("online-image"), online)
            assertFalse(store.hasPendingImages(online))
            assertTrue(store.hasPendingImages(local))
            assertEquals(setOf("chat_asset"), store.pendingKinds(listOf(local)))
        } finally { store.close(); context.deleteDatabase(name) }
    }

    @Test fun `轻量查询覆盖输入事件及报告直到全部目标确认`() {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="pending-${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name)
        try {
            assertFalse(store.hasPendingUploads())
            store.enqueue(MobileEvent("event","device","commit",occurredAt="2026-10-06T00:00:00Z"),listOf("https://online","http://local"))
            assertTrue(store.hasPendingUploads())
            store.acknowledge("https://online",listOf("event"));assertTrue(store.hasPendingUploads())
            store.acknowledge("http://local",listOf("event"));assertFalse(store.hasPendingUploads())
            store.enqueueReport(PendingReport("location","location","{}"),listOf("https://online"))
            assertTrue(store.hasPendingUploads())
            store.acknowledgeReports("https://online",listOf("location"));assertFalse(store.hasPendingUploads())
            // 检查只关心待传关系，不必解码体积大或损坏的载荷。
            store.enqueueReport(PendingReport("asset","chat_asset","{}"),listOf("https://online"))
            store.writableDatabase.execSQL("UPDATE pending_report SET payload='not-json' WHERE id='asset'")
            assertTrue(store.hasPendingUploads())
        }finally{store.close();context.deleteDatabase(name)}
    }
}
