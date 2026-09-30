package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.net.CaptureApi
import com.yuyan.imemodule.data.capture.net.PendingMessageUploadPayload
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ChatCallStatusDeliveryTest {
    private val conversation = buildJsonObject { put("platform", "wechat") }
    private fun message(text: String) = buildJsonObject {
        put("direction", "incoming"); put("text", text)
        put("metadata", buildJsonObject { put("capture_source", "notification") })
    }
    private fun payload(vararg texts: String) = buildJsonObject {
        put("device_id", "device"); put("conversation", conversation)
        put("messages", JsonArray(texts.map(::message)))
    }.toString()

    @Test fun captureApiDoesNotEnqueueStatusButKeepsRealVoice() {
        val queued=mutableListOf<String>()
        val api=CaptureApi("http://localhost", "device", enqueue={_,body->queued.add(body);true})
        fun row(text: String)=PendingMessageUploadPayload("device",conversation,message(text))
        assertTrue(api.uploadMessages(listOf(row("语音通话中"),row("视频通话中"))))
        assertTrue(queued.isEmpty())
        assertTrue(api.uploadMessages(listOf(row("语音通话中"),row("[语音]"))))
        val sent=Json.parseToJsonElement(queued.single()).jsonObject.getValue("messages").jsonArray
        assertEquals(listOf("[语音]"),sent.map{it.jsonObject.getValue("text").jsonPrimitive.content})
    }

    @Test fun legacyMessagingTimestampPreservesRealTextThroughBothQueues() = fixture { store,server,target ->
        val original=message("语音通话中")
        val real=JsonObject(original + ("metadata" to buildJsonObject {
            put("capture_source","notification");put("notification_message_timestamp","1700000000000")
        }))
        val queued=mutableListOf<String>()
        val api=CaptureApi(target,"device",enqueue={_,body->queued.add(body);true})
        assertTrue(api.uploadMessages(listOf(PendingMessageUploadPayload("device",conversation,real))))
        assertEquals(1,queued.size)
        store.enqueueReport(PendingReport("real","chat_messages",queued.single()),listOf(target))
        assertTrue(EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={target}).drain(target))
        assertEquals(2,server.requestCount)
        server.takeRequest()
        assertEquals(real,Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("messages").jsonArray.single())
    }

    @Test fun durableOldQueueCancelsAllStatusWithoutPostingOrOnlineConfirmation() = fixture { store,server,target ->
        store.enqueueReport(PendingReport("call-status","chat_messages",payload("语音通话中","视频通话中")),listOf(target))
        assertTrue(EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={target}).drain(target))
        assertTrue(store.pendingReports(target).isEmpty())
        assertEquals(1,server.requestCount) // 只有设备注册，没有聊天请求。
        assertEquals("/api/v1/mobile/device",server.takeRequest().path)
    }

    @Test fun mixedOldQueueFiltersEveryRetryAndRetainsVoiceUntilConfirmed() = fixture { store,server,target ->
        store.enqueueReport(PendingReport("mixed","chat_messages",payload("语音通话中","[语音]")),listOf(target))
        var fail=true
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=
            if(fail&&request.path!!.endsWith("/messages/batch")) MockResponse().setResponseCode(503)
            else MockResponse().setBody("""{"ok":true}""")}
        val sender=EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={target})
        assertFalse(sender.flush(target));assertEquals(1,store.pendingReports(target).size)
        fail=false;assertTrue(sender.drain(target));assertTrue(store.pendingReports(target).isEmpty())
        var batches=0
        repeat(server.requestCount) {
            val request=server.takeRequest()
            if(request.path!!.endsWith("/messages/batch")) {
                batches++
                val sent=Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("messages").jsonArray
                assertEquals(listOf("[语音]"),sent.map{it.jsonObject.getValue("text").jsonPrimitive.content})
            }
        }
        assertEquals(2,batches)
    }

    private fun fixture(block:(LocalInputStore,MockWebServer,String)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val name="call-status-${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name)
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody("""{"ok":true}""")}
            server.start()
            try {block(store,server,server.url("/").toString().trimEnd('/'))}
            finally {store.close();context.deleteDatabase(name)}
        }
    }
}
