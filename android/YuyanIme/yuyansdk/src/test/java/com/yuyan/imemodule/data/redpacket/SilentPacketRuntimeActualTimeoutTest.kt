package com.yuyan.imemodule.data.redpacket

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
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
import org.robolectric.shadows.ShadowSystemClock
import java.lang.reflect.Proxy
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SilentPacketRuntimeActualTimeoutTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val entered = CountDownLatch(1)
    private val release = CountDownLatch(1)
    private val stopped = CountDownLatch(1)
    private val stops = AtomicInteger()
    private val launches = AtomicInteger()
    private var blockedMethod = "startForNotification"
    private var startState = "RUNNING"
    private fun field(name: String) = SilentPacketRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(SilentPacketRuntime, value)
    private fun handler() = field("main").get(SilentPacketRuntime) as Handler
    @Suppress("UNCHECKED_CAST")
    private fun queue() = field("queued").get(SilentPacketRuntime) as ArrayDeque<PacketRequest>

    @Before fun prepare() {
        handler().removeCallbacksAndMessages(null)
        set("foregroundVerified", true)
        set("context", app)
        set("job", null)
        set("active", null)
        set("pendingDiagnostic", null)
        set("stopsInFlight", 0)
        set("retryScheduled", false)
        queue().clear()
        for (name in listOf("startGate")) {
            val gate = field(name).get(SilentPacketRuntime)
            gate.javaClass.getDeclaredField("deferredAt").apply { isAccessible = true }.set(gate, null)
        }
        PacketServiceState.accessibilityConnected = true
        PacketServiceState.notificationConnected = true
        SilentPacketSettings.setMode(app, "AUTO")
        SilentPacketSettings.setUserId(app, 0)
        SilentPacketSettings.setGroups(app, "测试群")
        app.getSharedPreferences("silent_packet_receipts", Context.MODE_PRIVATE).edit().clear().commit()
        val fake = Proxy.newProxyInstance(ISilentPacketService::class.java.classLoader,
            arrayOf(ISilentPacketService::class.java)) { _, method, _ ->
            if (method.name == blockedMethod) {
                entered.countDown()
                release.await(10, TimeUnit.SECONDS)
            }
            when (method.name) {
                "startForNotification" -> "{\"state\":\"$startState\"}"
                "launch" -> { launches.incrementAndGet(); false }
                "stop" -> { stops.incrementAndGet(); stopped.countDown(); null }
                else -> null
            }
        } as ISilentPacketService
        set("service", fake)
    }

    @After fun cleanUp() {
        release.countDown()
        val task = field("job").get(SilentPacketRuntime) as Job?
        task?.cancel()
        runBlocking { withTimeout(5_000) { task?.join() } }
        handler().removeCallbacksAndMessages(null)
        set("context", null)
        set("job", null)
        set("service", null)
        set("active", null)
        queue().clear()
        PacketServiceState.accessibilityConnected = false
        PacketServiceState.notificationConnected = false
        SilentPacketSettings.setMode(app, "OFF")
    }

    private fun begin(): Job {
        queue().addLast(PacketRequest(PacketCandidate("a".repeat(64), "测试群", true),
            PendingIntent.getActivity(app, 0, Intent("actual-timeout-test"), PendingIntent.FLAG_IMMUTABLE),
            System.currentTimeMillis()))
        SilentPacketRuntime::class.java.getDeclaredMethod("drain").apply { isAccessible = true }.invoke(SilentPacketRuntime)
        val task = field("job").get(SilentPacketRuntime) as Job
        assertTrue("actual task must enter fake service", entered.await(2, TimeUnit.SECONDS))
        return task
    }

    private fun assertIndependentDeadline(method: String) {
        blockedMethod = method
        val task = begin()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(24_999))
        assertEquals(0, stops.get())
        assertFalse(task.isCancelled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue("stop must run independently of blocked $method", stopped.await(2, TimeUnit.SECONDS))
        assertTrue(task.isCancelled)
        assertEquals(1, stops.get())
        assertNull(field("active").get(SilentPacketRuntime))
    }

    @Test fun startupIsCoveredByTwentyFiveSecondWatchdog() = assertIndependentDeadline("startForNotification")
    @Test fun pendingIntentLaunchIsCoveredBySameWatchdog() = assertIndependentDeadline("launch")

    @Test fun expiredTaskCannotLaunchEvenBeforeMainWatchdogDispatches() {
        val task = begin()
        // 只推进时间，不执行主线程超时回调，验证后台门禁也独立包含截止时间。
        ShadowSystemClock.advanceBy(Duration.ofSeconds(25))
        release.countDown()
        runBlocking { withTimeout(5_000) { task.join() } }
        assertEquals(0, launches.get())
    }

    @Test fun completedTaskCannotBeStoppedAgainByItsOldDeadline() {
        startState = "MAIN_FOCUS_UNKNOWN"
        release.countDown()
        val task = begin()
        runBlocking { withTimeout(5_000) { task.join() } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stops.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(25))
        assertEquals(1, stops.get())
        assertNull(field("job").get(SilentPacketRuntime))
    }
}
