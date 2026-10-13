package com.yuyan.imemodule.data.redpacket

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest
import com.yuyan.imemodule.data.collect.resetImageInputForTest
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
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SilentPacketRuntimeConcurrentInteractionTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val starts = AtomicInteger()
    private val started = CountDownLatch(1)
    private var startedUser = -1
    private var previousInstalledListeners = false
    private fun field(name: String) = SilentPacketRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(SilentPacketRuntime, value)
    private fun optionalSet(name: String, value: Any?) {
        SilentPacketRuntime::class.java.declaredFields.firstOrNull { it.name == name }
            ?.apply { isAccessible = true }?.set(SilentPacketRuntime, value)
    }
    private fun handler() = field("main").get(SilentPacketRuntime) as Handler
    private fun generation() = (field("generation").get(SilentPacketRuntime) as AtomicLong).get()
    @Suppress("UNCHECKED_CAST")
    private fun queue() = field("queued").get(SilentPacketRuntime) as ArrayDeque<PacketRequest>

    private fun resetRuntime() {
        handler().removeCallbacksAndMessages(null)
        set("context", null)
        set("service", null)
        set("binding", false)
        set("job", null)
        set("active", null)
        set("activeCardCommitted", false)
        set("pendingDiagnostic", null)
        set("stopsInFlight", 0)
        set("retryScheduled", false)
        optionalSet("pausedForUserAction", false)
        optionalSet("foregroundVerified", true)
        queue().clear()
        for (name in listOf("startGate", "userActionGate")) {
            SilentPacketRuntime::class.java.declaredFields.firstOrNull { it.name == name }
                ?.apply { isAccessible = true }?.get(SilentPacketRuntime)?.let { gate ->
                    gate.javaClass.getDeclaredField("deferredAt").apply { isAccessible = true }.set(gate, null)
                }
        }
        val recent = field("recent").get(SilentPacketRuntime)
        @Suppress("UNCHECKED_CAST")
        (recent.javaClass.getDeclaredField("seen").apply { isAccessible = true }.get(recent) as MutableSet<String>).clear()
    }

    @Before fun prepare() {
        resetRuntime()
        previousInstalledListeners = field("installedListeners").get(SilentPacketRuntime) as Boolean
        set("installedListeners", true)
        resetImageInputForTest()
        resetGameWorkRuntimeForTest()
        setVisibleGameProtection(false)
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
        app.getSharedPreferences("silent_packet_receipts", Context.MODE_PRIVATE).edit().clear().commit()
        SilentPacketSettings.setMode(app, "AUTO")
        SilentPacketSettings.setAutoUser(app, true)
        SilentPacketSettings.setAutoGroups(app, true)
        PacketServiceState.accessibilityConnected = true
        PacketServiceState.notificationConnected = true
    }

    @After fun cleanUp() {
        val task = field("job").get(SilentPacketRuntime) as Job?
        task?.cancel()
        runBlocking { withTimeout(5_000) { task?.join() } }
        resetRuntime()
        optionalSet("foregroundVerified", false)
        set("installedListeners", previousInstalledListeners)
        resetImageInputForTest()
        resetGameWorkRuntimeForTest()
        setVisibleGameProtection(false)
        PacketServiceState.accessibilityConnected = false
        PacketServiceState.notificationConnected = false
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit()
    }

    private fun setVisibleGameProtection(protected: Boolean) {
        GroupRedPacketAssistant::class.java.getDeclaredField("gameProtected").apply { isAccessible = true }
            .setBoolean(GroupRedPacketAssistant, protected)
        GroupRedPacketAssistant::class.java.getDeclaredField("gameResumeAt").apply { isAccessible = true }
            .setLong(GroupRedPacketAssistant, 0L)
    }

    private fun request(id: Char = 'a', user: Int = 128) = PacketRequest(
        PacketCandidate(id.toString().repeat(64), "测试群", true),
        PendingIntent.getActivity(app, id.code, Intent("concurrent-interaction-test"), PendingIntent.FLAG_IMMUTABLE),
        System.currentTimeMillis() - 1_000, targetUserId = user)

    private fun event(type: Int) {
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(type)
        try { SilentPacketRuntime.event(event) }
        finally { @Suppress("DEPRECATION") event.recycle() }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun prepareRealDrain() {
        val fake = Proxy.newProxyInstance(ISilentPacketService::class.java.classLoader,
            arrayOf(ISilentPacketService::class.java)) { _, method, args ->
            when (method.name) {
                "startForNotification" -> {
                    startedUser = args!![0] as Int
                    starts.incrementAndGet()
                    started.countDown()
                    "{\"state\":\"MAIN_FOCUS_UNKNOWN\"}"
                }
                else -> null
            }
        } as ISilentPacketService
        set("context", app)
        set("service", fake)
    }

    private fun drain() = SilentPacketRuntime::class.java.getDeclaredMethod("drain")
        .apply { isAccessible = true }.invoke(SilentPacketRuntime)

    private fun assertStartsWithoutWaitingForMainScreen() {
        queue().add(request())
        drain()
        assertNotNull("main-screen work must not delay task creation", field("job").get(SilentPacketRuntime))
        assertTrue("real drain must reach the isolated backend", started.await(2, TimeUnit.SECONDS))
        assertEquals(1, starts.get())
        assertEquals(128, startedUser)
    }

    @Test fun fourMainScreenActionsDoNotCancelChangeGenerationOrReorderWork() {
        for (type in listOf(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START, AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_SCROLLED, AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)) {
            resetRuntime()
            val original = request()
            val waiting = request('b', 0)
            val task = Job()
            set("job", task)
            set("active", original)
            queue().add(waiting)
            val before = generation()
            event(type)
            assertTrue(task.isActive)
            assertEquals(before, generation())
            assertEquals(original, field("active").get(SilentPacketRuntime))
            assertEquals(listOf(waiting), queue().toList())
            task.cancel()
        }
    }

    @Test fun continuousMainScreenActionsDoNotDelayRealDrain() {
        prepareRealDrain()
        repeat(10) {
            event(AccessibilityEvent.TYPE_VIEW_SCROLLED)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        }
        event(AccessibilityEvent.TYPE_VIEW_CLICKED)
        assertStartsWithoutWaitingForMainScreen()
    }

    @Test fun heldMainKeyboardTouchDoesNotBlockIsolatedTask() {
        prepareRealDrain()
        ImageUploadRuntime.noteTouch(0, this)
        assertFalse(ImageUploadRuntime.isInputIdle())
        assertStartsWithoutWaitingForMainScreen()
    }

    @Test fun mainKeyboardTypingDoesNotBlockIsolatedTask() {
        prepareRealDrain()
        ImageUploadRuntime.noteKeyActivity()
        assertFalse(ImageUploadRuntime.isInputIdle())
        assertStartsWithoutWaitingForMainScreen()
    }

    @Test fun verifiedGameCanRunSilentlyWhileGlobalBackgroundCollectionRemainsPaused() {
        prepareRealDrain()
        GameWorkRuntime.setGaming(true)
        setVisibleGameProtection(true)
        optionalSet("foregroundVerified", true)
        assertFalse(GameWorkRuntime.isBackgroundAllowed())
        assertFalse(GroupRedPacketAssistant.isInteractionAllowed())
        assertStartsWithoutWaitingForMainScreen()
    }

    @Test fun unverifiedForegroundCannotStartEvenWhenMainInputAndGlobalWorkAreIdle() {
        prepareRealDrain()
        optionalSet("foregroundVerified", false)
        assertTrue(ImageUploadRuntime.isInputIdle())
        assertTrue(GameWorkRuntime.isBackgroundAllowed())
        queue().add(request())
        drain()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1))
        assertEquals(0, starts.get())
        assertNull(field("job").get(SilentPacketRuntime))
        assertEquals(1, queue().size)
    }

    @Test fun explicitStopCannotBeUndoneByFurtherMainScreenActions() {
        val task = Job()
        set("job", task)
        set("active", request())
        queue().add(request('b'))
        SilentPacketRuntime.stop("用户停止")
        shadowOf(Looper.getMainLooper()).idle()
        event(AccessibilityEvent.TYPE_VIEW_SCROLLED)
        event(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4))
        assertTrue(task.isCancelled)
        assertTrue(queue().isEmpty())
        assertNull(field("active").get(SilentPacketRuntime))
    }

    @Test fun notificationDuringMainScreenInteractionKeepsNormalLifetimeAndSourceUser() {
        event(AccessibilityEvent.TYPE_VIEW_CLICKED)
        val uid = 128 * 100_000 + 10001
        val postedAt = System.currentTimeMillis() - 1_000
        val intent = PendingIntent.getActivity(app, 123, Intent("concurrent-notification-test"), PendingIntent.FLAG_IMMUTABLE)
        shadowOf(intent).setCreatorPackage(PACKET_WECHAT)
        shadowOf(intent).setCreatorUid(uid)
        val notification = Notification.Builder(app, "test")
            .setContentTitle("测试群").setContentText("成员: [微信红包]恭喜发财")
            .setContentIntent(intent).build()
        notification.extras.putBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, true)
        SilentPacketRuntime.notification(app, StatusBarNotification(PACKET_WECHAT, PACKET_WECHAT, 123, null,
            uid, 0, 0, notification, UserHandle.getUserHandleForUid(uid), postedAt))
        shadowOf(Looper.getMainLooper()).idle()
        val saved = queue().single()
        assertEquals(postedAt, saved.postedAt)
        assertEquals(128, saved.targetUserId)
        assertFalse(saved.deferredByGame)
        assertFalse(saved.isValidAt(postedAt + 15_001))
    }
}
