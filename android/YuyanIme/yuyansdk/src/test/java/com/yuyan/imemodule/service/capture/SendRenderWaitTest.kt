package com.yuyan.imemodule.service.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendRenderWaitTest {
    @Test fun inFlightContentDoesNotRevokeFrameButFailedRequestRechecksIt() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        assertFalse(wait.beginFrameRequest())
        now = 350
        assertTrue(wait.beginFrameRequest())
        now = 370
        wait.changed()
        assertTrue("实际请求后的泛化content不撤销原帧", wait.isSettled())
        assertFalse("同一在途请求不能重复开始", wait.beginFrameRequest())
        wait.frameRequestFailed()
        assertFalse("失败后不能沿用旧许可", wait.isSettled())
        now = 460
        assertTrue(wait.beginFrameRequest())
    }

    @Test fun contentBeforePhysicalRequestStillRequiresRenderSettling() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        now = 350
        assertTrue(wait.isSettled())
        now = 360
        wait.changed()
        assertFalse(wait.beginFrameRequest())
    }

    @Test fun inFlightPositionChangeCannotBeRevivedByReturningToSameTail() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        now = 250
        wait.scrolled("7:ListView", 12, 18, 19)
        now = 320
        wait.scrolled("7:ListView", 12, 18, 19)
        assertTrue(wait.beginFrameRequest())
        now = 350
        wait.changed()
        wait.scrolled("7:ListView", 12, 18, 19)
        assertTrue(wait.isSettled())
        wait.scrolled("7:ListView", 10, 16, 19)
        assertFalse(wait.isSettled())
        now = 450
        wait.scrolled("7:ListView", 12, 18, 19)
        assertFalse(wait.isSettled())
    }

    @Test fun inFlightUnknownPositionOrDifferentListInvalidatesFrame() {
        for (source in listOf("", "7:RecyclerView")) {
            var now = 0L
            val wait = SendRenderWait("com.tencent.mm", 1) { now }
            now = 250
            wait.scrolled("7:ListView", 12, 18, 19)
            now = 320
            wait.scrolled("7:ListView", 12, 18, 19)
            assertTrue(wait.beginFrameRequest())
            wait.scrolled(source, 12, 18, 19)
            assertFalse(wait.isSettled())
        }
    }

    @Test fun editorClearDoesNotImmediatelyLockOldFrame() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        now = 80
        wait.changed()
        assertEquals(220L, wait.remainingMillis())
        now = 270
        wait.changed()
        assertEquals(90L, wait.remainingMillis())
        now = 360
        assertEquals(0L, wait.remainingMillis())
        assertTrue(wait.isSettled())
    }
    @Test fun repeatedAnimationCannotPostponeBeyondBoundedSendWindow() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        now = 480
        wait.changed()
        assertEquals(20L, wait.remainingMillis())
        now = 500
        wait.changed()
        assertEquals(0L, wait.remainingMillis())
        assertFalse("到期不代表持续滚动已经停止", wait.isSettled())
        now = 800
        assertFalse("越过渲染截止的变化不能在等待手指释放后重新放行", wait.isSettled())
    }
    @Test fun repeatedStableTailPositionCanReleaseFrameWithoutAnotherQuietPeriod() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        now = 255
        wait.scrolled("7:ListView", 14, 18, 19)
        assertFalse(wait.isSettled())
        now = 365
        wait.changed()
        assertFalse(wait.isSettled())
        wait.scrolled("7:ListView", 14, 18, 19)
        assertTrue("同一发送的末尾布局已经两次一致，不再等静默期", wait.isSettled())
        assertEquals(0L, wait.remainingMillis())
        now = 390
        wait.scrolled("7:ListView", 12, 16, 19)
        assertFalse("向历史滚动必须使提前许可失效", wait.isSettled())
    }

    @Test fun invalidOrDifferentListPositionCannotConfirmStableTail() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        now = 255
        wait.scrolled("7:ListView", 14, 18, 19)
        now = 365
        wait.scrolled("7:RecyclerView", 14, 18, 19)
        assertFalse(wait.isSettled())
        now = 400
        wait.scrolled("7:RecyclerView", -1, -1, -1)
        assertFalse(wait.isSettled())
    }

    @Test fun missingContentEventsStillGetsOneBoundedAttempt() {
        var now = 0L
        val wait = SendRenderWait("com.ss.android.ugc.aweme", 2) { now }
        assertEquals(350L, wait.remainingMillis())
        now = 350
        assertEquals(0L, wait.remainingMillis())
    }

    @Test fun interruptedTailCannotPairWithAnOlderObservation() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        now = 255
        wait.scrolled("7:ListView", 14, 18, 19)
        now = 290
        wait.scrolled("7:ListView", 12, 16, 19)
        now = 365
        wait.scrolled("7:ListView", 14, 18, 19)
        assertFalse(wait.isSettled())
        now = 380
        wait.changed()
        assertFalse("内容事件立即撤销已有许可", wait.isSettled())
        now = 430
        wait.scrolled("7:ListView", 14, 18, 19)
        assertTrue("新滚动事件可重新核对此前位置，不沿用旧许可", wait.isSettled())
    }

    @Test fun stableTailCannotBypassMinimumRenderTimeOrReviveExpiredAttempt() {
        var now = 0L
        val wait = SendRenderWait("com.tencent.mm", 1) { now }
        now = 180
        wait.scrolled("7:ListView", 14, 18, 19)
        now = 250
        wait.scrolled("7:ListView", 14, 18, 19)
        assertFalse(wait.isSettled())
        now = 290
        wait.scrolled("7:ListView", 14, 18, 19)
        now = 300
        assertFalse("过于密集的事件不能确认稳定", wait.isSettled())
        now = 510
        wait.scrolled("7:ListView", 14, 18, 19)
        assertFalse(wait.isSettled())
    }
}
