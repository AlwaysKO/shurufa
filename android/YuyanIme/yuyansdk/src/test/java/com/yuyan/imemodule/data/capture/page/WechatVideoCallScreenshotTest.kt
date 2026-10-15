package com.yuyan.imemodule.data.capture.page

import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test

class WechatVideoCallScreenshotTest {
    private val bounds = IntRect(0, 0, 1000, 2000)
    private fun label(text: String, x: Int, y: Int) = PageLabel(text, IntRect(x, y, x + 250, y + 60))
    private fun callLabels() = listOf(label("00:12", 400, 300), label("切换摄像头", 50, 1400),
        label("切到语音通话", 650, 1400), label("挂断", 400, 1700))
    @Test fun connectedWechatVideoCallNeedsCallControlsAndConnectedEvidence() {
        val decision = PageCapturePolicy.classify("com.tencent.mm", bounds, callLabels())
        assertEquals(PageKind.VIDEO_CALL, decision.kind)
        for (missing in listOf("00:12", "挂断", "切换摄像头"))
            assertNull(missing, PageCapturePolicy.classify("com.tencent.mm", bounds, callLabels().filterNot { it.text == missing }).kind)
        assertNull(PageCapturePolicy.classify("com.ss.android.ugc.aweme", bounds, callLabels()).kind)
        assertNull(PageCapturePolicy.classify("com.tencent.mm", bounds, callLabels()+label("等待对方接受邀请", 200, 600)).kind)
        assertNull(PageCapturePolicy.classify("com.tencent.mm", bounds, callLabels()+label("请输入支付密码", 200, 700)).kind)
        assertNull(PageCapturePolicy.classify("com.tencent.mm", bounds, callLabels(), secureWindow = true).kind)
        assertEquals(PageKind.CHAT, PageCapturePolicy.classify("com.tencent.mm", bounds, callLabels(), chatVerified = true).kind)
        assertFalse(acceptOrdinaryPageKind(PageKind.VIDEO_CALL))
        assertTrue(reservePriorityPageKinds(decision, { error("call must not use payment budget") }, { error("call must not use feed budget") }).isEmpty())
    }
    @Test fun callScreenshotWaitsFiveSecondsAndTakesOnlyOnce() {
        val schedule = WechatVideoCallScreenshotSchedule()
        val ticket = schedule.observe("com.tencent.mm", 7, 1000)!!
        assertFalse(schedule.take(ticket, 5999))
        assertTrue(schedule.take(ticket, 6000))
        assertFalse(schedule.take(ticket, 6001))
        schedule.saved(ticket)
        schedule.invalidate()
        assertNull(schedule.observe("com.tencent.mm", 7, 10000))
        schedule.leave()
        assertNotNull(schedule.observe("com.tencent.mm", 7, 11000))
    }
    @Test fun navigationExitLockOrCallEndCancelOldFiveSecondCandidate() {
        for (leave in listOf(false, true)) {
            val schedule = WechatVideoCallScreenshotSchedule()
            val old = schedule.observe("com.tencent.mm", 7, 1000)!!
            if (leave) schedule.leave() else schedule.invalidate()
            assertFalse(schedule.take(old, 6000))
            assertFalse(schedule.isCurrent(old))
            val next = schedule.observe("com.tencent.mm", 8, 2000)!!
            assertFalse(schedule.take(next, 6999))
            assertTrue(schedule.take(next, 7000))
        }
    }
    @Test fun unsupportedPackageAndReversedClockCannotProduceCallShot() {
        val schedule = WechatVideoCallScreenshotSchedule()
        assertNull(schedule.observe("com.ss.android.ugc.aweme", 7, 1000))
        assertNull(schedule.observe("com.tencent.mm", -1, 1000))
        assertNull(schedule.observe("com.tencent.mm", 7, -1))
        val ticket = schedule.observe("com.tencent.mm", 7, 1000)!!
        assertFalse(schedule.take(ticket, 999))
    }

    @Test fun connectedSignalRechecksSameWindowOnceAndEndDoesNotBlockNextVisualCall() {
        val schedule = WechatVideoCallScreenshotSchedule()
        assertFalse(schedule.onCallSignal(false, "wechat_video"))
        assertTrue(schedule.onCallSignal(true, "wechat_video"))
        val first = schedule.observe("com.tencent.mm", 7, 1000)!!
        assertFalse(schedule.onCallSignal(true, "wechat_video"))
        assertTrue(schedule.isCurrent(first))
        assertFalse(schedule.onCallSignal(false, "wechat_video"))
        assertFalse(schedule.take(first, 6000))
        // 下一次通话即使未收到通知，也可凭新的完整画面独立确认。
        val next = schedule.observe("com.tencent.mm", 7, 7000)!!
        assertTrue(schedule.take(next, 12000))
        assertFalse(schedule.onCallSignal(true, "wechat_voice"))
        assertFalse(schedule.isCurrent(next))
        assertTrue(schedule.onCallSignal(true, "wechat_video"))
    }
}
