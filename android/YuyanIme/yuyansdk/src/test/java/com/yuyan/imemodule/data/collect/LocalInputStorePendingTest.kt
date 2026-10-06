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
