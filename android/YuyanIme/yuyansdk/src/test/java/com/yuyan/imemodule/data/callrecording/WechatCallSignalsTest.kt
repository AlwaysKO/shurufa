package com.yuyan.imemodule.data.callrecording

import org.junit.Assert.*
import org.junit.Test

class WechatCallSignalsTest {
    private val now = 1_000_000L
    private fun notify(
        state: WechatCallSignalState,
        key: String = "call",
        text: String = "语音通话中",
        ongoing: Boolean = true,
        messaging: Boolean = false,
        pkg: String = "com.tencent.mm",
        posted: Long = now,
        observed: Long = now,
    ) = state.notification(pkg, key, ongoing, text, messaging, posted, observed)

    @Test fun ongoingVoiceProducesOnlyGenericSource() {
        assertEquals(WechatCallSignal(true, "wechat_voice"), notify(WechatCallSignalState()))
    }

    @Test fun ongoingVideoIsSupported() {
        assertEquals(WechatCallSignal(true, "wechat_video"), notify(WechatCallSignalState(), text = "视频通话中"))
    }

    @Test fun plainMessagesEvenWithExactCallWordsDoNotStartRecording() {
        assertNull(notify(WechatCallSignalState(), messaging = true))
        assertNull(notify(WechatCallSignalState(), ongoing = false))
    }

    @Test fun OtherAppsCannotTriggerRecording() {
        assertNull(notify(WechatCallSignalState(), pkg = "com.ss.android.ugc.aweme"))
    }

    @Test fun ringingVoiceMessageAndChatBodyAreNotActiveCalls() {
        listOf("邀请你语音通话", "等待对方接受", "[语音]", "我的语音通话中断了", "联系人：语音通话中").forEach {
            assertNull(notify(WechatCallSignalState(), text = it))
        }
    }

    @Test fun staleFutureAndInvalidTimeDoNotStartRecording() {
        listOf(now - 30_001, now + 1, 0L).forEach {
            assertNull(notify(WechatCallSignalState(), posted = it))
        }
    }

    @Test fun emptyKeysAreIgnored() {
        assertNull(notify(WechatCallSignalState(), key = ""))
    }

    @Test fun unrelatedRemovalCannotEndTheCall() {
        val state = WechatCallSignalState()
        notify(state)
        assertNull(state.removed("com.tencent.mm", "ordinary-message", now))
        assertNull(state.removed("another.package", "call", now))
        assertEquals(WechatCallSignal(false, "wechat_voice"), state.removed("com.tencent.mm", "call", now))
    }

    @Test fun oldNotificationRemovalCannotStopReplacementCall() {
        val state = WechatCallSignalState()
        notify(state, key = "old")
        notify(state, key = "new", text = "视频通话中", posted = now + 10, observed = now + 10)
        assertNull(state.removed("com.tencent.mm", "old", now))
        assertEquals(WechatCallSignal(false, "wechat_video"), state.removed("com.tencent.mm", "new", now + 10))
    }

    @Test fun sameKeyOldRemovalCannotStopANewerUpdate() {
        val state = WechatCallSignalState()
        notify(state)
        notify(state, posted = now + 10, observed = now + 10)
        assertNull(state.removed("com.tencent.mm", "call", now))
        assertEquals(WechatCallSignal(false, "wechat_voice"), state.removed("com.tencent.mm", "call", now + 10))
    }

    @Test fun statusUpdateToEndedEndsOnlyItsOwnCall() {
        val state = WechatCallSignalState()
        notify(state)
        assertNull(notify(state, key = "unrelated", text = "通话结束"))
        assertEquals(WechatCallSignal(false, "wechat_voice"), notify(state, text = "通话结束"))
    }

    @Test fun staleUpdateDoesNotReplaceNewerEvidence() {
        val state = WechatCallSignalState()
        notify(state, posted = now + 10, observed = now + 10)
        assertNull(notify(state, text = "通话结束", observed = now + 20))
        assertEquals(WechatCallSignal(false, "wechat_voice"), state.removed("com.tencent.mm", "call", now + 10))
    }

    @Test fun clearDropsEvidenceAndRepeatedClearIsSilent() {
        val state = WechatCallSignalState()
        notify(state)
        assertEquals(WechatCallSignal(false, "wechat_voice"), state.clear())
        assertNull(state.clear())
        assertNull(state.removed("com.tencent.mm", "call", now))
    }
}
