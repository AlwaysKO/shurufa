package com.yuyan.imemodule.data.collect

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.usage.AppUsageTracker
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UploadRecoveryTest {
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val scheduler=app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private fun consent(value:Boolean) {PreferenceManager.getDefaultSharedPreferences(app).edit()
        .putBoolean(CollectionConsent.KEY,value).putBoolean(AppUsageTracker.KEY,true).commit()}
    private fun screen(action:String) {app.sendBroadcast(Intent(action));shadowOf(Looper.getMainLooper()).idle()}

    private fun pending() {
        ServerConfig.init(app)
        val store = LocalInputStore(app)
        try { store.enqueueReport(PendingReport("fixture", "input_session", "{}"), listOf(ServerConfig.baseUrl)) }
        finally { store.close() }
    }

    @Test fun `screen off hands off existing queues and repeated broadcasts do not postpone jobs`() = kotlinx.coroutines.runBlocking {
        consent(true); pending(); UploadRecovery.observe(app)
        screen(Intent.ACTION_SCREEN_OFF); UploadRecovery.recoverPending(app).join()
        val initial=scheduler.allPendingJobs.filter{!it.isPeriodic}
        assertEquals(1,initial.size)
        assertTrue(initial.all{it.minLatencyMillis>=5000})
        screen(Intent.ACTION_SCREEN_OFF); UploadRecovery.recoverPending(app).join()
        initial.forEach{old->assertSame(old,scheduler.allPendingJobs.single{it.id==old.id})}
    }
    @Test fun `no upload consent means screen events cannot schedule work`() {
        consent(false);UploadRecovery.observe(app);screen(Intent.ACTION_SCREEN_OFF)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun `unlock wakes old pending data without requiring wifi to reconnect`() = kotlinx.coroutines.runBlocking {
        consent(true); pending(); UploadRecovery.observe(app); screen(Intent.ACTION_USER_PRESENT)
        UploadRecovery.recoverPending(app).join()
        assertEquals(1,scheduler.allPendingJobs.count{!it.isPeriodic})
    }
    @Test fun `new events during running work do not replace or cancel that work`() {
        consent(true); UploadRecovery.wake(app)
        val running = scheduler.allPendingJobs.single()
        UploadRecovery.started(running.extras.getString("token"))
        UploadRecovery.wake(app)
        assertSame(running, scheduler.getPendingJob(running.id))
        scheduler.cancel(running.id) // Android removes the completed job before its follow-up.
        UploadRecovery.finished(app)
        assertNotNull(scheduler.getPendingJob(running.id))
    }
    @Test fun `revoking consent cancels pending recovery and later screen events cannot recreate it`() = kotlinx.coroutines.runBlocking {
        consent(true); pending(); UploadRecovery.observe(app); screen(Intent.ACTION_SCREEN_OFF)
        UploadRecovery.recoverPending(app).join()
        assertEquals(1, scheduler.allPendingJobs.size)
        consent(false); UploadRecovery.cancel(app); screen(Intent.ACTION_USER_PRESENT)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

    @Test fun `cancel followed by stale completion cannot recreate work`() {
        consent(true); UploadRecovery.wake(app)
        val running=scheduler.allPendingJobs.single()
        UploadRecovery.started(running.extras.getString("token"))
        UploadRecovery.wake(app,60_000L)
        UploadRecovery.cancel(app)
        UploadRecovery.finished(app)
        UploadRecovery.wake(app,120_000L)
        assertEquals(1,scheduler.allPendingJobs.size)
    }
    @Test fun `stale finished callback cannot stop a newer running token`() {
        consent(true);UploadRecovery.wake(app)
        val old=scheduler.allPendingJobs.single()
        UploadRecovery.started(old.extras.getString("token"))
        UploadRecovery.cancel(app);UploadRecovery.wake(app)
        val current=scheduler.allPendingJobs.single()
        UploadRecovery.started(current.extras.getString("token"))
        UploadRecovery.finished(app,old.extras.getString("token"))
        UploadRecovery.wake(app,60_000L)
        assertSame(current,scheduler.getPendingJob(current.id))
        scheduler.cancel(current.id)
        UploadRecovery.finished(app,current.extras.getString("token"))
        assertEquals(60_000L,scheduler.getPendingJob(current.id)!!.minLatencyMillis)
    }
    @Test fun `multiple categories keep earliest appointment and repeated signals cannot postpone it`() {
        consent(true);UploadRecovery.cancel(app)
        UploadRecovery.wake(app,240_000L)
        UploadRecovery.wake(app,60_000L)
        val earliest=scheduler.allPendingJobs.single()
        UploadRecovery.wake(app,180_000L)
        assertSame(earliest,scheduler.allPendingJobs.single())
        assertEquals(60_000L,earliest.minLatencyMillis)
    }

    @Test fun `stale system stop cannot cancel newly started service work`() {
        consent(true)
        val controller=org.robolectric.Robolectric.buildService(UploadRecoveryJobService::class.java).create()
        val service=controller.get()
        val parent=kotlinx.coroutines.SupervisorJob()
        val paused=object:kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context:kotlin.coroutines.CoroutineContext,block:Runnable)=Unit
        }
        org.robolectric.util.ReflectionHelpers.setField(service,"scope",kotlinx.coroutines.CoroutineScope(parent+paused))
        fun params(token:String):android.app.job.JobParameters {
            val result=org.robolectric.shadow.api.Shadow.newInstanceOf(android.app.job.JobParameters::class.java)
            org.robolectric.util.ReflectionHelpers.setField(result,"extras",android.os.PersistableBundle().apply{putString("token",token)})
            return result
        }
        try {
            val old=params("old");val current=params("current")
            assertTrue(service.onStartJob(old))
            val replaced=org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.Job>(service,"job")
            assertTrue(service.onStartJob(current))
            assertTrue("Starting B must retire the old A coroutine",replaced.isCancelled)
            val running=org.robolectric.util.ReflectionHelpers.getField<kotlinx.coroutines.Job>(service,"job")
            assertFalse(service.onStopJob(old))
            assertTrue("Stale stop must not cancel the new coroutine",running.isActive)
            assertTrue(service.onStopJob(params("current")))
            assertTrue(running.isCancelled)
        } finally { parent.cancel();controller.destroy() }
    }

}
