package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventDeliveryFairnessTest {
    private fun fixture(block: (LocalInputStore, MockWebServer, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "fair-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val server = MockWebServer().apply { start() }
        try { block(store, server, server.url("/").toString().trimEnd('/')) }
        finally { server.shutdown(); store.close(); context.deleteDatabase(name) }
    }
    @Test fun `事件接口失败不饿死独立聊天报告`() = fixture { store, server, target ->
        store.enqueue(MobileEvent("e", "device", "commit", occurredAt = "2026-09-22T00:00:00Z"), listOf(target))
        store.enqueueReport(PendingReport("message", "chat_messages", "{}"), listOf(target))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/api/v1/mobile/events/batch")
                MockResponse().setResponseCode(400) else MockResponse().setBody("{\"ok\":true}")
        }
        assertFalse(EventDelivery(store, OkHttpClient(), "device", "{}").drain(target))
        assertTrue(store.pendingReports(target).isEmpty())
        assertEquals(1, store.pending(target).size)
    }
    @Test fun `超大单个事件保留但不阻止聊天报告发送`() = fixture { store, server, target ->
        store.enqueue(MobileEvent("e", "device", "commit", text = "字".repeat(400000), occurredAt = "2026-09-22T00:00:00Z"), listOf(target))
        store.enqueueReport(PendingReport("message", "chat_messages", "{}"), listOf(target))
        repeat(2) { server.enqueue(MockResponse().setBody("{\"ok\":true}")) }
        assertFalse(EventDelivery(store, OkHttpClient(), "device", "{}").drain(target))
        assertTrue(store.pendingReports(target).isEmpty()); assertEquals(1, store.pending(target).size)
    }
    @Test fun `失败资产让位于未尝试的消息但仍然保留供重试`() = fixture { store, _, target ->
        store.enqueueReport(PendingReport("bad", "chat_asset", "{}"), listOf(target))
        store.enqueueReport(PendingReport("good", "chat_messages", "{}"), listOf(target))
        store.deferReport(target, "bad")
        assertEquals(listOf("good", "bad"), store.pendingReports(target).map { it.id })
    }
    @Test fun `五秒预算也在同批每个请求前检查`() = fixture { store, server, target ->
        repeat(3) { store.enqueueReport(PendingReport("m$it", "chat_messages", "{}"), listOf(target)) }
        val clock = AtomicLong()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/mobile/chat/messages/batch") clock.addAndGet(3000)
                return MockResponse().setBody("{\"ok\":true}")
            }
        }
        assertTrue(EventDelivery(store, OkHttpClient(), "device", "{}").drain(target, nowMillis = { clock.get() }))
        assertEquals(3, server.requestCount); assertEquals("m2", store.pendingReports(target).single().id)
    }
    @Test fun `二百状态但坏回执不计进展不确认`() = fixture { store, server, target ->
        store.enqueueReport(PendingReport("m", "chat_messages", "{}"), listOf(target))
        for (reply in listOf("{\"ok\":false}", "<html>login</html>")) {
            server.enqueue(MockResponse().setBody("{\"ok\":true}")); server.enqueue(MockResponse().setBody(reply))
            var batches = 0
            assertFalse(EventDelivery(store, OkHttpClient(), "device", "{}").drain(target, beforeBatch = { batches++ }))
            assertEquals(1, batches); assertEquals(1, store.pendingReports(target).size)
        }
    }
    @Test fun `全部被授权过滤时无进展退出且不删除数据`() = fixture { store, server, target ->
        store.enqueueReport(PendingReport("m", "chat_messages", "{}"), listOf(target))
        server.enqueue(MockResponse().setBody("{\"ok\":true}")); var batches = 0
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}", allowed = { it != "chat_messages" })
        assertTrue(sender.drain(target, beforeBatch = { batches++ }))
        assertEquals(1, batches); assertEquals(1, store.pendingReports(target).size); assertEquals(1, server.requestCount)
    }
    @Test fun `事件连续慢失败耗尽预算下一轮仍给聊天发送机会`() = fixture { store, server, target ->
        store.enqueue(MobileEvent("e", "device", "commit", occurredAt = "2026-09-22T00:00:00Z"), listOf(target))
        store.enqueueReport(PendingReport("message", "chat_messages", "{}"), listOf(target))
        val clock = AtomicLong()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                if (request.path == "/api/v1/mobile/events/batch") {
                    clock.addAndGet(6000)
                    return MockResponse().setResponseCode(503)
                }
                return MockResponse().setBody("{\"ok\":true}")
            }
        }
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}")
        assertFalse(sender.drain(target, nowMillis = { clock.get() }))
        assertEquals(1, store.pendingReports(target).size)
        assertFalse(sender.drain(target, nowMillis = { clock.get() }))
        assertTrue(store.pendingReports(target).isEmpty())
        assertEquals(1, store.pending(target).size)
    }

}
