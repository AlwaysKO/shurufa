package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
class EventDeliveryPendingTextTest {
    private val hash="a".repeat(64)
    private fun message(id:String,pending:Boolean,asset:Boolean=false,kind:String="text",deferred:Boolean=false)=buildJsonObject {
        put("id",id);put("direction","incoming");put("message_type",kind);put("text",id)
        put("asset_sha256",buildJsonArray { if(asset) add(hash) })
        put("metadata",buildJsonObject {
            put("capture_source","notification");put("notification_messaging_style","true")
            put("conversation_identity_status",if(pending) "pending" else "confirmed")
            put("identity_unavailable",pending.toString());put("identity_confidence",if(pending) ".55" else ".9")
            put("conversation_identity_source",if(pending) "notification_title_unverified" else "notification_shortcut")
            if(deferred) put("asset_capture_deferred","quota")
        })
    }
    private fun payload(pending:Boolean,vararg messages:JsonObject)=buildJsonObject {
        put("device_id","device")
        put("conversation",buildJsonObject {
            put("platform","wechat");put("account_key","notification")
            put("external_key",if(pending) "notification-v2:pending:example" else "notification-v2:peer:example")
            put("display_name",if(pending) "待确认通知（联系人）" else "联系人")
            put("identity_confidence",if(pending) .55 else .9)
        })
        put("messages",JsonArray(messages.toList()))
    }.toString()

    @Test fun unconfirmedTextCancelsOnlyCurrentTargetWithoutRemoteAcknowledgement()=fixture{store,server,target->
        store.enqueueReport(PendingReport("noise","chat_messages",payload(true,message("noise",true))),listOf(target,"other"))
        assertTrue(EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={target}).drain(target))
        assertEquals(1,server.requestCount)
        assertEquals("/api/v1/mobile/device",server.takeRequest().path)
        assertTrue(store.pendingReports(target).isEmpty())
        assertEquals(listOf("noise"),store.pendingReports("other").map{it.id})
        store.readableDatabase.rawQuery("SELECT online_confirmed_at FROM pending_report WHERE id='noise'",null).use{
            assertTrue(it.moveToFirst());assertTrue(it.isNull(0))
        }
    }

    @Test fun mixedBatchFiltersNoiseOnEveryRetryAndKeepsConfirmedTextAndAssets()=fixture{store,server,target->
        val original=payload(false,message("noise",true),message("confirmed",false),message("attached",true,asset=true),
            message("image-task",true,kind="image"),message("deferred",true,deferred=true))
        store.enqueueReport(PendingReport("mixed","chat_messages",original),listOf(target))
        var fail=true
        server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=
            if(fail&&request.path!!.endsWith("/messages/batch")) MockResponse().setResponseCode(503)
            else MockResponse().setBody("""{"ok":true}""")}
        val sender=EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={target})
        assertFalse(sender.flush(target));assertEquals(original,store.pendingReports(target).single().payload)
        fail=false;assertTrue(sender.drain(target));assertTrue(store.pendingReports(target).isEmpty())
        var batches=0
        repeat(server.requestCount){
            val request=server.takeRequest()
            if(request.path!!.endsWith("/messages/batch")) {
                batches++
                val sent=Json.parseToJsonElement(request.body.readUtf8()).jsonObject.getValue("messages").jsonArray
                assertEquals(listOf("confirmed","attached","image-task","deferred"),sent.map{it.jsonObject.getValue("id").jsonPrimitive.content})
            }
        }
        assertEquals(2,batches)
    }

    @Test fun queuedImageDependencyIsPreservedBeforeTextBatchCanBeFiltered()=fixture{store,server,target->
        store.enqueueReport(PendingReport("asset","chat_asset","""{"sha256":"$hash","file_base64":"AA=="}"""),listOf(target))
        store.enqueueReport(PendingReport("dependent","chat_messages",payload(true,message("noise",true),message("attached",true,asset=true))),listOf(target))
        val onlyText=DeliverySelection(events=false,regular=false,location=false,images=false)
        val sender=EventDelivery(store,OkHttpClient(),"device","{}",onlineTarget={target})
        assertTrue(sender.drain(target,selection=onlyText));assertEquals(1,server.requestCount)
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM report_target",null).use{assertTrue(it.moveToFirst());assertEquals(2,it.getInt(0))}
        store.acknowledgeReports(target,listOf("asset"))
        assertTrue(sender.drain(target,selection=onlyText));assertEquals(2,server.requestCount)
        server.takeRequest()
        val sent=Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject.getValue("messages").jsonArray
        assertEquals(listOf("attached"),sent.map{it.jsonObject.getValue("id").jsonPrimitive.content})
    }

    private fun fixture(block:(LocalInputStore,MockWebServer,String)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="pending-text-${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name)
        MockWebServer().use { server ->
            server.dispatcher=object:Dispatcher(){override fun dispatch(request:RecordedRequest)=MockResponse().setBody("""{"ok":true}""")}
            server.start()
            try{block(store,server,server.url("/").toString().trimEnd('/'))}
            finally{store.close();context.deleteDatabase(name)}
        }
    }
}
