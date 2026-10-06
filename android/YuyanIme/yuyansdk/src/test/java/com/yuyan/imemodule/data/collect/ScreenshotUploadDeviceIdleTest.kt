package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.test.core.app.ApplicationProvider
import okhttp3.OkHttpClient
import okhttp3.Request
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
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenshotUploadDeviceIdleTest {
    private fun ready(): Pair<Context, NetworkCapabilities> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServerConfig.init(context)
        CollectionConsent.setEnabled(context, true)
        ImageUploadRuntime.noteKeyActivity()
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val connectivity = Shadows.shadowOf(manager)
        connectivity.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        connectivity.setDefaultNetworkActive(true)
        val caps = ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        Shadows.shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        connectivity.setNetworkCapabilities(manager.activeNetwork, caps)
        return context to caps
    }
    @Test fun validatedWifiAndIdleInputAllowScreenshotsWithScreenOnOrOff() {
        val (context, caps) = ready()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val target = ServerConfig.baseUrl
        Shadows.shadowOf(power).setIsInteractive(true)
        assertTrue("亮屏停手也必须允许截图补传", ImageUploadRuntime.canUploadScreenshot(context, target))
        assertTrue(ImageUploadRuntime.canUploadChat(context, target))
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
        assertTrue(ImageUploadRuntime.canUploadScreenshot(context, target))
        assertNull(ImageUploadRuntime.beginPreparation())
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
        assertTrue(ImageUploadRuntime.canUploadScreenshot(context, target))
        assertFalse(ImageUploadRuntime.canUploadScreenshot(context, "http://127.0.0.1:3000"))
    }
    @Test fun wifiScreenshotTransferContinuesWhileTypingButPreparationStaysIdle() {
        val (context, _) = ready()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setIsInteractive(false)
        var chunks = 0
        val body = GuardedChatBody(ByteArray(40_000).toRequestBody(), allowed = { ImageUploadRuntime.canUploadScreenshot(context, ServerConfig.baseUrl) }, pause = {
            if (++chunks == 2) Shadows.shadowOf(power).setIsInteractive(true)
        })
        val sink = Buffer(); body.writeTo(sink)
        assertEquals(40_000L, sink.size)
        chunks = 0
        val typing = GuardedChatBody(ByteArray(40_000).toRequestBody(), allowed = { ImageUploadRuntime.canUploadScreenshot(context, ServerConfig.baseUrl) }, pause = {
            if (++chunks == 2) ImageUploadRuntime.noteKeyActivity()
        })
        val paused = Buffer()
        typing.writeTo(paused)
        assertEquals(40_000L, paused.size)
    }
    @Test fun actualChatAndNavigationRequestsAcceptLitScreenAndGuardInputAndWifi() {
        val (context, caps) = ready()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setIsInteractive(true)
        for (path in listOf("/api/v1/mobile/chat/assets", "/api/v1/mobile/navigation-records")) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
            val request = Request.Builder().url(ServerConfig.baseUrl + path).post(byteArrayOf(1).toRequestBody()).build()
            val call = ImageUploadRuntime.prepareChatCall(context, ServerConfig.baseUrl, OkHttpClient(), request)
            assertNotNull("亮屏时实际截图请求也不能被屏幕门禁拦截", call)
            try {
                context.sendBroadcast(Intent(Intent.ACTION_SCREEN_ON))
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
                assertFalse(call!!.isCanceled())
                val sink = Buffer(); call.request().body!!.writeTo(sink)
                assertEquals(1L, sink.size)
                ImageUploadRuntime.noteKeyActivity()
                if (path.endsWith("/assets")) {
                    call.request().body!!.writeTo(Buffer())
                } else {
                    try { call.request().body!!.writeTo(Buffer()); fail("导航仍须输入空闲") } catch (_: IOException) { }
                }
            } finally { if (call != null) ImageUploadRuntime.finishChatCall(call) }
        }
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001))
        val request = Request.Builder().url(ServerConfig.baseUrl + "/api/v1/mobile/chat/assets").post(byteArrayOf(1).toRequestBody()).build()
        val call = ImageUploadRuntime.prepareChatCall(context, ServerConfig.baseUrl, OkHttpClient(), request)!!
        try {
            Shadows.shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            try { call.request().body!!.writeTo(Buffer()); fail("失去Wi-Fi资格必须中断") } catch (_: IOException) { }
        } finally { ImageUploadRuntime.finishChatCall(call) }
    }
    @Test fun cellularAllowsTextAndNavigationButNeverChatAssets() {
        val (context,caps)=ready()
        Shadows.shadowOf(caps).removeTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        for(path in listOf("/api/v1/mobile/chat/messages/batch", "/api/v1/mobile/navigation-records")) {
            val request=Request.Builder().url(ServerConfig.baseUrl+path).post(byteArrayOf(1).toRequestBody()).build()
            val call=ImageUploadRuntime.prepareChatCall(context,ServerConfig.baseUrl,OkHttpClient(),request)
            assertNotNull(path,call)
            if(call!=null)ImageUploadRuntime.finishChatCall(call)
        }
        val request=Request.Builder().url(ServerConfig.baseUrl+"/api/v1/mobile/chat/assets").post(byteArrayOf(1).toRequestBody()).build()
        assertNull(ImageUploadRuntime.prepareChatCall(context,ServerConfig.baseUrl,OkHttpClient(),request))
        ImageUploadRuntime.noteKeyActivity()
        val nav=request.newBuilder().url(ServerConfig.baseUrl+"/api/v1/mobile/navigation-records").build()
        assertNull(ImageUploadRuntime.prepareChatCall(context,ServerConfig.baseUrl,OkHttpClient(),nav))
    }
    @Test fun screenOffHasLargerBudgetAndLitScreenRequestHasTimeToSendLargeImageSlowly() {
        val (context, _) = ready()
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        Shadows.shadowOf(power).setIsInteractive(true)
        assertEquals(256L, ImageUploadRuntime.chunkPauseMillis(context))
        val litBudget = ImageUploadRuntime.maxImageBytes(context, ServerConfig.baseUrl)
        Shadows.shadowOf(power).setIsInteractive(false)
        assertEquals(16L, ImageUploadRuntime.chunkPauseMillis(context))
        assertTrue(ImageUploadRuntime.maxImageBytes(context, ServerConfig.baseUrl) > litBudget)
        Shadows.shadowOf(power).setIsInteractive(true)
        assertEquals(256L, ImageUploadRuntime.chunkPauseMillis(context))
        val request = Request.Builder().url(ServerConfig.baseUrl + "/api/v1/mobile/navigation-records").post(byteArrayOf(1).toRequestBody()).build()
        val call = ImageUploadRuntime.prepareChatCall(context, ServerConfig.baseUrl, OkHttpClient(), request)!!
        try { assertTrue("4MiB JSON按32KiB/s上传需128秒，不能被旧90秒超时卡死", call.timeout().timeoutNanos() > java.util.concurrent.TimeUnit.SECONDS.toNanos(128)) }
        finally { ImageUploadRuntime.finishChatCall(call) }
    }
}
