package com.yuyan.imemodule.data.redpacket

import android.app.KeyguardManager
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.yuyan.redpacket.silent.ISilentPacketService
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SilentPacketRuntimeNoticeDiagnosticTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private fun field(name: String) = SilentPacketRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(SilentPacketRuntime, value)
    private fun handler() = field("main").get(SilentPacketRuntime) as Handler
    private fun pending() = field("pendingNoticeDiagnostic").get(SilentPacketRuntime)
    private fun waitValue(check: Any, name: String): Long = check.javaClass.getDeclaredField(name)
        .apply { isAccessible = true }.getLong(check)
    private fun ordinaryNotice(): StatusBarNotification {
        val uid = 10001
        val intent = PendingIntent.getActivity(app, 701, Intent("ordinary-diagnostic"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(intent).setCreatorPackage(PACKET_WECHAT)
        shadowOf(intent).setCreatorUid(uid)
        val notification = Notification.Builder(app, "diagnostic").setContentTitle("我自己的")
            .setContentText("成员: 普通消息").setContentIntent(intent).build()
        notification.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true)
        return StatusBarNotification(PACKET_WECHAT, PACKET_WECHAT, 701, null, uid, 0, 0,
            notification, UserHandle.getUserHandleForUid(uid), System.currentTimeMillis())
    }
    private var calls = 0
    @Before fun prepare() {
        handler().removeCallbacksAndMessages(null)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        set("context", null); set("job", null); set("active", null)
        set("stopsInFlight", 0); set("pendingDiagnostic", null)
        set("pendingNoticeDiagnostic", null); set("foregroundVerified", true)
        @Suppress("UNCHECKED_CAST")
        (field("queued").get(SilentPacketRuntime) as ArrayDeque<PacketRequest>).clear()
        PacketServiceState.accessibilityConnected = true
        SilentPacketSettings.setGroups(app, "我自己的")
        set("service", Proxy.newProxyInstance(ISilentPacketService::class.java.classLoader,
            arrayOf(ISilentPacketService::class.java)) { _, method, _ ->
            if (method.name in setOf("start", "launch", "tap", "back")) calls++
            when(method.name) { "status" -> "{\"displayId\":-1}"; "start" -> "{\"state\":\"MAIN_FOCUS_UNKNOWN\"}"; else -> null }
        } as ISilentPacketService)
    }
    @After fun cleanup() {
        handler().removeCallbacksAndMessages(null)
        set("pendingNoticeDiagnostic", null); set("pendingDiagnostic", null)
        set("service", null); set("context", null)
        set("foregroundVerified", false)
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        PacketServiceState.accessibilityConnected = false
    }
    @Test fun stopBeforePostedDiagnosticStartCannotResurrectIt() {
        SilentPacketRuntime.diagnose(app, 0)
        SilentPacketRuntime.stop("用户停止")
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(field("pendingDiagnostic").get(SilentPacketRuntime))
        assertEquals(0, calls)
    }
    @Test fun staleHandoffCannotScheduleAfterGenerationChanged() {
        val generation = field("generation").get(SilentPacketRuntime) as java.util.concurrent.atomic.AtomicLong
        val expected = generation.get()
        generation.incrementAndGet()
        val schedule = SilentPacketRuntime::class.java.declaredMethods.single { it.name == "scheduleDiagnosis" }
        schedule.isAccessible = true
        schedule.invoke(SilentPacketRuntime, app, 0, null, expected, null, Long.MAX_VALUE)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(field("pendingDiagnostic").get(SilentPacketRuntime))
        assertEquals(0, calls)
    }
    @Test fun expiredNotificationCannotBeLaunchedAfterSecondaryStarts() {
        shadowOf(app.getSystemService(android.os.PowerManager::class.java)).setIsInteractive(true)
        shadowOf(app.getSystemService(android.app.KeyguardManager::class.java)).setIsDeviceLocked(false)
        val cleaned = java.util.concurrent.CountDownLatch(1)
        val launches = java.util.concurrent.atomic.AtomicInteger()
        set("service", Proxy.newProxyInstance(ISilentPacketService::class.java.classLoader,
            arrayOf(ISilentPacketService::class.java)) { _, method, _ ->
            when (method.name) {
                "start" -> "{\"state\":\"RUNNING\",\"displayId\":5}"
                "launch" -> { launches.incrementAndGet(); false }
                "stop" -> { cleaned.countDown(); null }
                "status" -> "{\"displayId\":-1}"
                else -> null
            }
        } as ISilentPacketService)
        val intent = android.app.PendingIntent.getActivity(app, 90, android.content.Intent("read-only"),
            android.app.PendingIntent.FLAG_IMMUTABLE)
        val expected = (field("generation").get(SilentPacketRuntime) as java.util.concurrent.atomic.AtomicLong).get()
        val start = SilentPacketRuntime::class.java.declaredMethods.single { it.name == "startDiagnosis" }
        start.isAccessible = true
        start.invoke(SilentPacketRuntime, app, 0, expected, intent, System.currentTimeMillis() - 16_000, Long.MAX_VALUE)
        assertTrue(cleaned.await(3, java.util.concurrent.TimeUnit.SECONDS))
        assertEquals(0, launches.get())
    }
    @Test fun armingWaitsWithoutCreatingDisplayOrStartingTask() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(pending()); assertNull(field("job").get(SilentPacketRuntime)); assertEquals(0, calls)
    }
    @Test fun armingExpiresOnceWithoutPollingOrStartingAnything() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(59))
        assertNotNull(pending())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(pending()); assertEquals(0, calls)
        assertTrue(SilentPacketRuntime.status().contains("未收到符合范围的普通群通知"))
    }
    @Test fun explicitStopClearsWaitAndOldTimerCannotOverwriteStopReason() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(pending())
        SilentPacketRuntime.stop("用户停止")
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertNull(pending()); assertTrue(SilentPacketRuntime.status().startsWith("用户停止")); assertEquals(0, calls)
    }
    @Test fun unknownForegroundPausesSameWaitWithoutCreatingResourcesOrExtendingDeadline() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle()
        val original = pending()!!
        val armedAt = waitValue(original, "armedAt")
        val expiresAt = waitValue(original, "expiresAt")
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20))
        assertSame(original, pending())
        SilentPacketRuntime.notification(app, ordinaryNotice())
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(original, pending())
        assertNull(field("pendingDiagnostic").get(SilentPacketRuntime))
        assertNull(field("job").get(SilentPacketRuntime))
        assertEquals(0, calls)
        SilentPacketRuntime.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(39))
        assertSame(original, pending())
        assertEquals(armedAt, waitValue(pending()!!, "armedAt"))
        assertEquals(expiresAt, waitValue(pending()!!, "expiresAt"))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertNull(pending()); assertEquals(0, calls)
    }
    @Test fun freshNoticeAfterForegroundRecoveryCanScheduleReadOnlyDiagnostic() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle()
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idle()
        SilentPacketRuntime.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idle()
        SilentPacketRuntime.notification(app, ordinaryNotice())
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(pending())
        assertNotNull(field("pendingDiagnostic").get(SilentPacketRuntime))
        assertEquals(0, calls)
    }
    @Test fun explicitStopWhileForegroundUnknownCannotBeResurrectedByRecovery() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle()
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idle()
        SilentPacketRuntime.stop("用户停止")
        // stop与恢复回调可连续到达，不能等主线程先完成清理才保证不复活。
        SilentPacketRuntime.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(pending())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertNull(pending()); assertEquals(0, calls)
        assertTrue(SilentPacketRuntime.status().startsWith("用户停止"))
    }
    @Test fun independentLifecycleCancellationWhileAlreadyUnknownCannotRecover() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle()
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertNotNull(pending())
        SilentPacketRuntime.cancelNoticeDiagnosticForLifecycle()
        // 模拟无障碍/屏幕生命周期并不再次发布去重后的false，而直接恢复true。
        SilentPacketRuntime.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(pending())
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertNull(pending()); assertEquals(0, calls)
    }
    @Test fun lockedScreenCancelsWaitAndUnlockDoesNotRestoreIt() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle(); assertNotNull(pending())
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(true)
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idle(); assertNull(pending())
        shadowOf(app.getSystemService(KeyguardManager::class.java)).setIsDeviceLocked(false)
        SilentPacketRuntime.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertNull(pending()); assertEquals(0, calls)
    }
    @Test fun screenOffCancelsWaitAndScreenOnDoesNotRestoreIt() {
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle(); assertNotNull(pending())
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(false)
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idle(); assertNull(pending())
        shadowOf(app.getSystemService(PowerManager::class.java)).setIsInteractive(true)
        SilentPacketRuntime.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(61))
        assertNull(pending()); assertEquals(0, calls)
    }
    @Test fun missingScopeOrConnectionCannotArmNotificationInspection() {
        SilentPacketSettings.setGroups(app, "")
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle(); assertNull(pending())
        SilentPacketSettings.setGroups(app, "我自己的"); set("service", null)
        SilentPacketRuntime.diagnoseNextNotice(app, 0)
        shadowOf(Looper.getMainLooper()).idle(); assertNull(pending()); assertEquals(0, calls)
    }
}
