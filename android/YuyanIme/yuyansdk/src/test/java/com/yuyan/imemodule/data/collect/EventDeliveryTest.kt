package com.yuyan.imemodule.data.collect

import kotlinx.serialization.json.Json
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventDeliveryTest {
    @Test fun `失败目标重试不重发已确认目标且先注册设备`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val local = MockWebServer()
        val remote = MockWebServer()
        local.start(); remote.start()
        try {
            val a = local.url("/").toString().trimEnd('/')
            val b = remote.url("/").toString().trimEnd('/')
            val e = MobileEvent("id-1", "device-1", "commit", text = "候选词", inputCode = "46898262", occurredAt = "2026-09-07T00:00:00Z")
            store.enqueue(e, listOf(a, b))
            val delivery = EventDelivery(store, OkHttpClient(), "device-1", "{\"id\":\"device-1\"}")
            local.enqueue(MockResponse().setBody("{\"ok\":true}"))
            local.enqueue(MockResponse().setBody("{\"ok\":true}"))
            remote.enqueue(MockResponse().setResponseCode(503))
            assertTrue(delivery.flush(a))
            assertFalse(delivery.flush(b))
            assertEquals("/api/v1/mobile/device", local.takeRequest().path)
            val request = local.takeRequest()
            assertEquals("device-1", request.getHeader("X-Device-Id"))
            assertTrue(request.body.readUtf8().contains("46898262"))
            assertEquals(listOf(e), store.pending(b))
            remote.enqueue(MockResponse().setBody("{\"ok\":true}"))
            remote.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(delivery.flush(b))
            assertTrue(store.targets().isEmpty())
            assertEquals(2, local.requestCount)
        } finally {
            local.shutdown(); remote.shutdown(); store.close(); context.deleteDatabase(name)
        }
    }
    @Test fun `不能把代理的嵌套ok或HTML误认为事件确认`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val server = MockWebServer()
        server.start()
        try {
            val target = server.url("/").toString().trimEnd('/')
            val event = MobileEvent("id-1", "device-1", "commit", occurredAt = "2026-09-07T00:00:00Z")
            store.enqueue(event, listOf(target))
            val delivery = EventDelivery(store, OkHttpClient(), "device-1", "{\"id\":\"device-1\"}")
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setBody("{\"proxy\":{\"ok\":true},\"error\":\"wrong upstream\"}"))
            assertFalse(delivery.flush(target))
            assertEquals(listOf(event), store.pending(target))
        } finally { server.shutdown(); store.close(); context.deleteDatabase(name) }
    }
    @Test fun `含中文快照积压按UTF8预算分批只确认发送前缀且双端独立`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "test-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val local = MockWebServer()
        val remote = MockWebServer()
        local.start(); remote.start()
        try {
            val a = local.url("/").toString().trimEnd('/')
            val b = remote.url("/").toString().trimEnd('/')
            // Actual UTF-8 exceeds the budget, even though the character count may not.
            val events = (1..60).map { index ->
                MobileEvent("large-$index", "device-1", "commit", text = "字".repeat(5000),
                    textBefore = "前".repeat(5000), textAfter = "后".repeat(5000),
                    sequenceNo = index.toLong(), occurredAt = "2026-09-07T00:00:00Z")
            }
            events.forEach { store.enqueue(it, listOf(a, b)) }
            val delivery = EventDelivery(store, OkHttpClient(), "device-1", "{\"id\":\"device-1\"}")
            local.enqueue(MockResponse().setBody("{\"ok\":true}"))
            local.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(delivery.flush(a))
            assertEquals("/api/v1/mobile/device", local.takeRequest().path)
            val request = local.takeRequest()
            assertTrue("body must fit UTF-8 budget", request.bodySize <= 1024 * 1024)
            val first = Json.decodeFromString(EventBatch.serializer(), request.body.readUtf8()).events
            assertTrue(first.isNotEmpty())
            assertTrue(first.size < events.size)
            assertEquals(events.take(first.size), first)
            assertEquals(events.drop(first.size), store.pending(a))
            assertEquals(events, store.pending(b))
            val uploaded = first.toMutableList()
            while (store.pending(a).isNotEmpty()) {
                local.enqueue(MockResponse().setBody("{\"ok\":true}"))
                assertTrue(delivery.flush(a))
                val next = local.takeRequest()
                assertTrue(next.bodySize <= 1024 * 1024)
                uploaded.addAll(Json.decodeFromString(EventBatch.serializer(), next.body.readUtf8()).events)
            }
            assertEquals(events, uploaded)
            assertEquals(events, store.pending(b))
            remote.enqueue(MockResponse().setBody("{\"ok\":true}"))
            remote.enqueue(MockResponse().setResponseCode(503))
            assertFalse(delivery.flush(b))
            assertEquals(events, store.pending(b))
            remote.takeRequest(); remote.takeRequest()
            remote.enqueue(MockResponse().setBody("{\"ok\":true}"))
            remote.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(delivery.flush(b))
            remote.takeRequest()
            val retry = remote.takeRequest()
            assertTrue(retry.bodySize <= 1024 * 1024)
            assertEquals(first, Json.decodeFromString(EventBatch.serializer(), retry.body.readUtf8()).events)
            assertEquals(events.drop(first.size), store.pending(b))
        } finally {
            local.shutdown(); remote.shutdown(); store.close(); context.deleteDatabase(name)
        }
    }

    @Test fun `本地配置不取代线上目标且去重`() {
        assertEquals(listOf("http://127.0.0.1:3000", "https://my.dog8ball.com"), collectorTargets(null))
        assertEquals(listOf("http://192.168.1.5:3000", "https://my.dog8ball.com"), collectorTargets(" http://192.168.1.5:3000/ "))
        assertEquals(listOf("https://my.dog8ball.com"), collectorTargets("https://my.dog8ball.com/"))
    }
}
