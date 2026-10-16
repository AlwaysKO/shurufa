package com.yuyan.imemodule.data.usage

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import kotlinx.coroutines.runBlocking
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UsageUploadRecoveryTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val manager=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val ops=app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Before fun setup()=runBlocking {
        val cadence=AppUsageTracker::class.java.getDeclaredField("syncCadence").apply { isAccessible=true }.get(null)
        cadence.javaClass.getDeclaredField("lastStarted").apply { isAccessible=true }.set(cadence,null)
        app.deleteDatabase(java.io.File(app.noBackupFilesDir,"app_usage.db").absolutePath)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,true)
            .putBoolean(AppUsageTracker.KEY,true)
            .putString("online_server_url","https://usage-evidence-test.invalid")
            .putString("server_url","http://127.0.0.1:65534").commit()
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_ALLOWED)
        GameWorkRuntime.setGaming(true);GameWorkRuntime.setGaming(false)
        ImageUploadRuntime.finishInput()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        network(true)
        // Empty sync must not consume an automatic upload slot; no HTTP is issued.
        AppUsageTracker.sync(app)
    }
    private fun network(wifi:Boolean) {
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if(wifi)ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE,0,true,true))
        shadowOf(manager).setDefaultNetworkActive(true)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(if(wifi)NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork,caps)
    }
    private fun pending(target:String=ServerConfig.baseUrl,duration:Long=10_000L,id:String="pending") {
        UsageStore(app).use { store->
            val now=System.currentTimeMillis()
            store.save(UsageReduction(store.state()?:UsageState(cursor=now),listOf(
                UsageRecord(id,"usage","com.example.reader",null,now-duration,now,"lock")
            )),listOf(target))
        }
    }
    private fun advanceEvidenceCadence() {
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        // Robolectric advances Android's monotonic clock, not java.lang.System wall time.
        // Align the observation checkpoint; do not create a synthetic clock_changed gap/HTTP batch.
        UsageStore(app).use { store ->
            val state=store.state()!!
            store.save(UsageReduction(state.copy(elapsed=android.os.SystemClock.elapsedRealtime(),observedAt=System.currentTimeMillis()),emptyList()),emptyList())
        }
    }
    private fun startCadence() {
        val field=AppUsageTracker::class.java.getDeclaredField("syncCadence").apply { isAccessible=true }
        val cadence=field.get(null) as com.yuyan.imemodule.data.collect.CollectorSyncCadence
        assertTrue(cadence.tryStart(true))
    }
    @Test fun `offline persistent usage remains scheduled without making a network request`() = runBlocking {
        pending()
        shadowOf(manager).setActiveNetworkInfo(null)
        assertNull(AppUsageTracker.nextUploadDelay(app))
        assertEquals(300_000L, AppUsageTracker.pendingRecoveryDelay(app))
    }

    @Test fun `no reportable usage creates no persistent recovery appointment`() = runBlocking {
        shadowOf(manager).setActiveNetworkInfo(null)
        assertNull(AppUsageTracker.pendingRecoveryDelay(app))
        pending(duration = 1L)
        assertNull(AppUsageTracker.pendingRecoveryDelay(app))
    }

    @Test fun `empty sync leaves next real batch immediately eligible`()=runBlocking {
        pending()
        assertEquals(30_000L,AppUsageTracker.nextUploadDelay(app))
        assertEquals(30_000L,AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `switching networks and querying never reset due time`()=runBlocking {
        startCadence(); pending()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(120))
        assertEquals(180_000L,AppUsageTracker.nextUploadDelay(app))
        network(false)
        assertEquals(180_000L,AppUsageTracker.nextUploadDelay(app))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        network(true)
        assertEquals(120_000L,AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `manual sync retries immediately with real receipt then automatic sync waits a new batch`()=runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val target=server.url("/").toString().trimEnd('/')
            app.sendStickyBroadcast(android.content.Intent("android.hardware.usb.action.USB_STATE")
                .putExtra("connected",true).putExtra("configured",true))
            pending(target)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/v1/mobile/app-usage/batch",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(60)); network(false)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"ok\":true,\"received\":1}"))
            AppUsageTracker.sync(app,userInitiated=true)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/v1/mobile/app-usage/batch",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            UsageStore(app).use { assertTrue(it.pending(target).isEmpty()) }
            pending(target,id="after-manual")
            AppUsageTracker.sync(app)
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals(300_000L,AppUsageTracker.nextUploadDelay(app))
            UsageStore(app).use { assertEquals("after-manual",it.pending(target).single().id) }
        }
    }
    @Test fun `failed local health is retried only once per five minute batch`()=runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val target=server.url("/").toString().trimEnd('/')
            app.sendStickyBroadcast(android.content.Intent("android.hardware.usb.action.USB_STATE")
                .putExtra("connected",true).putExtra("configured",true))
            pending(target)
            repeat(3) { server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503)) }
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            AppUsageTracker.sync(app)
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            UsageStore(app).use { assertEquals(1,it.pending(target).size) }
        }
    }
    @Test fun `pending query does not issue a local health request`()=runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val target=server.url("/").toString().trimEnd('/')
            app.sendStickyBroadcast(android.content.Intent("android.hardware.usb.action.USB_STATE")
                .putExtra("connected",true).putExtra("configured",true))
            pending(target)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
            assertEquals(30_000L,AppUsageTracker.nextUploadDelay(app))
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }
    @Test fun `failed first health does not block another target in same batch`()=runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { unavailable ->
            okhttp3.mockwebserver.MockWebServer().use { available ->
                val first=unavailable.url("/").toString().trimEnd('/')
                val second=available.url("/").toString().trimEnd('/')
                app.sendStickyBroadcast(android.content.Intent("android.hardware.usb.action.USB_STATE")
                    .putExtra("connected",true).putExtra("configured",true))
                pending(first); pending(second)
                val firstTarget=UsageStore(app).use { it.targets().first() }
                // 实际队列的第一个目标失败，后面的目标仍须在本批处理。
                val bad=if(firstTarget==first) unavailable else available
                val good=if(firstTarget==first) available else unavailable
                val badTarget=bad.url("/").toString().trimEnd('/')
                val goodTarget=good.url("/").toString().trimEnd('/')
                bad.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
                good.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
                good.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"ok\":true,\"received\":1}"))
                AppUsageTracker.sync(app)
                assertEquals("/health",bad.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
                assertEquals("/health",good.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
                assertEquals("/api/v1/mobile/app-usage/batch",good.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
                UsageStore(app).use { assertEquals(1,it.pending(badTarget).size); assertTrue(it.pending(goodTarget).isEmpty()) }
            }
        }
    }
    @Test fun `failed upload remains pending and repeated sync does not send or probe before due`()=runBlocking {
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val target=server.url("/").toString().trimEnd('/')
            app.sendStickyBroadcast(android.content.Intent("android.hardware.usb.action.USB_STATE")
                .putExtra("connected",true).putExtra("configured",true))
            pending(target)
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/v1/mobile/app-usage/batch",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            UsageStore(app).use { assertEquals(1,it.pending(target).size) }
            // A second call must return before both upload and the USB health network probe.
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
            AppUsageTracker.sync(app)
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"ok\":true,\"received\":1}"))
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/v1/mobile/app-usage/batch",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            UsageStore(app).use { assertTrue(it.pending(target).isEmpty()) }
        }
    }
    @Test fun `screen off pending usage schedules remaining wifi cadence instead of thirty minute fallback`()=runBlocking {
        startCadence();pending();ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        assertEquals(240_000L,AppUsageTracker.nextUploadDelay(app))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(240))
        assertEquals(30_000L,AppUsageTracker.nextUploadDelay(app))
        UsageStore(app).use { assertEquals(1,it.pending(ServerConfig.baseUrl).size) }
    }
    @Test fun `mobile recovery uses same five minute cadence`()=runBlocking {
        startCadence();pending();network(false);ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        assertEquals(240_000L,AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `empty acknowledged and suppressed queues do not poll`()=runBlocking {
        assertNull(AppUsageTracker.nextUploadDelay(app))
        pending(duration=1000L)
        assertNull(AppUsageTracker.nextUploadDelay(app))
        UsageStore(app).use { it.acknowledge(ServerConfig.baseUrl,listOf("pending")) }
        assertNull(AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `offline permission loss and opt out cannot schedule usage recovery`()=runBlocking {
        pending();shadowOf(manager).setActiveNetworkInfo(null)
        assertNull(AppUsageTracker.nextUploadDelay(app))
        network(true)
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_IGNORED)
        assertNull(AppUsageTracker.nextUploadDelay(app))
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_ALLOWED)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,false).commit()
        assertNull(AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `input and game pause do not inspect storage or cause short polling`()=runBlocking {
        UsageStore(app).use { it.writableDatabase.execSQL("DROP TABLE delivery") }
        ImageUploadRuntime.noteKeyActivity()
        assertNull(AppUsageTracker.nextUploadDelay(app))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        GameWorkRuntime.setGaming(true)
        assertNull(AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `six second screen handoff includes settled screen off event without extending duration`()=runBlocking {
        val start=System.currentTimeMillis()
        assertTrue(AppUsageTracker.setEnabled(app,true,start))
        val manager=app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        shadowOf(manager).addEvent("com.example.reader",start+1_000,1)
        shadowOf(manager).addEvent("com.example.reader",start+11_000,16)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(17))
        AppUsageTracker.sample(app,start+17_000)
        UsageStore(app).use { store ->
            val usage=store.pending(ServerConfig.baseUrl).single { it.kind=="usage" }
            assertEquals(start+1_000,usage.startMs)
            assertEquals(start+11_000,usage.endMs)
            assertEquals(10_000L,usage.endMs-usage.startMs)
        }
    }
    @Test fun `system recovery pauses five minutes without decoding usage payload`()=runBlocking {
        pending()
        UsageStore(app).use { it.writableDatabase.execSQL("UPDATE record SET payload='not-json'") }
        ImageUploadRuntime.noteKeyActivity()
        assertEquals(300_000L,AppUsageTracker.nextUploadDelay(app,includePaused=true))
    }
    @Test fun `unreachable local queue alone does not schedule online retry`()=runBlocking {
        pending("http://127.0.0.1:65534")
        assertNull(AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `new settled usage hands remaining cadence to system but empty resampling does not`()=runBlocking {
        val scheduler=app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        val start=System.currentTimeMillis()
        assertTrue(AppUsageTracker.setEnabled(app,true,start));startCadence()
        val manager=app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        shadowOf(manager).addEvent("com.example.reader",start+1_000,1)
        shadowOf(manager).addEvent("com.example.reader",start+11_000,16)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(17))
        AppUsageTracker.sample(app,start+17_000)
        assertEquals(1,scheduler.allPendingJobs.count { !it.isPeriodic })
        assertEquals(283_000L,scheduler.allPendingJobs.single { !it.isPeriodic }.minLatencyMillis)
        com.yuyan.imemodule.data.collect.UploadRecovery.cancel(app)
        AppUsageTracker.sample(app,start+17_000)
        assertTrue(scheduler.allPendingJobs.none { !it.isPeriodic })
        UsageStore(app).use { assertEquals(1,it.pending(ServerConfig.baseUrl).count { row -> row.kind=="usage" }) }
    }
    @Test fun `new durable gap also hands off without entering sync mutex again`()=runBlocking {
        val scheduler=app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        val start=System.currentTimeMillis()
        assertTrue(AppUsageTracker.setEnabled(app,true,start));startCadence()
        AppUsageTracker::class.java.getDeclaredField("processSampled").apply { isAccessible=true }.set(null,false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(1))
        AppUsageTracker.sample(app,start+1_000)
        assertEquals(1,scheduler.allPendingJobs.count { !it.isPeriodic })
        assertEquals(299_000L,scheduler.allPendingJobs.single { !it.isPeriodic }.minLatencyMillis)
        UsageStore(app).use { assertTrue(it.pending(ServerConfig.baseUrl).any { row -> row.kind=="gap" }) }
    }
    @Test fun `new usage during input has five minute system pause floor`()=runBlocking {
        val scheduler=app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler
        val start=System.currentTimeMillis()
        assertTrue(AppUsageTracker.setEnabled(app,true,start))
        val manager=app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        shadowOf(manager).addEvent("com.example.reader",start+1_000,1)
        shadowOf(manager).addEvent("com.example.reader",start+11_000,16)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(17));ImageUploadRuntime.noteKeyActivity()
        AppUsageTracker.sample(app,start+17_000)
        assertEquals(1,scheduler.allPendingJobs.count { !it.isPeriodic })
        assertEquals(300_000L,scheduler.allPendingJobs.single { !it.isPeriodic }.minLatencyMillis)
    }

    @Test fun `raw pause off and lock persist from authorized real query without inferring segment end`()=runBlocking {
        val start=System.currentTimeMillis()
        assertTrue(AppUsageTracker.setEnabled(app,true,start))
        val manager=app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        shadowOf(manager).addEvent("reader",start-1_000,16)
        shadowOf(manager).addEvent("reader",start+1_000,1)
        shadowOf(manager).addEvent("reader",start+8_000,2)
        shadowOf(manager).addEvent("reader",start+10_000,16)
        shadowOf(manager).addEvent("reader",start+11_000,17)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(17))
        AppUsageTracker.sample(app,start+17_000)
        UsageStore(app).use { store ->
            assertEquals(start+8_000,store.pending(ServerConfig.baseUrl).single { it.kind=="usage" }.endMs)
            val evidence=store.pendingEvidence(ServerConfig.baseUrl)!!
            assertEquals(start+8_000,evidence.appActivity!!.atMs)
            assertEquals(start+10_000,evidence.screenOff!!.atMs)
            assertEquals(start+11_000,evidence.lock!!.atMs)
            AppUsageTracker.sample(app,start+17_000)
            assertEquals(evidence.version,store.pendingEvidence(ServerConfig.baseUrl)!!.version)
        }
    }
    @Test fun `snapshot only pending recovers at five minute cadence while revocation retains data`()=runBlocking {
        val start=System.currentTimeMillis()
        assertTrue(AppUsageTracker.setEnabled(app,true,start));startCadence()
        val manager=app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        shadowOf(manager).addEvent("reader",start+1_000,16)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(7))
        AppUsageTracker.sample(app,start+7_000)
        UsageStore(app).use { assertTrue(it.pending(ServerConfig.baseUrl).isEmpty());assertNotNull(it.pendingEvidence(ServerConfig.baseUrl)) }
        assertEquals(293_000L,AppUsageTracker.nextUploadDelay(app))
        ImageUploadRuntime.noteKeyActivity()
        assertEquals(300_000L,AppUsageTracker.nextUploadDelay(app,includePaused=true))
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_IGNORED)
        assertNull(AppUsageTracker.nextUploadDelay(app,includePaused=true))
        UsageStore(app).use { assertNotNull(it.pendingEvidence(ServerConfig.baseUrl)) }
    }

    @Test fun `old server snapshot only waits capability cooldown without empty post or health polling`()=runBlocking {
        assertTrue(AppUsageTracker.setEnabled(app,true))
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val target=server.url("/").toString().trimEnd('/')
            app.sendStickyBroadcast(android.content.Intent("android.hardware.usb.action.USB_STATE").putExtra("connected",true).putExtra("configured",true))
            UsageStore(app).use { store ->
                val t=store.state()!!.cursor
                store.save(UsageReduction(store.state()!!.copy(cursor=t+1),emptyList()),ServerConfig.eventTargets+target,listOf(UsageEvent(t,"off")))
                val version=store.pendingEvidence(target)!!.version
                // Fixture isolates HTTP to MockWebServer: configured production/mirror targets are already ACKed.
                ServerConfig.eventTargets.filter { it!=target }.forEach { store.acknowledgeEvidence(it,version,"saved") }
            }
            ShadowSystemClock.advanceBy(Duration.ofMillis(2))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"collector_base_url\":\"https://collector-test.invalid\"}"))
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/v1/mobile/config",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            advanceEvidenceCadence()
            AppUsageTracker.sample(app)
            UsageStore(app).use { store ->
                assertEquals("snapshot-only target set",listOf(target),store.targets())
                assertTrue("snapshot-only records: ${store.pending(target)}",store.pending(target).isEmpty())
            }
            AppUsageTracker.sync(app)
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertTrue(AppUsageTracker.nextUploadDelay(app)!!>300_000L)
            UsageStore(app).use { assertNotNull(it.pendingEvidence(target)) }
            // An old collector still accepts normal records; capability-negative does not suppress them.
            pending(target)
            UsageStore(app).use { assertEquals(1,it.pendingForUpload(target,emptySet()).size) }
            val cadence=AppUsageTracker::class.java.getDeclaredField("syncCadence").apply { isAccessible=true }.get(null) as com.yuyan.imemodule.data.collect.CollectorSyncCadence
            assertEquals("negative capability must not consume another cadence",0L,cadence.remainingDelay(true))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"ok\":true,\"received\":1}"))
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            val body=org.json.JSONObject(server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)!!.body.readUtf8())
            assertFalse(body.has("usage_evidence"))
            UsageStore(app).use { assertTrue(it.pending(target).isEmpty());assertNotNull(it.pendingEvidence(target)) }
        }
    }

    @Test fun `snapshot only real sync failure retries after five minutes and exact ack clears it`()=runBlocking {
        assertTrue(AppUsageTracker.setEnabled(app,true))
        okhttp3.mockwebserver.MockWebServer().use { server ->
            val target=server.url("/").toString().trimEnd('/')
            app.sendStickyBroadcast(android.content.Intent("android.hardware.usb.action.USB_STATE").putExtra("connected",true).putExtra("configured",true))
            val evidence=UsageStore(app).use { store ->
                val t=store.state()!!.cursor
                store.save(UsageReduction(store.state()!!.copy(cursor=t+1),emptyList()),ServerConfig.eventTargets+target,listOf(UsageEvent(t,"off")))
                val version=store.pendingEvidence(target)!!.version
                // Fixture isolates HTTP to MockWebServer: configured production/mirror targets are already ACKed.
                ServerConfig.eventTargets.filter { it!=target }.forEach { store.acknowledgeEvidence(it,version,"saved") }
                store.pendingEvidence(target)!!
            }
            ShadowSystemClock.advanceBy(Duration.ofMillis(2))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"capabilities\":{\"usage_evidence\":1}}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setResponseCode(503))
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/v1/mobile/config",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/v1/mobile/app-usage/batch",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            UsageStore(app).use { assertEquals(evidence,it.pendingEvidence(target)) }
            AppUsageTracker.sync(app)
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            assertEquals(300_000L,AppUsageTracker.nextUploadDelay(app))
            advanceEvidenceCadence()
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("{\"status\":\"ok\"}"))
            server.enqueue(okhttp3.mockwebserver.MockResponse().setBody("""{"ok":true,"received":0,"usage_evidence_ack":{"version":"${evidence.version}","disposition":"saved"}}"""))
            AppUsageTracker.sync(app)
            assertEquals("/health",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            assertEquals("/api/v1/mobile/app-usage/batch",server.takeRequest(1,java.util.concurrent.TimeUnit.SECONDS)?.path)
            UsageStore(app).use { assertNull(it.pendingEvidence(target));assertFalse(target in it.targets()) }
        }
    }

}
