package com.yuyan.imemodule.data.collect

import android.content.Context
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowSystemClock
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenshotUploadDeviceIdleTest {
    @Test fun sharedUploadGateRequiresValidatedWifiScreenOffConsentAndIdleInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServerConfig.init(context)
        CollectionConsent.setEnabled(context, true)
        ImageUploadRuntime.noteKeyActivity()
        ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(3001))
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connectivity = Shadows.shadowOf(manager)
        connectivity.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        connectivity.setDefaultNetworkActive(true)
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connectivity.setNetworkCapabilities(manager.activeNetwork, caps)
        val target = ServerConfig.baseUrl
        Shadows.shadowOf(power).setIsInteractive(true)
        assertFalse(ImageUploadRuntime.canUploadScreenshot(context, target))
        assertTrue("纯聊天元数据保持原同步时机", ImageUploadRuntime.canUploadChat(context, target))
        Shadows.shadowOf(power).setIsInteractive(false)
        assertTrue(ImageUploadRuntime.canUploadScreenshot(context, target))
        Shadows.shadowOf(caps).removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        assertFalse(ImageUploadRuntime.canUploadScreenshot(context, target))
        Shadows.shadowOf(caps).removeTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertFalse(ImageUploadRuntime.canUploadScreenshot(context, target))
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        CollectionConsent.setEnabled(context, false)
        assertFalse(ImageUploadRuntime.canUploadScreenshot(context, target))
        CollectionConsent.setEnabled(context, true)
        ImageUploadRuntime.noteKeyActivity()
        assertFalse(ImageUploadRuntime.canUploadScreenshot(context, target))
        assertFalse(ImageUploadRuntime.canUploadScreenshot(context, "http://127.0.0.1:3000"))
    }
    @Test fun illuminatedScreenBlocksUploadsEvenWhenKeyboardIsIdle() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setIsInteractive(true)
        assertFalse(ImageUploadRuntime.isDeviceIdle(context))
        Shadows.shadowOf(power).setIsInteractive(false)
        assertTrue(ImageUploadRuntime.isDeviceIdle(context))
    }
    @Test fun wakingScreenInterruptsChunkedUploadBeforeRemainingImageIsWritten() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setIsInteractive(false)
        var chunks = 0
        val body = GuardedChatBody(ByteArray(40_000).toRequestBody(), allowed = { ImageUploadRuntime.isDeviceIdle(context) }, pause = {
            if (++chunks == 2) Shadows.shadowOf(power).setIsInteractive(true)
        })
        val sink = Buffer()
        try { body.writeTo(sink); fail("亮屏后必须暂停截图上传") } catch (_: IOException) { }
        assertEquals(8192L, sink.size)
    }
}
