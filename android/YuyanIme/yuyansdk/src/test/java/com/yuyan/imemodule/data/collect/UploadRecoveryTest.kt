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

    @Test fun `screen off hands off both queues and repeated broadcasts do not postpone jobs`() {
        consent(true);UploadRecovery.observe(app)
        screen(Intent.ACTION_SCREEN_OFF)
        val initial=scheduler.allPendingJobs.filter{!it.isPeriodic}
        assertEquals(1,initial.size)
        assertTrue(initial.all{it.minLatencyMillis>=5000})
        screen(Intent.ACTION_SCREEN_OFF)
        initial.forEach{old->assertSame(old,scheduler.allPendingJobs.single{it.id==old.id})}
    }
    @Test fun `no upload consent means screen events cannot schedule work`() {
        consent(false);UploadRecovery.observe(app);screen(Intent.ACTION_SCREEN_OFF)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun `unlock wakes old pending data without requiring wifi to reconnect`() {
        consent(true);UploadRecovery.observe(app);screen(Intent.ACTION_USER_PRESENT)
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
    @Test fun `revoking consent cancels pending recovery and later screen events cannot recreate it`() {
        consent(true); UploadRecovery.observe(app); screen(Intent.ACTION_SCREEN_OFF)
        assertEquals(1, scheduler.allPendingJobs.size)
        consent(false); UploadRecovery.cancel(app); screen(Intent.ACTION_USER_PRESENT)
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }

}
