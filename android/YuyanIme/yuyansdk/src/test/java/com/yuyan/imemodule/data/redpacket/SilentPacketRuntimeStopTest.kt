package com.yuyan.imemodule.data.redpacket

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
@LooperMode(LooperMode.Mode.PAUSED)
class SilentPacketRuntimeStopTest {
    private fun field(name: String) = SilentPacketRuntime::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun set(name: String, value: Any?) = field(name).set(SilentPacketRuntime, value)
    @Suppress("UNCHECKED_CAST")
    private fun queue() = field("queued").get(SilentPacketRuntime) as ArrayDeque<PacketRequest>

    @Before fun reset() {
        (field("main").get(SilentPacketRuntime) as Handler).removeCallbacksAndMessages(null)
        (field("job").get(SilentPacketRuntime) as Job?)?.cancel()
        queue().clear()
        set("context", null)
        set("service", null)
        set("job", null)
        set("active", null)
        set("activeCardCommitted", false)
        set("retryScheduled", false)
        set("stopsInFlight", 0)
    }

    @After fun cleanUp() = reset()

    private fun begin(committed: Boolean = false): PacketRequest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val request = PacketRequest(PacketCandidate("a".repeat(64), "测试群", true),
            PendingIntent.getActivity(context, 0, Intent("runtime-stop-test"), PendingIntent.FLAG_IMMUTABLE),
            System.currentTimeMillis())
        set("active", request)
        set("activeCardCommitted", committed)
        // 保留 active，模拟后台任务已取消、完成回调尚未在主线程清理的窗口。
        set("job", Job())
        return request
    }

    @Test fun explicitStopThenProtectionCannotRestoreCancelledRequest() {
        begin()
        SilentPacketRuntime.stop("用户停止")
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idle()
        SilentPacketRuntime.protectForeground(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(queue().isEmpty())
        assertNull(field("active").get(SilentPacketRuntime))
    }

    @Test fun protectionAlonePreservesUncommittedRequestWithOriginalTimestamp() {
        val request = begin()
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, queue().size)
        assertEquals(request.candidate.id, queue().first().candidate.id)
        assertEquals(request.postedAt, queue().first().postedAt)
        assertTrue(queue().first().deferredByGame)
    }

    @Test fun protectionNeverRestoresCommittedCard() {
        begin(committed = true)
        SilentPacketRuntime.protectForeground(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(queue().isEmpty())
    }
}
