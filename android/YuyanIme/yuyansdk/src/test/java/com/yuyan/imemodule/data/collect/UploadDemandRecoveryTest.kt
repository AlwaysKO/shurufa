package com.yuyan.imemodule.data.collect

import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UploadDemandRecoveryTest {
    @Before fun resetCachedQueue() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val field = DataCollector::class.java.getDeclaredField("eventStore").apply { isAccessible = true }
        (field.get(null) as? LocalInputStore)?.close()
        field.set(null, null)
        app.deleteDatabase("local_input.db")
    }

    @After fun releaseCachedQueue() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, false).commit()
        UploadRecovery.cancel(app)
        val scope = UploadRecovery::class.java.getDeclaredField("recoveryScope").apply { isAccessible = true }
            .get(null) as kotlinx.coroutines.CoroutineScope
        kotlinx.coroutines.runBlocking {
            scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
        }
        val field = DataCollector::class.java.getDeclaredField("eventStore").apply { isAccessible = true }
        (field.get(null) as? LocalInputStore)?.close()
        field.set(null, null)
        app.deleteDatabase("local_input.db")
    }

    @Test fun emptyScreenAndUnlockSignalsDoNotCreateAnUploadJob() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true).commit()
        UploadRecovery.cancel(app)
        assertNull("Fixture must have no durable upload before screen signals", DataCollector.pendingRecovery(app))
        UploadRecovery.observe(app)
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).idle()
        app.sendBroadcast(Intent(Intent.ACTION_USER_PRESENT))
        shadowOf(Looper.getMainLooper()).idle()
        UploadRecovery.recoverPending(app).join()
        assertTrue(scheduler.allPendingJobs.isEmpty())
    }
    @Test fun imageOnlyRecoveryWaitsForWifiAndNormalPendingCanUpgradeIt() {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true).commit()
        try {
            UploadRecovery.cancel(app)
            UploadRecovery.wake(app, 6_000L, wifiOnly = true)
            val image = scheduler.allPendingJobs.single()
            assertEquals(android.app.job.JobInfo.NETWORK_TYPE_UNMETERED, image.networkType)
            assertTrue(image.isPersisted)
            UploadRecovery.wake(app, 300_000L, wifiOnly = false)
            val combined = scheduler.allPendingJobs.single()
            assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, combined.networkType)
            UploadRecovery.wake(app, 6_000L, wifiOnly = true)
            assertSame(combined, scheduler.allPendingJobs.single())
        } finally { UploadRecovery.cancel(app) }
    }

    @Test fun pausedImageQueueUsesAtLeastFiveMinutesInsteadOfSixSecondJobs() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true).commit()
        ServerConfig.init(app)
        val store = LocalInputStore(app)
        try {
            store.enqueueReport(PendingReport("paused-image", "chat_asset",
                """{"sha256":"${"a".repeat(64)}","file_base64":"AA=="}"""), listOf(ServerConfig.baseUrl))
        } finally { store.close() }
        try {
            UploadRecovery.cancel(app)
            GameWorkRuntime.setGaming(true)
            UploadRecovery.scheduleNext(app)
            val job = scheduler.allPendingJobs.single()
            assertTrue(job.minLatencyMillis >= 300_000L)
            assertEquals(android.app.job.JobInfo.NETWORK_TYPE_UNMETERED, job.networkType)
        } finally { GameWorkRuntime.setGaming(false); UploadRecovery.cancel(app) }
    }

    @Test fun typingDoesNotPostponeAlreadyPreparedChatImages() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true).commit()
        ServerConfig.init(app)
        val store = LocalInputStore(app)
        try {
            store.enqueueReport(PendingReport("ready-image", "chat_asset",
                """{"sha256":"${"b".repeat(64)}","file_base64":"AA=="}"""), listOf(ServerConfig.baseUrl))
        } finally { store.close() }
        resetGameWorkRuntimeForTest()
        resetImageInputForTest()
        try {
            UploadRecovery.cancel(app)
            ImageUploadRuntime.noteKeyActivity()
            assertFalse(ImageUploadRuntime.isInputIdle())
            UploadRecovery.scheduleNext(app)
            val job = scheduler.allPendingJobs.single()
            assertEquals(6_000L, job.minLatencyMillis)
            assertEquals(android.app.job.JobInfo.NETWORK_TYPE_UNMETERED, job.networkType)
        } finally { resetImageInputForTest(); UploadRecovery.cancel(app) }
    }

    @Test fun mixedUsageAndImageRecoveryDoesNotPollImagesOnMobileNetwork() = kotlinx.coroutines.runBlocking {
        val app = ApplicationProvider.getApplicationContext<Context>()
        val scheduler = app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(CollectionConsent.KEY, true)
            .putBoolean(com.yuyan.imemodule.data.usage.AppUsageTracker.KEY, true).commit()
        val ops = app.getSystemService(Context.APP_OPS_SERVICE) as android.app.AppOpsManager
        shadowOf(ops).setMode(android.app.AppOpsManager.OPSTR_GET_USAGE_STATS,
            android.os.Process.myUid(), app.packageName, android.app.AppOpsManager.MODE_ALLOWED)
        ServerConfig.init(app)
        val store = LocalInputStore(app)
        try { store.enqueueReport(PendingReport("image-with-usage", "chat_asset", "{}"), listOf(ServerConfig.baseUrl)) }
        finally { store.close() }
        com.yuyan.imemodule.data.usage.UsageStore(app).use { usage ->
            val now = System.currentTimeMillis()
            usage.save(com.yuyan.imemodule.data.usage.UsageReduction(
                com.yuyan.imemodule.data.usage.UsageState(cursor = now), listOf(
                    com.yuyan.imemodule.data.usage.UsageRecord("pending-usage", "usage", "com.example.reader", null,
                        now - 10_000L, now, "lock"))), listOf(ServerConfig.baseUrl))
        }
        resetGameWorkRuntimeForTest(); resetImageInputForTest()
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        shadowOf(manager).setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED, android.net.ConnectivityManager.TYPE_MOBILE, 0, true, true))
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).apply {
            addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, caps)
        try {
            assertTrue(ImageUploadRuntime.hasValidatedNetwork(app))
            assertFalse(ImageUploadRuntime.hasValidatedWifi(app))
            UploadRecovery.cancel(app)
            UploadRecovery.scheduleNext(app)
            val job = scheduler.allPendingJobs.single()
            assertEquals(300_000L, job.minLatencyMillis)
            assertEquals(android.app.job.JobInfo.NETWORK_TYPE_ANY, job.networkType)
        } finally {
            UploadRecovery.cancel(app)
            app.deleteDatabase(java.io.File(app.noBackupFilesDir, "app_usage.db").absolutePath)
        }
    }

}
