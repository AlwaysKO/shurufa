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
    @Test fun `本地配置不取代线上目标且去重`() {
        assertEquals(listOf("http://127.0.0.1:3000", "https://my.dog8ball.com"), collectorTargets(null))
        assertEquals(listOf("http://192.168.1.5:3000", "https://my.dog8ball.com"), collectorTargets(" http://192.168.1.5:3000/ "))
        assertEquals(listOf("https://my.dog8ball.com"), collectorTargets("https://my.dog8ball.com/"))
    }
}
