package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SilentReportingTest {
    @Test fun `WiFi only and bounded rolling upload budget`() {
        var now=0L
        val policy=ImageUploadSchedule { now }
        for(network in listOf(ImageUploadNetwork.MOBILE,ImageUploadNetwork.USB,ImageUploadNetwork.OFFLINE)) {
            assertEquals(0L,policy.maxImageBytes(network))
            assertNull(policy.tryStartImage(network,1))
        }
        assertTrue(policy.maxImageBytes(ImageUploadNetwork.WIFI) <= 8*1024*1024)
        policy.tryStartImage(ImageUploadNetwork.WIFI,1024)!!.close()
        assertNull(policy.tryStartImage(ImageUploadNetwork.WIFI,1024)) // inter-image cooldown
        now=60000
        assertNotNull(policy.tryStartImage(ImageUploadNetwork.WIFI,1024))
    }
    @Test fun `local destination never sends historical chat but still sends location`() {
        val context=ApplicationProvider.getApplicationContext<Context>(); val name="${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name); val server=MockWebServer().apply { start() }
        val target=server.url("/").toString().trimEnd('/')
        try {
            repeat(30) { store.enqueueReport(PendingReport("chat$it","chat_asset","{}"),listOf(target,"online")) }
            store.enqueueReport(PendingReport("loc","location","{}"),listOf(target,"online"))
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher(){
                override fun dispatch(r:okhttp3.mockwebserver.RecordedRequest)=MockResponse().setBody("{\"ok\":true,\"id\":\"loc\"}")
            }
            assertTrue(EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={"online"}).flush(target))
            assertEquals(2,server.requestCount)
            assertEquals("/api/v1/mobile/device",server.takeRequest().path)
            assertEquals("/api/v1/mobile/reports",server.takeRequest().path)
            assertEquals(30,store.readableDatabase.rawQuery("SELECT count(*) FROM pending_report WHERE kind='chat_asset'",null).use { it.moveToFirst();it.getInt(0) })
        } finally { server.shutdown();store.close();context.deleteDatabase(name) }
    }
    @Test fun `online confirmed location expires after seven days not collection age or repeat ack`() {
        var now=100L; val context=ApplicationProvider.getApplicationContext<Context>();val name="${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name){now};val week=7*86400000L
        try {
            for(id in listOf("confirmed","unconfirmed")) store.enqueueReport(PendingReport(id,"location","{}"),listOf("local","online"))
            store.acknowledgeReports("online",listOf("confirmed"),"online")
            now+=week-1;store.pruneExpiredLocalChatReports("online",week)
            assertEquals(2,store.pendingReports("local").size)
            store.acknowledgeReports("online",listOf("confirmed"),"online")
            now+=2;store.pruneExpiredLocalChatReports("online",week)
            assertEquals(listOf("unconfirmed"),store.pendingReports("local").map { it.id })
            assertEquals(listOf("unconfirmed"),store.pendingReports("online").map { it.id })
        } finally {store.close();context.deleteDatabase(name)}
    }
    @Test fun `input event retention also requires first explicit online receipt`() {
        var now=100L;val week=7*86400000L
        val context=ApplicationProvider.getApplicationContext<Context>();val name="${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name){now}
        try {
            for(id in listOf("done","pending")) store.enqueue(MobileEvent(id,"device","commit",text="测试",occurredAt="2026-09-29T00:00:00Z"),listOf("local","online"))
            store.acknowledge("online",listOf("done"),"online")
            now+=week-1;store.pruneExpiredLocalChatReports("online",week)
            assertEquals(2,store.pending("local").size)
            store.acknowledge("online",listOf("done"),"online")
            now+=2;store.pruneExpiredLocalChatReports("online",week)
            assertEquals(listOf("pending"),store.pending("local").map{it.id})
        } finally {store.close();context.deleteDatabase(name)}
    }

    @Test fun `discarded receipt is not proof of online storage`() {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name);val server=MockWebServer().apply{start()};val target=server.url("/").toString().trimEnd('/')
        try {
            store.enqueueReport(PendingReport("r","location","{}"),listOf(target,"local"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"id\":\"r\",\"discarded\":true}"))
            assertFalse(EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={target}).flush(target))
            assertEquals(1,store.pendingReports(target).size)
        } finally { server.shutdown();store.close();context.deleteDatabase(name) }
        assertFalse(com.yuyan.imemodule.data.usage.AppUsageTracker.acceptsReceipt("{\"ok\":true,\"received\":1,\"discarded\":true}",1))
    }

    @Test fun `partial event receipt retains whole batch for idempotent retry`() {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name);val server=MockWebServer().apply{start()};val target=server.url("/").toString().trimEnd('/')
        try {
            store.enqueue(MobileEvent("e","device","commit",occurredAt="2026-09-29T00:00:00Z"),listOf(target,"local"))
            server.enqueue(MockResponse().setBody("{\"ok\":true}"))
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"received\":0}"))
            assertFalse(EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={target}).flush(target))
            assertEquals(1,store.pending(target).size)
        } finally { server.shutdown();store.close();context.deleteDatabase(name) }
    }

}
