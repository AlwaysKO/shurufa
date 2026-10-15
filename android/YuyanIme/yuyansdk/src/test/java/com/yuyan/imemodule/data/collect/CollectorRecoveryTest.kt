package com.yuyan.imemodule.data.collect

import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
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
class CollectorRecoveryTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val scheduler=app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private lateinit var store:LocalInputStore
    private val plan get()=field("uploadPlan").get(null) as CollectorUploadPlan
    private fun field(name:String)=DataCollector::class.java.getDeclaredField(name).apply{isAccessible=true}
    @Before fun setup() {
        val prefs=PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().clear().putBoolean(CollectionConsent.KEY,true).commit()
        store=LocalInputStore(app,"recovery.db")
        field("delivery").set(null,null)
        field("eventStore").set(null,store);field("appContext").set(null,app);field("prefs").set(null,prefs)
        ServerConfig.init(app);UploadRecovery.cancel(app)
        GameWorkRuntime.setGaming(true);GameWorkRuntime.setGaming(false);ImageUploadRuntime.finishInput()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        network()
        for(name in listOf("regular","location","text")) {
            val cadence=plan.javaClass.getDeclaredField(name).apply{isAccessible=true}.get(plan)
            cadence.javaClass.getDeclaredField("lastStarted").apply{isAccessible=true}.set(cadence,null)
        }
    }
    private fun network() {
        val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_MOBILE,0,true,true))
        shadowOf(cm).setDefaultNetworkActive(true)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(cm).setNetworkCapabilities(cm.activeNetwork,caps)
    }
    @After fun cleanup() {
        (field("wakeJob").get(null) as? Job)?.cancel();field("wakeJob").set(null,null)
        UploadRecovery.cancel(app);UploadRecovery.stopped()
        field("eventStore").set(null,null);field("appContext").set(null,null);field("prefs").set(null,null)
        store.close();app.deleteDatabase("recovery.db")
    }
    private fun pending(kind:String,target:String=ServerConfig.baseUrl) {
        store.enqueueReport(PendingReport(kind,kind,"{}"),listOf(target))
    }
    @Test fun `durable enqueue hands recovery to system not only process timer`() {
        assertTrue(DataCollector.enqueueReport(app,"location","{}"))
        assertEquals(1,scheduler.allPendingJobs.count{!it.isPeriodic})
    }
    @Test fun `location without usage retains remaining batch after screen off settle`()=runBlocking {
        pending("location");plan.select(false,true,setOf("location"),false,false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(6))
        assertEquals(294_000L,DataCollector.nextUploadDelay(app))
        UploadRecovery.scheduleNext(app)
        assertEquals(294_000L,scheduler.allPendingJobs.single().minLatencyMillis)
    }
    @Test fun `ordinary without usage and several kinds select earliest and do not consume cadence`()=runBlocking {
        pending("personal_choice");plan.select(false,true,setOf("personal_choice"),false,false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        pending("location");plan.select(false,true,setOf("location"),false,false)
        assertEquals(240_000L,DataCollector.nextUploadDelay(app))
        assertEquals(240_000L,DataCollector.nextUploadDelay(app))
        UploadRecovery.scheduleNext(app)
        assertEquals(240_000L,scheduler.allPendingJobs.single().minLatencyMillis)
    }
    @Test fun `empty image only disabled location and disconnected usb do not renew`()=runBlocking {
        assertNull(DataCollector.nextUploadDelay(app))
        pending("chat_asset");assertNull(DataCollector.nextUploadDelay(app))
        pending("personal_choice","http://127.0.0.1:65534");assertNull(DataCollector.nextUploadDelay(app))
        pending("location")
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("location_tracking_enable",false).commit()
        assertNull(DataCollector.nextUploadDelay(app))
        UploadRecovery.scheduleNext(app);assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun `pause retains pending with bounded five minute system recheck`()=runBlocking {
        pending("location");ImageUploadRuntime.noteKeyActivity()
        assertEquals(300_000L,DataCollector.nextUploadDelay(app))
        UploadRecovery.scheduleNext(app)
        assertEquals(300_000L,scheduler.allPendingJobs.single().minLatencyMillis)
        assertEquals(1,store.pendingKinds().size)
    }
    @Test fun `revocation and offline stop followup without deleting queued data`()=runBlocking {
        pending("location")
        val cm=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).setActiveNetworkInfo(null)
        assertNull(DataCollector.nextUploadDelay(app))
        network();assertNotNull(DataCollector.nextUploadDelay(app))
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,false).commit()
        UploadRecovery.scheduleNext(app)
        assertTrue(scheduler.allPendingJobs.isEmpty());assertTrue(store.pendingKinds().contains("location"))
    }
    @Test fun `queue receipt clears followup but interrupted work preserves records`()=runBlocking {
        pending("location")
        UploadRecovery.wake(app)
        val scheduled=scheduler.allPendingJobs.single()
        val token=scheduled.extras.getString("token")
        UploadRecovery.started(token);UploadRecovery.stopped(token)
        assertEquals(setOf("location"),store.pendingKinds())
        scheduler.cancelAll()
        store.acknowledgeReports(ServerConfig.baseUrl,listOf("location"),ServerConfig.baseUrl)
        UploadRecovery.scheduleNext(app)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun `reopened persistent queue remains discoverable without an in memory pending marker`() {
        pending("location");store.close()
        store=LocalInputStore(app,"recovery.db");field("eventStore").set(null,store)
        assertEquals(5_000L,DataCollector.nextUploadDelay(app))
    }
    @Test fun `scheduler failure does not turn durable persistence into enqueue failure`() {
        shadowOf(scheduler).failOnJob(5175340)
        assertTrue(DataCollector.enqueueReport(app,"location","{}"))
        assertTrue(store.pendingKinds().contains("location"))
    }

    @Test fun `scheduler exception cannot undo a successfully persisted location`() {
        field("appContext").set(null,object:android.content.ContextWrapper(app) {
            override fun getSystemService(name:String):Any? {
                if(name==Context.JOB_SCHEDULER_SERVICE) throw SecurityException("scheduler unavailable")
                return super.getSystemService(name)
            }
        })
        assertTrue(DataCollector.enqueueReport(app,"location","{}"))
        assertTrue(store.pendingKinds().contains("location"))
    }

    @Test fun `new location during input keeps five minute system pause rather than six second wakes`() {
        ImageUploadRuntime.noteKeyActivity()
        assertTrue(DataCollector.enqueueReport(app,"location","{}"))
        assertEquals(300_000L,scheduler.allPendingJobs.single().minLatencyMillis)
    }

    private fun flushWithSchedulerFailure(failure:Exception):kotlinx.coroutines.Job = runBlocking {
        pending("location")
        val entered=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        field("appContext").set(null,object:android.content.ContextWrapper(app) {
            override fun getSystemService(name:String):Any? {
                if(name==Context.JOB_SCHEDULER_SERVICE) {
                    entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));throw failure
                }
                return super.getSystemService(name)
            }
        })
        val scope=field("scope").get(null) as kotlinx.coroutines.CoroutineScope
        val before=scope.coroutineContext[kotlinx.coroutines.Job]!!.children.toSet()
        val method=DataCollector::class.java.getDeclaredMethod("flushEvents",Boolean::class.javaPrimitiveType).apply{isAccessible=true}
        method.invoke(DataCollector,true)
        try {
            assertTrue(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val job=scope.coroutineContext[kotlinx.coroutines.Job]!!.children.single { it !in before }
            release.countDown()
            kotlinx.coroutines.withTimeout(5_000) { job.join() }
            job
        } finally { release.countDown() }
    }
    @Test fun `asynchronous flush isolates recovery scheduler exceptions`() {
        assertFalse(flushWithSchedulerFailure(SecurityException("scheduler unavailable")).isCancelled)
    }
    @Test fun `asynchronous flush preserves cancellation from recovery query`() {
        assertTrue(flushWithSchedulerFailure(kotlinx.coroutines.CancellationException("stopped")).isCancelled)
    }

}
