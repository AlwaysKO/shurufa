package com.yuyan.imemodule.data.redpacket

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import com.yuyan.redpacket.silent.ISilentPacketService
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SilentPacketRuntimeNativeRoutingTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val publicStarts = AtomicInteger()
    private val privateStarts = AtomicInteger()
    private val launches = AtomicInteger()
    private val screenshots = AtomicInteger()
    private val users = mutableListOf<Int>()
    private fun field(name: String) = SilentPacketRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(SilentPacketRuntime, value)
    private fun handler() = field("main").get(SilentPacketRuntime) as Handler
    @Suppress("UNCHECKED_CAST")
    private fun queue() = field("queued").get(SilentPacketRuntime) as ArrayDeque<PacketRequest>

    @Before fun prepare() {
        handler().removeCallbacksAndMessages(null)
        for ((name, value) in listOf("context" to app, "foregroundVerified" to true, "job" to null,
            "active" to null, "pendingDiagnostic" to null, "pendingNoticeDiagnostic" to null,
            "stopsInFlight" to 0, "retryScheduled" to false)) set(name, value)
        queue().clear()
        val gate = field("startGate").get(SilentPacketRuntime)
        gate.javaClass.getDeclaredField("deferredAt").apply { isAccessible = true }.set(gate, null)
        PacketServiceState.accessibilityConnected = true
        PacketServiceState.notificationConnected = true
        SilentPacketSettings.setAutoUser(app, false)
        SilentPacketSettings.setAutoGroups(app, false)
        SilentPacketSettings.setUserId(app, 128)
        SilentPacketSettings.setGroups(app, "测试群")
        SilentPacketSettings.setMode(app, "AUTO")
        app.getSharedPreferences("silent_packet_receipts", Context.MODE_PRIVATE).edit().clear().commit()
        val service = Proxy.newProxyInstance(ISilentPacketService::class.java.classLoader,
            arrayOf(ISilentPacketService::class.java)) { _, method, args ->
            when (method.name) {
                "start" -> { privateStarts.incrementAndGet(); "{\"state\":\"RUNNING\"}" }
                "startForNotification" -> {
                    publicStarts.incrementAndGet(); synchronized(users) { users += args!![0] as Int }
                    "{\"state\":\"RUNNING\"}"
                }
                "launch" -> { launches.incrementAndGet(); false }
                "capture", "captureFrame" -> { screenshots.incrementAndGet(); null }
                else -> null
            }
        } as ISilentPacketService
        set("service", service)
    }
    @After fun cleanUp() {
        val job = field("job").get(SilentPacketRuntime) as Job?
        job?.cancel()
        runBlocking { withTimeout(5000) { job?.join() } }
        handler().removeCallbacksAndMessages(null)
        set("context", null); set("service", null); set("job", null); set("active", null)
        set("foregroundVerified", false); set("stopsInFlight", 0)
        queue().clear()
        PacketServiceState.accessibilityConnected = false
        PacketServiceState.notificationConnected = false
        SilentPacketSettings.setMode(app, "OFF")
    }
    private fun runTask(mode: String) {
        SilentPacketSettings.setMode(app, mode)
        queue().addLast(PacketRequest(PacketCandidate("b".repeat(64), "测试群", true),
            PendingIntent.getActivity(app, 44, Intent("native-routing-test"), PendingIntent.FLAG_IMMUTABLE),
            System.currentTimeMillis(), targetUserId = 128))
        SilentPacketRuntime::class.java.getDeclaredMethod("drain").apply { isAccessible = true }.invoke(SilentPacketRuntime)
        val task = field("job").get(SilentPacketRuntime) as Job
        runBlocking { withTimeout(5000) { task.join() } }
    }
    @Test fun automaticNotificationUsesAccessiblePublicSessionAndExactSourceProfile() {
        runTask("AUTO")
        assertEquals(1, publicStarts.get()); assertEquals(0, privateStarts.get())
        assertEquals(listOf(128), users); assertEquals(1, launches.get()); assertEquals(0, screenshots.get())
    }
    @Test fun notificationProbeAlsoUsesNativePublicSessionWithoutOcrFallback() {
        runTask("PROBE")
        assertEquals(1, publicStarts.get()); assertEquals(0, privateStarts.get())
        assertEquals(0, screenshots.get())
    }
}
