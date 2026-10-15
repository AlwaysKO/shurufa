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
@Config(sdk=[30])
class VideoVisitSyncTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val manager=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    @Before fun setup() {
        val cadence=VideoVisitSync::class.java.getDeclaredField("cadence").apply { isAccessible=true }.get(null)
        cadence.javaClass.getDeclaredField("lastStarted").apply { isAccessible=true }.set(cadence,null)
        app.deleteDatabase(java.io.File(app.noBackupFilesDir,"video_visits.db").absolutePath)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,true).commit()
        ServerConfig.init(app)
        GameWorkRuntime.setGaming(true); GameWorkRuntime.setGaming(false)
        ImageUploadRuntime.finishInput()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        network(true)
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
    private fun pending(): VideoVisit = VideoVisitStore(app).use { store ->
        val now=System.currentTimeMillis()
        val row=store.enter("wechat","test",100,now-100).active
        store.finish(row.id,200,now,VideoExitReason.LOCKED)!!
    }
    @Test fun mobileMetadataIsDueWithoutPermittingImageUploads() {
        pending(); network(false)
        assertTrue(VideoVisitSync.hasDue(app))
        assertFalse(ImageUploadRuntime.canUploadScreenshot(app,ServerConfig.baseUrl))
    }
    @Test fun emptyFlushAndDelayQueriesDoNotConsumeFirstBatch()=runBlocking {
        VideoVisitSync.flush(app)
        assertNull(VideoVisitSync.nextUploadDelay(app))
        pending()
        repeat(2) { assertEquals(5000L,VideoVisitSync.nextUploadDelay(app)) }
        assertTrue(VideoVisitSync.hasDue(app))
    }
    @Test fun attemptsWaitFiveMinutesAcrossNetworkChangesAndQueries() {
        pending()
        val cadence=VideoVisitSync::class.java.getDeclaredField("cadence").apply { isAccessible=true }.get(null) as CollectorSyncCadence
        assertTrue(cadence.tryStart(true))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        network(false)
        assertEquals(240_000L,VideoVisitSync.nextUploadDelay(app))
        assertFalse(VideoVisitSync.hasDue(app))
        ShadowSystemClock.advanceBy(Duration.ofMillis(239_999))
        assertFalse(VideoVisitSync.hasDue(app))
        ShadowSystemClock.advanceBy(Duration.ofMillis(1))
        assertTrue(VideoVisitSync.hasDue(app))
        assertEquals(5000L,VideoVisitSync.nextUploadDelay(app))
    }
    @Test fun deferredMetadataStillSchedulesButInputAndConsentStopScheduling() {
        val row=pending()
        VideoVisitStore(app).use { it.defer(row,System.currentTimeMillis()) }
        // System.currentTimeMillis 在此运行器仍会随真实 SQLite 查询推进数毫秒；精确边界由 Store 的假时钟用例覆盖。
        assertTrue(requireNotNull(VideoVisitSync.nextUploadDelay(app)) in 29_000L..30_000L)
        ImageUploadRuntime.noteKeyActivity()
        assertNull(VideoVisitSync.nextUploadDelay(app))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,false).commit()
        assertNull(VideoVisitSync.nextUploadDelay(app))
    }
}
