package com.yuyan.imemodule.data.capture.media

import org.junit.Assert.*
import org.junit.Test

class ConversationTitleStabilizerSnapshotTest {
    private val a = "a".repeat(64)
    private val b = "b".repeat(64)

    @Test fun samePageAcceptedFrameKeepsTruncatedSourceForNextLiveFrame() {
        val original = WechatTitleStabilizer()
        val snapshot = original.fork()
        val accepted = observeSnapshotTitle(original, snapshot, snapshot.version(), { true }, "很长的群名…", a, 1_000)
        val next = original.observe("很长的群名…", a, 1_400)
        assertEquals(accepted.externalKey, next.externalKey)
    }

    @Test fun samePageAcceptedFrameSuppliesExactlyOneLiveConfirmationVote() {
        val original = WechatTitleStabilizer()
        val snapshot = original.fork()
        val accepted = observeSnapshotTitle(original, snapshot, snapshot.version(), { true }, "甲会话", a, 1_000)
        assertEquals("pending", accepted.status)
        assertEquals("confirmed", original.observe("甲会话", a, 1_400).status)
    }

    @Test fun changedScopeUsesFrozenSourceWithoutChangingCurrentPage() {
        val original = WechatTitleStabilizer()
        val first = original.observe("很长的群名…", a, 1_000)
        val snapshot = original.fork()
        original.reset()
        val next = original.observe("另一群…", b, 1_100)
        val old = observeSnapshotTitle(original, snapshot, snapshot.version(), { false }, "很长的群名…", a, 1_400)
        assertEquals(first.externalKey, old.externalKey)
        assertEquals(next.externalKey, original.observe("另一群…", b, 1_500).externalKey)
    }

    @Test fun navigationDuringScopeCheckFallsBackToFrozenSource() {
        val original = WechatTitleStabilizer()
        val first = original.observe("很长的群名…", a, 1_000)
        val snapshot = original.fork()
        val old = observeSnapshotTitle(original, snapshot, snapshot.version(), { original.reset(); true }, "很长的群名…", a, 1_400)
        assertEquals(first.externalKey, old.externalKey)
        assertNotEquals(first.externalKey, original.observe("很长的群名…", a, 1_500).externalKey)
    }

    @Test fun originalNavigationDoesNotResetAcceptedTruncatedTitleSnapshot() {
        val original = WechatTitleStabilizer()
        original.reset()
        val accepted = original.observe("很长的群名…", a, 1_000)
        val snapshot = original.fork()
        val acceptedVersion = snapshot.version()
        original.reset()
        val newPage = original.observe("很长的群名…", a, 1_100)
        val oldFrame = snapshot.observe("很长的群名…", a, 1_200, acceptedVersion)
        assertEquals("truncated", oldFrame.status)
        assertEquals(accepted.externalKey, oldFrame.externalKey)
        assertNotEquals(newPage.externalKey, oldFrame.externalKey)
        assertEquals(acceptedVersion, snapshot.version())
    }

    @Test fun snapshotVotesDoNotConfirmOriginalMutableState() {
        val original = WechatTitleStabilizer()
        original.observe("甲会话", a, 1_000)
        val snapshot = original.fork()
        assertEquals("confirmed", snapshot.observe("甲会话", a, 1_400).status)
        assertEquals("pending", original.observe("对方正在输入…", b, 1_500).status)
    }

    @Test fun snapshotObservationCannotReplaceNewPageCandidate() {
        val original = WechatTitleStabilizer()
        val accepted = original.observe("甲会话", a, 1_000)
        val snapshot = original.fork()
        original.reset()
        val newPage = original.observe("乙会话", b, 1_200)
        assertEquals(accepted.externalKey, snapshot.observe("甲会话", a, 1_400).externalKey)
        val confirmed = original.observe("乙会话", b, 1_600)
        assertEquals(newPage.externalKey, confirmed.externalKey)
        assertEquals("confirmed", confirmed.status)
        assertEquals("乙会话", confirmed.displayName)
    }

    @Test fun snapshotCreationDoesNotCountAsAnotherFrameOrAdvanceVoteClock() {
        val original = WechatTitleStabilizer()
        original.observe("甲会话", a, 1_000)
        val snapshot = original.fork()
        assertEquals("pending", snapshot.observe("甲会话", a, 1_000).status)
        assertEquals("pending", snapshot.observe("甲会话", a, 1_349).status)
        assertEquals("confirmed", snapshot.observe("甲会话", a, 1_350).status)
    }

    @Test fun snapshotRetainsPendingSourceAndSharesConfirmedIdentityStore() {
        val store = MemoryConversationIdentityStore()
        val original = WechatTitleStabilizer(store)
        val pending = original.observe(null, null, 1_000)
        val snapshot = original.fork()
        original.reset()
        assertEquals(pending.externalKey, snapshot.observe("甲会话", a, 1_400).externalKey)
        val confirmed = snapshot.observe("甲会话", a, 1_800)
        assertEquals("confirmed", confirmed.status)
        val later = original.observe("甲会话", a, 2_000)
        assertEquals(confirmed.externalKey, later.externalKey)
        assertEquals("confirmed", later.status)
    }
}
