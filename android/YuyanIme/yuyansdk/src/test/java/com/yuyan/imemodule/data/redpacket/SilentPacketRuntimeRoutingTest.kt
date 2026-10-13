package com.yuyan.imemodule.data.redpacket

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.redpacket.silent.ISilentPacketService
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy
import java.util.concurrent.CopyOnWriteArrayList

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SilentPacketRuntimeRoutingTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val starts = CopyOnWriteArrayList<Int>()
    private fun field(name: String) = SilentPacketRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(SilentPacketRuntime, value)
    private fun handler() = field("main").get(SilentPacketRuntime) as Handler
    @Suppress("UNCHECKED_CAST") private fun queue() = field("queued").get(SilentPacketRuntime) as ArrayDeque<PacketRequest>
    @Before fun prepare() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        handler().removeCallbacksAndMessages(null)
        set("foregroundVerified", true)
        set("context", app); set("job", null); set("active", null); set("pendingDiagnostic", null)
        set("stopsInFlight", 0); set("retryScheduled", false); queue().clear()
        for (name in listOf("startGate")) {
            val gate = field(name).get(SilentPacketRuntime)
            gate.javaClass.getDeclaredField("deferredAt").apply { isAccessible = true }.set(gate, null)
        }
        PacketServiceState.accessibilityConnected = true
        PacketServiceState.notificationConnected = true
        SilentPacketSettings.setMode(app, "AUTO")
        SilentPacketSettings.setAutoUser(app, true)
        SilentPacketSettings.setAutoGroups(app, true)
        app.getSharedPreferences("silent_packet_receipts", Context.MODE_PRIVATE).edit().clear().commit()
        val fake = Proxy.newProxyInstance(ISilentPacketService::class.java.classLoader,
            arrayOf(ISilentPacketService::class.java)) { _, method, args ->
            when (method.name) {
                "startForNotification" -> { starts.add(args!![0] as Int); "{\"state\":\"MAIN_FOCUS_UNKNOWN\"}" }
                "launch", "tap", "back" -> false
                else -> null
            }
        } as ISilentPacketService
        set("service", fake)
    }
    @After fun cleanUp() {
        val task = field("job").get(SilentPacketRuntime) as Job?
        task?.cancel(); runBlocking { withTimeout(5_000) { task?.join() } }
        handler().removeCallbacksAndMessages(null)
        set("context", null); set("job", null); set("service", null); set("active", null); queue().clear()
        PacketServiceState.accessibilityConnected = false
        PacketServiceState.notificationConnected = false
        SilentPacketSettings.setMode(app, "OFF")
    }
    private fun runRequest(target: Int, group: String = "测试群") = runBlocking {
        queue().addLast(PacketRequest(PacketCandidate((target+2).toString(16).padStart(64,'a'), group, true),
            PendingIntent.getActivity(app, target, Intent("route-test"), PendingIntent.FLAG_IMMUTABLE),
            System.currentTimeMillis(), targetUserId = target))
        SilentPacketRuntime::class.java.getDeclaredMethod("drain").apply { isAccessible = true }.invoke(SilentPacketRuntime)
        val task = field("job").get(SilentPacketRuntime) as Job?
        withTimeout(5_000) { task?.join() }
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun automaticModeUsesEachRequestsRealProfileWithoutSelectingOneAccount() {
        runRequest(0); runRequest(128)
        assertEquals(listOf(0,128), starts.toList())
        assertEquals(-1, SilentPacketSettings.userId(app))
    }
    @Test fun manualModeRejectsAnIncomingRequestForTheOtherProfile() {
        SilentPacketSettings.setUserId(app, 0)
        SilentPacketSettings.setAutoUser(app, false)
        runRequest(128)
        assertTrue(starts.isEmpty())
    }
    @Test fun automaticAccountStillRespectsAnExplicitGroupWhitelist() {
        SilentPacketSettings.setAutoGroups(app, false)
        SilentPacketSettings.setGroups(app, "限定群")
        runRequest(128, "其他群"); runRequest(128, "限定群")
        assertEquals(listOf(128), starts.toList())
    }
    @Test fun automaticModeRejectsRequestsWithoutVerifiedProfileMetadata() {
        runRequest(-1)
        assertTrue(starts.isEmpty())
    }
}
