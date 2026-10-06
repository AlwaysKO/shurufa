package com.yuyan.imemodule.data.collect

import android.content.Context
import android.net.*
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.adapter.*
import okhttp3.*
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.annotation.*
import org.robolectric.shadows.*
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30], shadows=[ChatDiagnosticTimeoutTest.NetworkShadow::class])
class ChatDiagnosticTimeoutTest {
    @Implements(Network::class) class NetworkShadow {
        @Implementation fun getSocketFactory(): SocketFactory = SocketFactory.getDefault()
        @Implementation fun getAllByName(host: String): Array<InetAddress> = InetAddress.getAllByName(host)
    }
    @Before @After fun reset() { resetImageInputForTest(); resetGameWorkRuntimeForTest() }
    private fun ready(): Context {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ServerConfig.init(context); CollectionConsent.setEnabled(context,true)
        val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow=Shadows.shadowOf(manager)
        shadow.setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_WIFI,0,true,true))
        shadow.setDefaultNetworkActive(true)
        val caps=ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(caps).apply { addTransportType(NetworkCapabilities.TRANSPORT_WIFI); addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET); addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        shadow.setNetworkCapabilities(manager.activeNetwork,caps)
        return context
    }
    @Test fun diagnosticsRetainFiveSecondDeadlineWhileOrdinaryChatKeepsFiveMinutes() {
        val context=ready()
        val client=OkHttpClient.Builder().callTimeout(5,TimeUnit.SECONDS).build()
        val request=Request.Builder().url("http://127.0.0.1/diagnostics").post("{}".toRequestBody()).build()
        val ordinary=ImageUploadRuntime.prepareChatCall(context,ServerConfig.baseUrl,client,request)!!
        val diagnostic=ImageUploadRuntime.prepareChatCall(context,ServerConfig.baseUrl,client,request,preserveCallTimeout=true)!!
        try {
            assertEquals(TimeUnit.MINUTES.toNanos(5),ordinary.timeout().timeoutNanos())
            assertEquals(TimeUnit.SECONDS.toNanos(5),diagnostic.timeout().timeoutNanos())
        } finally { ordinary.cancel(); diagnostic.cancel(); ImageUploadRuntime.finishChatCall(ordinary); ImageUploadRuntime.finishChatCall(diagnostic) }
    }

}
