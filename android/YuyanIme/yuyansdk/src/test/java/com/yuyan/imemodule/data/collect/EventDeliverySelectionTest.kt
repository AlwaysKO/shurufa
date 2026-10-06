package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class EventDeliverySelectionTest {
    private val textAndLocation=DeliverySelection(events=false,regular=false,images=false)
    private fun fixture(block:(LocalInputStore,MockWebServer,String)->Unit) {
        val context=ApplicationProvider.getApplicationContext<Context>();val name="selection-${UUID.randomUUID()}.db"
        val store=LocalInputStore(context,name);val server=MockWebServer().apply{start()}
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse {
                val body=JSONObject(request.body.clone().readUtf8())
                return MockResponse().setBody(JSONObject().put("ok",true).put("received",1).put("id",body.optString("id")).toString())
            }
        }
        try{block(store,server,server.url("/").toString().trimEnd('/'))}
        finally{server.shutdown();store.close();context.deleteDatabase(name)}
    }
    @Test fun `未到期普通报告和图片在SQL限额之前排除不遮蔽位置文字`()=fixture{store,_,target->
        repeat(30){store.enqueueReport(PendingReport("regular$it","phrase_use","{}"),listOf(target))}
        repeat(30){store.enqueueReport(PendingReport("image$it","chat_asset","{}"),listOf(target))}
        store.enqueueReport(PendingReport("location","location","{}"),listOf(target))
        store.enqueueReport(PendingReport("text","chat_messages","{}"),listOf(target))
        val selected=store.pendingReports(target,limit=2,selection=textAndLocation,
            maxImageBytes={error("unselected_image_budget_must_not_be_read")},beginImageRead={error("unselected_images_must_not_be_opened")})
        assertEquals(setOf("location","text"),selected.map{it.id}.toSet())
    }
    @Test fun `只选文字位置时不解码事件也不发送确认普通记录`()=fixture{store,server,target->
        store.enqueue(MobileEvent("event","device","commit",occurredAt="2026-10-06T00:00:00Z"),listOf(target))
        store.writableDatabase.execSQL("UPDATE pending_event SET payload='corrupt json'")
        repeat(30){store.enqueueReport(PendingReport("regular$it","phrase_use","{}"),listOf(target))}
        store.enqueueReport(PendingReport("location","location","{}"),listOf(target))
        store.enqueueReport(PendingReport("text","chat_messages","{}"),listOf(target))
        val sender=EventDelivery(store,OkHttpClient(),"device","{}")
        assertTrue(sender.drain(target,selection=textAndLocation))
        assertEquals(3,server.requestCount)
        val paths=(1..server.requestCount).map{server.takeRequest().path}
        assertEquals(setOf("/api/v1/mobile/device","/api/v1/mobile/reports","/api/v1/mobile/chat/messages/batch"),paths.toSet())
        assertTrue(store.hasPendingEvents());assertEquals(setOf("phrase_use"),store.pendingKinds())
        store.readableDatabase.rawQuery("SELECT COUNT(*) FROM report_target",null).use{assertTrue(it.moveToFirst());assertEquals(30,it.getInt(0))}
    }
    @Test fun `蜂窝只选文字不索引旧图片且缺图消息仍等待`()=fixture{store,_,target->
        val hash="a".repeat(64);val db=store.writableDatabase
        db.execSQL("INSERT INTO pending_report(id,kind,payload) VALUES(?,?,?)",arrayOf("old-image","chat_asset","{\"sha256\":\"$hash\",\"file_base64\":\"${"A".repeat(2000)}\"}"))
        db.execSQL("INSERT INTO report_target(report_id,target) VALUES(?,?)",arrayOf("old-image",target))
        store.enqueueReport(PendingReport("dependent","chat_messages","{\"messages\":[{\"asset_sha256\":[\"$hash\"]}]}"),listOf(target))
        store.enqueueReport(PendingReport("text","chat_messages","{}"),listOf(target))
        val onlyText=textAndLocation.copy(location=false)
        assertEquals(listOf("text"),store.pendingReports(target,selection=onlyText,
            maxImageBytes={error("no_image_budget_on_text_selection")},beginImageRead={error("no_image_read_on_text_selection")}).map{it.id})
        db.rawQuery("SELECT COUNT(*) FROM report_image_meta WHERE report_id='old-image'",null).use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
        store.acknowledgeReports(target,listOf("old-image","text"))
        assertEquals(listOf("dependent"),store.pendingReports(target,selection=onlyText).map{it.id})
    }
    @Test fun `选择和授权相交不能放行被禁止的位置或聊天`()=fixture{store,_,target->
        store.enqueueReport(PendingReport("location","location","{}"),listOf(target))
        store.enqueueReport(PendingReport("text","chat_messages","{}"),listOf(target))
        store.enqueueReport(PendingReport("ordinary","phrase_use","{}"),listOf(target))
        assertTrue(store.pendingReports(target,selection=textAndLocation,includeLocation=false,includeChat=false).isEmpty())
        assertEquals(listOf("ordinary"),store.pendingReports(target,selection=DeliverySelection(location=false,chatText=false,images=false)).map{it.id})
    }
    @Test fun `只选图片不索引未选择的旧文字正文`()=fixture{store,_,target->
        val db=store.writableDatabase
        db.execSQL("INSERT INTO pending_report(id,kind,payload) VALUES(?,?,?)",arrayOf("old-text","chat_messages","{}"))
        db.execSQL("INSERT INTO report_target(report_id,target) VALUES(?,?)",arrayOf("old-text",target))
        store.enqueueReport(PendingReport("image","chat_asset","{}"),listOf(target))
        val onlyImages=DeliverySelection(events=false,regular=false,location=false,chatText=false)
        assertEquals(listOf("image"),store.pendingReports(target,selection=onlyImages).map{it.id})
        db.rawQuery("SELECT COUNT(*) FROM report_image_meta WHERE report_id='old-text'",null).use{assertTrue(it.moveToFirst());assertEquals(0,it.getInt(0))}
    }
    @Test fun `轻量队列类型不解析损坏正文并随各目标确认更新`()=fixture{store,_,target->
        assertFalse(store.hasPendingEvents());assertTrue(store.pendingKinds().isEmpty())
        store.enqueue(MobileEvent("event","device","commit",occurredAt="2026-10-06T00:00:00Z"),listOf(target,"other"))
        store.enqueueReport(PendingReport("location","location","{}"),listOf(target,"other"))
        store.enqueueReport(PendingReport("image","chat_asset","{}"),listOf(target))
        store.writableDatabase.execSQL("UPDATE pending_report SET payload='broken'")
        assertTrue(store.hasPendingEvents());assertEquals(setOf("location","chat_asset"),store.pendingKinds())
        store.acknowledge(target,listOf("event"));store.acknowledgeReports(target,listOf("location","image"))
        assertTrue(store.hasPendingEvents());assertEquals(setOf("location"),store.pendingKinds())
        store.acknowledge("other",listOf("event"));store.acknowledgeReports("other",listOf("location"))
        assertFalse(store.hasPendingEvents());assertTrue(store.pendingKinds().isEmpty())
    }
    @Test fun `慢图耗尽预算前先传位置文字普通报告且失败图片仍待传`()=fixture{store,server,target->
        val clock=AtomicLong(0)
        store.enqueueReport(PendingReport("image","chat_asset","{}"),listOf(target))
        store.enqueueReport(PendingReport("regular","phrase_use","{}"),listOf(target))
        store.enqueueReport(PendingReport("text","chat_messages","{}"),listOf(target))
        store.enqueueReport(PendingReport("location","location","{}"),listOf(target))
        server.dispatcher=object:Dispatcher(){
            override fun dispatch(request:RecordedRequest):MockResponse {
                if(request.path=="/api/v1/mobile/chat/assets") {
                    clock.addAndGet(6_000)
                    return MockResponse().setResponseCode(503)
                }
                val body=JSONObject(request.body.clone().readUtf8())
                return MockResponse().setBody(JSONObject().put("ok",true).put("id",body.optString("id")).toString())
            }
        }
        val sender=EventDelivery(store,OkHttpClient(),"device","{}")
        assertFalse(sender.drain(target,nowMillis={clock.get()},selection=DeliverySelection(events=false)))
        assertEquals(setOf("chat_asset"),store.pendingKinds())
        val requests=(1..server.requestCount).map{server.takeRequest()}
        assertEquals(listOf("/api/v1/mobile/device","/api/v1/mobile/reports","/api/v1/mobile/chat/messages/batch",
            "/api/v1/mobile/reports","/api/v1/mobile/chat/assets"),requests.map{it.path})
        assertEquals("location",JSONObject(requests[1].body.readUtf8()).getString("id"))
        assertEquals("regular",JSONObject(requests[3].body.readUtf8()).getString("id"))
        store.readableDatabase.rawQuery("SELECT attempted_at FROM report_target WHERE report_id='image'",null).use{
            assertTrue(it.moveToFirst());assertTrue(it.getLong(0)>0)
        }
    }
    @Test fun `位置先进入首批不被大量文字挡住且失败位置仍退避`()=fixture{store,_,target->
        repeat(30){store.enqueueReport(PendingReport("text$it","chat_messages","{}"),listOf(target))}
        store.enqueueReport(PendingReport("location","location","{}"),listOf(target))
        assertEquals("location",store.pendingReports(target,limit=1,selection=textAndLocation).single().id)
        store.deferReport(target,"location")
        assertEquals("text0",store.pendingReports(target,limit=1,selection=textAndLocation).single().id)
        assertEquals(setOf("location","chat_messages"),store.pendingKinds())
    }
}
