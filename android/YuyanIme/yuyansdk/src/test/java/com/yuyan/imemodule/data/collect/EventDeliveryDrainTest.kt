package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CancellationException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventDeliveryDrainTest {
    private fun fixture(block: (LocalInputStore, MockWebServer, String, EventDelivery) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "drain-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val server = MockWebServer().apply { start() }
        val target = server.url("/").toString().trimEnd('/')
        try { block(store, server, target, EventDelivery(store, OkHttpClient(), "device", "{}")) }
        finally { server.shutdown(); store.close(); context.deleteDatabase(name) }
    }
    private fun seed(store: LocalInputStore, target: String, count: Int) {
        // Two assets exceed the existing 262144-character batch budget.
        repeat(count) { store.enqueueReport(PendingReport("asset-$it", "chat_asset", "{\"file_base64\":\"${"A".repeat(150000)}\"}"), listOf(target, "other")) }
    }
    private fun ok(server: MockWebServer, count: Int) { repeat(count) { server.enqueue(MockResponse().setBody("{\"ok\":true}")) } }

    @Test fun `同次唤醒连续补传但保留单批内存上限和另一端待传`() = fixture { store, server, target, sender ->
        seed(store, target, 3); assertEquals(1, store.pendingReports(target).size); ok(server, 4)
        assertTrue(sender.drain(target))
        assertTrue(store.pendingReports(target).isEmpty())
        assertFalse(store.pendingReports("other").isEmpty())
        assertEquals(4, server.requestCount)
    }
    @Test fun `达到批数上限保留剩余并在下一次继续`() = fixture { store, server, target, sender ->
        seed(store, target, 3); ok(server, 4)
        assertTrue(sender.drain(target, maxBatches = 2))
        assertEquals("asset-2", store.pendingReports(target).single().id)
        assertEquals(3, server.requestCount)
        assertTrue(sender.drain(target)); assertTrue(store.pendingReports(target).isEmpty())
    }
    @Test fun `失败立即结束本次连续补传且恢复后自动重试`() = fixture { store, server, target, sender ->
        seed(store, target, 2); ok(server, 1); server.enqueue(MockResponse().setResponseCode(503))
        assertFalse(sender.drain(target)); assertEquals(2, server.requestCount)
        ok(server, 3)
        assertTrue(sender.drain(target)); assertTrue(store.pendingReports(target).isEmpty())
    }
    @Test fun `达到五秒预算不再启动下一批`() = fixture { store, server, target, sender ->
        seed(store, target, 3)
        val clock = java.util.concurrent.atomic.AtomicLong()
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                if (request.path == "/api/v1/mobile/chat/assets") clock.addAndGet(3000)
                return MockResponse().setBody("{\"ok\":true}")
            }
        }
        assertTrue(sender.drain(target, nowMillis = { clock.get() }))
        assertEquals(3, server.requestCount)
        assertEquals("asset-2", store.pendingReports(target).single().id)
    }
    @Test fun `取消在下一批开始前传播且未发数据保留`() = fixture { store, server, target, sender ->
        seed(store, target, 3); ok(server, 4)
        var batches = 0
        assertThrows(CancellationException::class.java) {
            sender.drain(target, beforeBatch = { if (++batches == 2) throw CancellationException() })
        }
        assertEquals(2, server.requestCount)
        assertEquals("asset-1", store.pendingReports(target).single().id)
        assertTrue(sender.drain(target)); assertTrue(store.pendingReports(target).isEmpty())
    }
    @Test fun `空队列无确认进展时退出不重复扫描一百次`() = fixture { _, server, target, sender ->
        ok(server, 1); var batches = 0
        assertTrue(sender.drain(target, beforeBatch = { batches++ }))
        assertEquals(1, batches); assertEquals(1, server.requestCount)
    }
}
