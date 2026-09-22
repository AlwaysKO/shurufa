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
import java.io.Closeable
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class EventDeliveryImagePolicyTest {
    private fun fixture(block: (LocalInputStore, MockWebServer, String) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "image-delivery-${UUID.randomUUID()}.db"
        val store = LocalInputStore(context, name)
        val server = MockWebServer().apply { start() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("{\"ok\":true}")
        }
        try { block(store, server, server.url("/").toString().trimEnd('/')) }
        finally { server.shutdown(); store.close(); context.deleteDatabase(name) }
    }

    @Test fun pausedBudgetKeepsImagesButSendsOrdinaryEvents() = fixture { store, server, target ->
        store.enqueueReport(PendingReport("asset", "chat_asset", "{}"), listOf(target))
        store.enqueue(MobileEvent("event", "device", "commit", occurredAt = "2026-09-22T00:00:00Z"), listOf(target))
        var asked = false
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}", maxImageBytes = { asked = true; 0L })
        assertTrue(sender.drain(target))
        assertTrue(asked)
        assertEquals(listOf("asset"), store.pendingReports(target).map { it.id })
        assertTrue(store.pending(target).isEmpty())
        assertEquals(2, server.requestCount)
    }

    @Test fun nullPermitIsPauseNotFailureOrDeferral() = fixture { store, server, target ->
        for (id in listOf("a", "b")) store.enqueueReport(PendingReport(id, "chat_asset", "{}"), listOf(target))
        var attempts = 0
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}", tryStartImage = { _, _ -> attempts++; null })
        assertTrue(sender.drain(target))
        assertEquals(2, attempts)
        assertEquals(listOf("a", "b"), store.pendingReports(target).map { it.id })
        assertEquals(1, server.requestCount)
    }

    @Test fun metersUtf8AndRechecksBeforeNextImage() = fixture { store, server, target ->
        val payload = "{\"note\":\"中文\"}"
        for (id in listOf("a", "b")) store.enqueueReport(PendingReport(id, "chat_asset", payload), listOf(target))
        var closed = 0
        val charged = mutableListOf<Long>()
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}", tryStartImage = { actualTarget, bytes ->
            assertEquals(target, actualTarget)
            if (closed > 0) null else { charged += bytes; Closeable { closed++ } }
        })
        assertTrue(sender.drain(target))
        assertEquals(listOf(payload.toByteArray(Charsets.UTF_8).size.toLong()), charged)
        assertEquals(1, closed)
        assertEquals(listOf("b"), store.pendingReports(target).map { it.id })
        assertEquals(2, server.requestCount)
    }

    @Test fun failedHttpStillReleasesPermitAndRetainsAsset() = fixture { store, server, target ->
        store.enqueueReport(PendingReport("asset", "chat_asset", "{}"), listOf(target))
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path!!.endsWith("/assets"))
                MockResponse().setResponseCode(503) else MockResponse().setBody("{\"ok\":true}")
        }
        var closed = 0
        val sender = EventDelivery(store, OkHttpClient(), "device", "{}", tryStartImage = { _, _ -> Closeable { closed++ } })
        assertFalse(sender.flush(target))
        assertEquals(1, closed)
        assertEquals("asset", store.pendingReports(target).single().id)
    }
}
