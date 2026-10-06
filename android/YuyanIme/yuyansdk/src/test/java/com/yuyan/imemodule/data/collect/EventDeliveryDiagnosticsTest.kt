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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventDeliveryDiagnosticsTest {
    @Test fun onlyActualMessageJsonAckReportsAcknowledgedAndHtmlReportsFailure() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val name = "diagnostic-${UUID.randomUUID()}.db"
        val store = LocalInputStore(app, name)
        val server = MockWebServer().apply { start() }
        val target = server.url("/").toString().trimEnd('/')
        val statuses = mutableListOf<Pair<String,String>>()
        var html = true
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                if (request.path!!.endsWith("messages/batch") && html) "<html>bad</html>" else "{\"ok\":true}")
        }
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}", onChatDelivery = { p, s -> statuses += p to s })
        try {
            store.enqueueReport(PendingReport("asset", "chat_asset", "{}"), listOf(target))
            store.enqueueReport(PendingReport("chat", "chat_messages", """{"conversation":{"platform":"wechat"},"messages":[{"text":"合成正文"}]}"""), listOf(target))
            assertFalse(sender.flush(target))
            assertEquals(listOf("wechat" to "waiting", "wechat" to "failed"), statuses)
            assertEquals("chat", store.pendingReports(target).single().id)
            statuses.clear(); html = false
            assertTrue(sender.flush(target))
            assertEquals(listOf("wechat" to "waiting", "wechat" to "acknowledged"), statuses)
            assertTrue(store.pendingReports(target).isEmpty())
        } finally { server.shutdown(); store.close(); app.deleteDatabase(name) }
    }
}
