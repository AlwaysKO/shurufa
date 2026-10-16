package com.yuyan.imemodule.data.capture.page

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import java.io.Closeable
import java.io.File
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class VideoVisitImageDependencyTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val manager=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val now=1_791_504_000_000L
    @Before fun setup() {
        val cadence=VideoVisitSync::class.java.getDeclaredField("cadence").apply { isAccessible=true }.get(null)
        cadence.javaClass.getDeclaredField("lastStarted").apply { isAccessible=true }.set(cadence,null)
        for(name in listOf("video_visits.db","page_capture_outbox.db")) app.deleteDatabase(File(app.noBackupFilesDir,name).absolutePath)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,true).commit()
        ServerConfig.init(app)
        GameWorkRuntime.setGaming(true); GameWorkRuntime.setGaming(false)
        ImageUploadRuntime.finishInput(); ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        network(false)
    }
    private fun network(wifi: Boolean) {
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if(wifi)ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE,0,true,true))
        shadowOf(manager).setDefaultNetworkActive(true)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(if(wifi)NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork,caps)
    }
    private fun image(label: String): String = PageCaptureOutbox(app).use { pages ->
        pages.enqueue("com.tencent.mm",PageFrame(PageDecision(PageKind.MEDIA_FEED,"verified"),label.toByteArray(),500,1000),now).id!!
    }
    private fun visit(store: VideoVisitStore, first: String?=null, last: String?=null, offset: Long=0): VideoVisit {
        val key="visit-$offset"
        val row=store.enter("wechat",key,100,now+offset,first).active
        if(last!=null) assertTrue(store.attachFrame(row.id,key,VideoFrameRole.LAST,last))
        return store.finish(row.id,200,now+offset+100,VideoExitReason.LOCKED)!!
    }
    private fun receipt(row: VideoVisit) = PageUploadResponse(200,
        JSONObject().put("ok",true).put("record",videoVisitPayload(row)).toString())

    @Test fun onlyImageBlockedMetadataNeverSchedulesEvenOnWifiOrWhenPaused() {
        val first=image("pending")
        val row=VideoVisitStore(app).use { visit(it,first) }
        for(wifi in listOf(false,true)) {
            network(wifi)
            assertNull(VideoVisitSync.nextUploadDelay(app))
            assertFalse(VideoVisitSync.hasDue(app))
        }
        ImageUploadRuntime.noteKeyActivity()
        assertNull(VideoVisitSync.nextUploadDelay(app,includePaused=true))
        VideoVisitStore(app).use { assertEquals(row,it.completed().single()) }
        PageCaptureOutbox(app).use { assertEquals(first,it.pending().single().id) }
    }

    @Test fun bothFrameReferencesMustFinishButAcknowledgementRestoresEligibilityWithoutChangingVisit() {
        val first=image("first"); val last=image("last")
        val row=VideoVisitStore(app).use { visit(it,first,last) }
        assertNull(VideoVisitSync.nextUploadDelay(app))
        PageCaptureOutbox(app).use { pages -> assertTrue(pages.acknowledge(pages.pending().first { it.id==first }) { true }) }
        assertNull(VideoVisitSync.nextUploadDelay(app))
        PageCaptureOutbox(app).use { pages -> assertTrue(pages.acknowledge(pages.pending().single()) { true }) }
        assertEquals(5000L,VideoVisitSync.nextUploadDelay(app))
        assertTrue(VideoVisitSync.hasDue(app))
        VideoVisitStore(app).use { assertEquals(row,it.completed().single()) }
    }

    @Test fun waitingRowsDoNotPrepareOrSendAndDoNotAcquireRetryBackoff()=runBlocking {
        val first=image("waiting")
        VideoVisitStore(app).use { store ->
            val row=visit(store,first)
            var preparations=0; var requests=0
            val result=VideoVisitDelivery(store,{ now+1000 },{ true },{ preparations++;Closeable {} },{ _, _ ->
                requests++;PageUploadResponse(409,"{\"error\":\"missing_page_captures\"}")
            }).runOnce(limit=100,blockedImageIds=setOf(first))
            assertEquals(VideoDeliveryResult(0,0,0),result)
            assertEquals(0,preparations); assertEquals(0,requests)
            assertEquals(row,store.completed().single())
            assertEquals(0L,store.nextDeliveryDelay(now+1000,setOf("wechat")))
        }
    }

    @Test fun blockedFrontBeyondBatchLimitCannotStarveReadyMetadataOrUnknownRemoteReferences()=runBlocking {
        val first=image("blocking")
        VideoVisitStore(app).use { store ->
            repeat(205) { visit(store,first,offset=it.toLong()) }
            val ready=visit(store,"00000000-0000-4000-8000-000000000008",offset=1000)
            val remote=visit(store,"00000000-0000-4000-8000-000000000009",offset=2000)
            val sent=mutableListOf<String>()
            val result=VideoVisitDelivery(store,{ now+3000 },{ true },{ Closeable {} },{ row, _ -> sent.add(row.id);receipt(row) })
                .runOnce(limit=2,blockedImageIds=setOf(first))
            assertEquals(2,result.saved)
            assertEquals(listOf(ready.id,remote.id),sent)
            assertNull(store.nextDeliveryDelay(now+3000,setOf("wechat"),blockedImageIds=setOf(first)))
            assertEquals(205L,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM completed",null).use { it.moveToFirst();it.getLong(0) })
        }
    }

    @Test fun versionTwoMigrationKeepsPayloadActiveAndBackoffWhileFillingReferenceColumns() {
        val active=VideoVisit("00000000-0000-4000-8000-000000000001","wechat","active",now,100,firstImage="active-image")
        val done=active.copy(id="00000000-0000-4000-8000-000000000002",reason=VideoExitReason.INTERRUPTED,
            firstImage="00000000-0000-4000-8000-000000000003",lastImage="00000000-0000-4000-8000-000000000004")
        val payload=Json.encodeToString(done)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(File(app.noBackupFilesDir,"video_visits.db"),null).use { db ->
            db.execSQL("CREATE TABLE active (id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE completed (id TEXT PRIMARY KEY,entered_at INTEGER NOT NULL,payload TEXT NOT NULL,retry_after INTEGER NOT NULL DEFAULT 0,platform TEXT NOT NULL)")
            db.execSQL("CREATE INDEX completed_order ON completed(entered_at,id)")
            db.execSQL("CREATE INDEX completed_delivery ON completed(platform,retry_after,entered_at,id)")
            db.execSQL("INSERT INTO active VALUES(1,?)",arrayOf(Json.encodeToString(active)))
            db.execSQL("INSERT INTO completed VALUES(?,?,?,?,?)",arrayOf(done.id,done.enteredAt,payload,now+12_000,done.platform))
            val frameless=done.copy(id="00000000-0000-4000-8000-000000000005",firstImage=null,lastImage=null)
            db.execSQL("INSERT INTO completed VALUES(?,?,?,?,?)",arrayOf(frameless.id,frameless.enteredAt,Json.encodeToString(frameless),0,frameless.platform))
            db.version=2
        }
        VideoVisitStore(app).use { store ->
            assertEquals(5,store.readableDatabase.version)
            assertEquals(active,store.active()); assertEquals(done,store.completed().single())
            store.readableDatabase.rawQuery("SELECT payload,retry_after,first_image_id,last_image_id FROM completed",null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(payload,c.getString(0)); assertEquals(now+12_000,c.getLong(1))
                assertEquals(done.firstImage,c.getString(2)); assertEquals(done.lastImage,c.getString(3))
            }
            assertEquals(12_000L,store.nextDeliveryDelay(now,setOf("wechat")))
            assertNull(store.nextDeliveryDelay(now,setOf("wechat"),blockedImageIds=setOf(done.lastImage!!)))
        }
    }

    @Test fun collidedVersionFourWithoutImageColumnsRecoversWithoutLosingPayloadOrBackoff() {
        val active=VideoVisit("00000000-0000-4000-8000-000000000001","wechat","active",now,100,firstImage="active-image")
        val done=active.copy(id="00000000-0000-4000-8000-000000000002",reason=VideoExitReason.INTERRUPTED)
        val payload=Json.encodeToString(done)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(File(app.noBackupFilesDir,"video_visits.db"),null).use { db ->
            db.execSQL("CREATE TABLE active (id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE completed (id TEXT PRIMARY KEY,entered_at INTEGER NOT NULL,payload TEXT NOT NULL,retry_after INTEGER NOT NULL DEFAULT 0,platform TEXT NOT NULL)")
            db.execSQL("INSERT INTO active VALUES(1,?)",arrayOf(Json.encodeToString(active)))
            db.execSQL("INSERT INTO completed VALUES(?,?,?,?,?)",arrayOf(done.id,done.enteredAt,payload,now+12_000,done.platform))
            db.version=4
        }
        VideoVisitStore(app).use { store ->
            store.recoverInterrupted()
            assertNull(store.active())
            assertEquals(2,store.completed().size)
            store.readableDatabase.rawQuery("SELECT payload,retry_after,first_image_id FROM completed WHERE id=?",arrayOf(done.id)).use { c ->
                assertTrue(c.moveToFirst()); assertEquals(payload,c.getString(0)); assertEquals(now+12_000,c.getLong(1)); assertEquals(done.firstImage,c.getString(2))
            }
        }
        VideoVisitStore(app).use { assertEquals(2,it.completed().size) }
    }

    @Test fun completeVersionFourDoesNotDecodeOrRewriteQueuedPayloads() {
        VideoVisitStore(app).use { store ->
            store.writableDatabase.execSQL("INSERT INTO completed(id,entered_at,payload,platform,first_image_id) VALUES('waiting',0,'future-format','wechat','image')")
            store.writableDatabase.version=4
        }
        VideoVisitStore(app).use { store ->
            assertEquals(5,store.readableDatabase.version)
            assertEquals(emptyList<VideoVisit>(),store.due(now,blockedImageIds=setOf("image")))
            store.readableDatabase.rawQuery("SELECT payload FROM completed",null).use { c ->
                assertTrue(c.moveToFirst()); assertEquals("future-format",c.getString(0))
            }
        }
    }

    @Test fun partiallyMigratedVersionFourAddsOnlyMissingColumnAndBackfillsBothReferences() {
        val visit=VideoVisit("00000000-0000-4000-8000-000000000001","wechat","fixture",now,100,
            firstImage="first",lastImage="last",reason=VideoExitReason.INTERRUPTED)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(File(app.noBackupFilesDir,"video_visits.db"),null).use { db ->
            db.execSQL("CREATE TABLE active (id INTEGER PRIMARY KEY CHECK(id=1),payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE completed (id TEXT PRIMARY KEY,entered_at INTEGER NOT NULL,payload TEXT NOT NULL,retry_after INTEGER NOT NULL DEFAULT 0,platform TEXT NOT NULL,first_image_id TEXT)")
            db.execSQL("INSERT INTO completed VALUES(?,?,?,?,?,?)",arrayOf(visit.id,now,Json.encodeToString(visit),now+12000,"wechat","first"))
            db.version=4
        }
        VideoVisitStore(app).use { store ->
            assertEquals(visit,store.completed().single())
            assertNull(store.nextDeliveryDelay(now,setOf("wechat"),blockedImageIds=setOf("last")))
            assertEquals(12000L,store.nextDeliveryDelay(now,setOf("wechat")))
        }
    }

    @Test fun sqlFiltersWaitingRowsWithoutDecodingTheirPayload() {
        val blocked="00000000-0000-4000-8000-000000000009"
        VideoVisitStore(app).use { store ->
            assertEquals(5,store.writableDatabase.version)
            store.writableDatabase.execSQL("INSERT INTO completed(id,entered_at,payload,platform,first_image_id) VALUES('blocked',0,'not-json','wechat',?)",arrayOf(blocked))
            val ready=visit(store,"remote-image")
            assertEquals(listOf(ready),store.due(now+1000,limit=1,blockedImageIds=setOf(blocked)))
            assertEquals(0L,store.nextDeliveryDelay(now,setOf("wechat"),blockedImageIds=setOf(blocked)))
        }
    }
}
