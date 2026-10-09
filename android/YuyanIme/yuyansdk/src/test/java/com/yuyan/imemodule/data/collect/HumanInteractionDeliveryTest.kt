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
class HumanInteractionDeliveryTest {
    @Test fun `new interaction reregisters once and retries original timestamp after failure`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "human-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val server = MockWebServer().apply { start() }
        val target = server.url("/").toString().trimEnd('/')
        var interaction: HumanInteraction? = null
        val delivery = EventDelivery(store, OkHttpClient(), "device", "{}", interaction = { interaction })
        try {
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(delivery.flush(target))
            server.takeRequest()
            interaction = HumanInteraction(100_000, "touch")
            assertTrue(delivery.hasPendingInteraction(listOf(target)))
            server.enqueue(MockResponse().setResponseCode(503))
            assertFalse(delivery.flush(target))
            val failed = server.takeRequest().body.readUtf8()
            assertTrue(failed.contains("1970-01-01T00:01:40Z"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(delivery.flush(target))
            assertEquals(failed, server.takeRequest().body.readUtf8())
            assertFalse(delivery.hasPendingInteraction(listOf(target)))
            assertTrue(delivery.flush(target))
            assertEquals(3, server.requestCount)
        } finally {
            server.shutdown(); store.close(); context.deleteDatabase(name)
        }
    }
    @Test fun `image-only registration does not bypass ordinary interaction cadence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "human-image-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val server = MockWebServer().apply { start() }
        val target = server.url("/").toString().trimEnd('/')
        val delivery = EventDelivery(store, OkHttpClient(), "device", "{}", interaction = { HumanInteraction(100_000, "touch") })
        try {
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(delivery.flush(target, DeliverySelection(false, false, false, false, true)))
            assertFalse(server.takeRequest().body.readUtf8().contains("last_interaction_at"))
            assertTrue(delivery.hasPendingInteraction(listOf(target)))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            assertTrue(delivery.flush(target))
            assertTrue(server.takeRequest().body.readUtf8().contains("last_interaction_at"))
            assertFalse(delivery.hasPendingInteraction(listOf(target)))
        } finally {
            server.shutdown(); store.close(); context.deleteDatabase(name)
        }
    }

}
