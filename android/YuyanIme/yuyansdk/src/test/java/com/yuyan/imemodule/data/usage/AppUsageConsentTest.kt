package com.yuyan.imemodule.data.usage

import android.app.Application
import android.app.AppOpsManager
import android.app.job.JobScheduler
import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28,35])
class AppUsageConsentTest {
    private lateinit var app: Application
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        app.deleteDatabase(java.io.File(app.noBackupFilesDir,"app_usage.db").absolutePath)
        val ops=app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_IGNORED)
    }
    private fun permission() {
        val ops=app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_ALLOWED)
    }
    @Test fun `default off and both consent and system permission required`() {
        permission()
        assertFalse(AppUsageTracker.enabled(app))
        assertFalse(AppUsageTracker.setEnabled(app,true,1000))
        CollectionConsent.setEnabled(app,true)
        assertTrue(AppUsageTracker.setEnabled(app,true,1000))
        assertTrue(AppUsageTracker.enabled(app))
        UsageStore(app).use { assertEquals(1000L,it.state()!!.cursor) }
    }
    @Test fun `enable without usage access does not start collection`() {
        CollectionConsent.setEnabled(app,true)
        assertFalse(AppUsageTracker.setEnabled(app,true,1000))
        assertFalse(AppUsageTracker.enabled(app))
    }
    @Test fun `master switch immediately invalidates open session`() {
        permission(); CollectionConsent.setEnabled(app,true)
        AppUsageTracker.setEnabled(app,true,1000)
        UsageStore(app).use { it.save(UsageReduction(UsageState(cursor=1200,active=UsageActive("a","one",1100)),emptyList()),listOf("local")) }
        CollectionConsent.setEnabled(app,false)
        UsageStore(app).use { assertNull(it.state()!!.active); assertTrue(it.state()!!.suspended) }
    }
    @Test fun `sampling system history ignores preconsent events and repeated polls deduplicate`() {
        permission(); CollectionConsent.setEnabled(app,true)
        AppUsageTracker.setEnabled(app,true,1000)
        val manager=app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        shadowOf(manager).addEvent("before",500,1)
        shadowOf(manager).addEvent("before",600,2)
        shadowOf(manager).addEvent("after",1100,1)
        shadowOf(manager).addEvent("after",5100,2)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(11000))
        AppUsageTracker.sample(app,12000)
        AppUsageTracker.sample(app,12000)
        UsageStore(app).use { store ->
            val records=store.pending(store.targets().first())
            assertEquals(1,records.size)
            assertEquals("after",records.single().packageName)
            assertEquals(1100L,records.single().startMs)
            assertEquals(5100L,records.single().endMs)
        }
    }
    @Test fun `sampling persists only long app usage and returning home closes the preceding app`() {
        permission(); CollectionConsent.setEnabled(app,true)
        AppUsageTracker.setEnabled(app,true,1000)
        val home="custom.home"
        shadowOf(app.packageManager).addResolveInfoForIntent(
            android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_HOME),
            android.content.pm.ResolveInfo().apply { activityInfo=android.content.pm.ActivityInfo().apply { packageName=home; name="Home" } },
        )
        val manager=app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        shadowOf(manager).addEvent("app",1100,1)
        shadowOf(manager).addEvent(home,6100,1)
        shadowOf(manager).addEvent("short",16100,1)
        shadowOf(manager).addEvent("long",19100,1)
        shadowOf(manager).addEvent("long",22101,2)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(30000))
        AppUsageTracker.sample(app,31000)
        UsageStore(app).use { store ->
            val records=store.targets().flatMap { store.pending(it) }.distinctBy { it.id }
            assertEquals(listOf("app","long"),records.map { it.packageName })
            assertEquals(listOf(5000L,3001L),records.map { it.endMs-it.startMs })
            assertEquals(26000L,store.state()!!.cursor)
        }
    }
    @Test fun `closing remains effective even if usage storage is broken`() {
        permission(); CollectionConsent.setEnabled(app,true)
        AppUsageTracker.setEnabled(app,true,1000)
        UsageStore(app).use { it.writableDatabase.execSQL("DROP TABLE state") }
        assertTrue(AppUsageTracker.setEnabled(app,false,2000))
        assertFalse(AppUsageTracker.enabled(app))
    }
    @Test fun `master closing cannot prevent other collectors stopping when usage storage fails`() {
        permission(); CollectionConsent.setEnabled(app,true)
        AppUsageTracker.setEnabled(app,true,1000)
        UsageStore(app).use { it.writableDatabase.execSQL("DROP TABLE state") }
        CollectionConsent.setEnabled(app,false)
        assertFalse(CollectionConsent.enabled(app))
        assertFalse(AppUsageTracker.enabled(app))
    }
    @Test fun `system event arriving after a query is recovered within settling window`() {
        permission(); CollectionConsent.setEnabled(app,true)
        AppUsageTracker.setEnabled(app,true,1000)
        val manager=app.getSystemService(Context.USAGE_STATS_SERVICE) as android.app.usage.UsageStatsManager
        shadowOf(manager).addEvent("pkg",1100,1)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(6000))
        AppUsageTracker.sample(app,7000)
        shadowOf(manager).addEvent("pkg",6999,2)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(7000))
        AppUsageTracker.sample(app,14000)
        UsageStore(app).use { store ->
            val records=store.targets().flatMap { store.pending(it) }.distinctBy { it.id }
            assertEquals(1,records.size)
            assertEquals(6999L,records.single().endMs)
        }
    }
    @Test fun `settling after first enable cannot move cursor backward`() {
        permission(); CollectionConsent.setEnabled(app,true)
        AppUsageTracker.setEnabled(app,true,1000)
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(2000))
        AppUsageTracker.sample(app,3000)
        UsageStore(app).use { assertEquals(1000L,it.state()!!.cursor); assertTrue(it.targets().isEmpty()) }
    }
    @Test fun `acknowledgement must contain explicit matching batch count`() {
        assertTrue(AppUsageTracker.acceptsReceipt("{\"ok\":true,\"received\":2}",2))
        assertFalse(AppUsageTracker.acceptsReceipt("{\"ok\":true,\"discarded\":true,\"received\":2}",2))
        assertFalse(AppUsageTracker.acceptsReceipt("{\"ok\":true}",2))
        assertFalse(AppUsageTracker.acceptsReceipt("{\"ok\":true,\"received\":1}",2))
        assertFalse(AppUsageTracker.acceptsReceipt("not json",2))
    }
    @Test fun `offline system job remains scheduled and disabling cancels it`() {
        permission(); CollectionConsent.setEnabled(app,true)
        AppUsageTracker.setEnabled(app,true,1000)
        val scheduler=app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val job=scheduler.allPendingJobs.single { it.id==UsageSyncJobService.JOB_ID }
        assertEquals(android.app.job.JobInfo.NETWORK_TYPE_NONE,job.networkType)
        AppUsageTracker.setEnabled(app,false,2000)
        assertFalse(AppUsageTracker.enabled(app))
        assertFalse(scheduler.allPendingJobs.any { it.id==UsageSyncJobService.JOB_ID })
        UsageStore(app).use { assertEquals(2000L,it.state()!!.cursor); assertNull(it.state()!!.active) }
    }
}
