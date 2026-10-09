package com.yuyan.imemodule.data.redpacket

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
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
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SilentPacketRuntimeDiagnosticQuietTest {
    private val starts = AtomicInteger()
    private val stops = AtomicInteger()
    private val startEntered = CountDownLatch(1)
    private val stopEntered = CountDownLatch(1)
    private val releaseStart = CountDownLatch(1)
    private fun field(name: String) = SilentPacketRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(SilentPacketRuntime, value)
    private fun handler() = field("main").get(SilentPacketRuntime) as Handler
    private fun inputStops(): Long = (field("diagnostics").get(SilentPacketRuntime) as SilentPacketDiagnostics)
        .snapshot().counters.getValue(SilentPacketDiagnosticEvent.INPUT_STOPPED)

    private fun resetPendingDiagnostic() {
        SilentPacketRuntime::class.java.declaredFields.firstOrNull { it.name == "pendingDiagnostic" }
            ?.apply { isAccessible = true }?.set(SilentPacketRuntime, null)
        SilentPacketRuntime::class.java.declaredFields.firstOrNull { it.name == "startGate" }
            ?.apply { isAccessible = true }?.get(SilentPacketRuntime)?.let { gate ->
                gate.javaClass.getDeclaredField("deferredAt").apply { isAccessible = true }.set(gate, null)
            }
    }

    @Before fun prepare() {
        handler().removeCallbacksAndMessages(null)
        resetPendingDiagnostic()
        set("context", null)
        set("job", null)
        set("active", null)
        set("stopsInFlight", 0)
        set("retryScheduled", false)
        @Suppress("UNCHECKED_CAST")
        (field("queued").get(SilentPacketRuntime) as ArrayDeque<PacketRequest>).clear()
        PacketServiceState.accessibilityConnected = true
        val fake = Proxy.newProxyInstance(ISilentPacketService::class.java.classLoader,
            arrayOf(ISilentPacketService::class.java)) { _, method, _ ->
            when (method.name) {
                "start" -> {
                    starts.incrementAndGet()
                    startEntered.countDown()
                    // 旧实现若已经启动，阻止它抢先完成并隐藏迟到点击竞态。
                    releaseStart.await(10, TimeUnit.SECONDS)
                    "{\"state\":\"MAIN_FOCUS_UNKNOWN\"}"
                }
                "status" -> "{\"displayId\":-1}"
                "users" -> "{\"users\":[]}"
                "stop" -> { stops.incrementAndGet(); stopEntered.countDown(); null }
                "tap", "back", "launch" -> false
                else -> null
            }
        } as ISilentPacketService
        set("service", fake)
    }

    @After fun cleanUp() {
        releaseStart.countDown()
        val job = field("job").get(SilentPacketRuntime) as Job?
        job?.cancel()
        runBlocking { withTimeout(5_000) { job?.join() } }
        handler().removeCallbacksAndMessages(null)
        resetPendingDiagnostic()
        set("job", null)
        set("service", null)
        PacketServiceState.accessibilityConnected = false
    }

    @Test fun lateDiagnosticButtonClickArrivesBeforeAnyTaskIsCreated() {
        val before = inputStops()
        SilentPacketRuntime.diagnose(ApplicationProvider.getApplicationContext<Context>(), 0)
        handler().post {
            @Suppress("DEPRECATION")
            val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_CLICKED)
            try { SilentPacketRuntime.event(event) }
            finally { @Suppress("DEPRECATION") event.recycle() }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(before, inputStops())
        assertNull(field("job").get(SilentPacketRuntime))
        assertEquals(0, starts.get())
    }

    @Test fun explicitStopDuringQuietWindowPreventsDelayedDiagnosticStart() {
        SilentPacketRuntime.diagnose(ApplicationProvider.getApplicationContext<Context>(), 0)
        shadowOf(Looper.getMainLooper()).idle()
        // 等待期不应提前构造后台任务。
        assertNull(field("job").get(SilentPacketRuntime))
        SilentPacketRuntime.stop("用户停止")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertNull(field("job").get(SilentPacketRuntime))
        assertEquals(0, starts.get())
    }

    @Test fun diagnosticRequestsStopAtSeventeenSecondsEvenWhileTaskCannotCooperate() {
        SilentPacketRuntime.diagnose(ApplicationProvider.getApplicationContext<Context>(), 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue("diagnostic must enter fake service", startEntered.await(2, TimeUnit.SECONDS))
        val task = field("job").get(SilentPacketRuntime) as Job
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(16_999))
        assertEquals(0, stops.get())
        assertFalse(task.isCancelled)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue("stop must not wait for the blocked diagnostic", stopEntered.await(2, TimeUnit.SECONDS))
        assertTrue(task.isCancelled)
        assertEquals(1, stops.get())
    }

    @Test fun completedDiagnosticDoesNotStopAgainAtItsOldDeadline() {
        releaseStart.countDown()
        SilentPacketRuntime.diagnose(ApplicationProvider.getApplicationContext<Context>(), 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(600))
        assertTrue(startEntered.await(2, TimeUnit.SECONDS))
        val task = field("job").get(SilentPacketRuntime) as Job
        runBlocking { withTimeout(5_000) { task.join() } }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, stops.get())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(17))
        assertEquals(1, stops.get())
        assertNull(field("job").get(SilentPacketRuntime))
    }
}
