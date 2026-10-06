package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import androidx.preference.PreferenceManager
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BackgroundRefreshRuntimeTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val saved = context.getSharedPreferences("background_resource_refresh_v1", Context.MODE_PRIVATE)
    @Before fun prepare() {
        resetImageInputForTest()
        resetGameWorkRuntimeForTest()
        // Robolectric 更换 Application，但单例仍可能持有上一项测试的服务器配置。
        ServerConfig::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
        ServerConfig.init(context)
        saved.edit().clear().commit()
        context.getSharedPreferences("expression_background_sync", Context.MODE_PRIVATE).edit().clear().commit()
        network(true)
    }
    @After fun resetGuards() {
        resetImageInputForTest()
        resetGameWorkRuntimeForTest()
        ServerConfig::class.java.getDeclaredField("prefs").apply { isAccessible = true }.set(null, null)
    }
    private fun network(wifi: Boolean) {
        val shadow = shadowOf(manager)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if (wifi) ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE, 0, true, true))
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(if (wifi) NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadow.setNetworkCapabilities(manager.activeNetwork, caps)
    }
    @Test fun persistedBatchTimeUsesCurrentNetworkAndManualRefreshBypassesIt() {
        saved.edit().putString("source", ServerConfig.baseUrl).putLong("last_attempt_at", System.currentTimeMillis() - 31 * 60_000L).commit()
        network(false)
        assertFalse(BackgroundRefreshRuntime.due(context))
        assertTrue(BackgroundRefreshRuntime.due(context, userInitiated = true))
        network(true)
        assertTrue(BackgroundRefreshRuntime.due(context))
    }
    @Test fun offlineChecksDoNotConsumePersistedQualification() {
        val last = System.currentTimeMillis() - 3 * 60 * 60_000L
        saved.edit().putString("source", ServerConfig.baseUrl).putLong("last_attempt_at", last).commit()
        shadowOf(manager).setActiveNetworkInfo(null)
        assertFalse(BackgroundRefreshRuntime.due(context))
        assertFalse(BackgroundRefreshRuntime.due(context, userInitiated = true))
        assertEquals(last, saved.getLong("last_attempt_at", 0))
        network(false)
        assertTrue(BackgroundRefreshRuntime.due(context))
    }
    @Test fun interruptedBatchDoesNotFallBackToAnOlderModuleAttempt() {
        context.getSharedPreferences("expression_background_sync", Context.MODE_PRIVATE).edit()
            .putLong("last_attempt_at", System.currentTimeMillis()).commit()
        assertFalse(BackgroundRefreshRuntime.due(context))
        saved.edit().putString("source", ServerConfig.baseUrl).remove("last_attempt_at").commit()
        assertTrue(BackgroundRefreshRuntime.due(context))
    }

    @Test fun offlineUsbDictionaryQualificationIsSeparateAndDoesNotPollEveryThirtySeconds() {
        shadowOf(manager).setActiveNetworkInfo(null)
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB))
        assertTrue(BackgroundRefreshRuntime.usbDue(context))
        assertFalse(BackgroundRefreshRuntime.due(context))
        saved.edit().putLong("usb_last_attempt_at", System.currentTimeMillis() - 30_000L).commit()
        assertFalse(BackgroundRefreshRuntime.usbDue(context))
        assertTrue(BackgroundRefreshRuntime.usbDue(context, userInitiated = true))
        saved.edit().putLong("usb_last_attempt_at", System.currentTimeMillis() - 31 * 60_000L).commit()
        assertTrue(BackgroundRefreshRuntime.usbDue(context))
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_PLUGGED, 0))
        assertFalse(BackgroundRefreshRuntime.usbDue(context, userInitiated = true))
    }

    @Test fun inputDuringUsbHealthCheckReleasesQualificationAfterNormalReturn() {
        usbHealthCheck { ImageUploadRuntime.noteKeyActivity() }
        assertFalse(saved.contains("usb_last_attempt_at"))
        resetImageInputForTest()
        assertTrue(BackgroundRefreshRuntime.usbDue(context))
    }

    @Test fun gameDuringUsbHealthCheckReleasesQualificationAfterNormalReturn() {
        usbHealthCheck { GameWorkRuntime.setGaming(true) }
        assertFalse(saved.contains("usb_last_attempt_at"))
        resetGameWorkRuntimeForTest()
        assertTrue(BackgroundRefreshRuntime.usbDue(context))
    }

    @Test fun ordinaryUsbHealthFailureKeepsThirtyMinuteQualification() {
        usbHealthCheck { }
        assertTrue(saved.contains("usb_last_attempt_at"))
        assertFalse(BackgroundRefreshRuntime.usbDue(context))
    }

    private fun usbHealthCheck(onRequest: () -> Unit) {
        shadowOf(manager).setActiveNetworkInfo(null)
        context.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB))
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    onRequest()
                    return MockResponse().setResponseCode(503)
                }
            }
            server.start()
            val localTarget = server.url("/").toString().replace("localhost", "127.0.0.1").trimEnd('/')
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                .putBoolean(CollectionConsent.KEY, true)
                .putString("server_url", localTarget).commit()
            assertTrue(ServerConfig.eventTargets.contains(localTarget))
            assertTrue(ImageUploadRuntime.isBackgroundWorkAllowed())
            runBlocking { BackgroundRefreshRuntime.refreshUsbResources(context) }
            assertEquals(1, server.requestCount)
        }
    }
}
