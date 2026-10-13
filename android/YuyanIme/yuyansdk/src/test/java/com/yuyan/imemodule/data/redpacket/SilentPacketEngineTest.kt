package com.yuyan.imemodule.data.redpacket

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SilentPacketEngineTest {
    private val target = IntRect(10, 20, 50, 60)
    private fun chat(name: String = "测试群") = PacketVisualMatch(
        PacketPage(chatName = name, groupChat = true, chatInfoButton = "visual:info", cards = listOf("visual:card:one")),
        mapOf("visual:info" to target, "visual:card:one" to target))
    private fun details() = PacketVisualMatch(PacketPage(verifiedGroupDetails = true), emptyMap())
    private fun panel() = PacketVisualMatch(PacketPage(packetPanel = true, openButton = "visual:open"), mapOf("visual:open" to target))
    private fun result() = PacketVisualMatch(PacketPage(packetPanel = true, result = "已领取"), emptyMap())
    private fun request(confirmed: Boolean = false): PacketRequest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return PacketRequest(PacketCandidate("id", "测试群", confirmed),
            PendingIntent.getActivity(context, 0, Intent("test-silent-packet"), PendingIntent.FLAG_IMMUTABLE), 1000)
    }
    private inner class Session(pages: List<PacketVisualMatch>) : SilentPacketBackend {
        var now = 1000L
        var frameId = 0L
        var display = 7
        var stale = false
        var repeatId = false
        var switchDisplay = false
        var readDelay = 0L
        val readDelays = ArrayDeque<Long>()
        var sourceFacts: SilentPacketSourceFacts? = null
        var transientNull = false
        var safe = true
        var failTap = false
        var captures = 0
        var backs = 0
        val taps = mutableListOf<Long>()
        val remaining = ArrayDeque(pages)
        private val matches = java.util.IdentityHashMap<Bitmap, PacketVisualMatch>()
        override fun capture(): SilentPacketFrame? {
            captures++
            if (transientNull && captures % 2 == 1) return null
            if (remaining.isEmpty()) return null
            val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
            matches[bitmap] = remaining.removeFirst()
            if (!repeatId || frameId == 0L) frameId++
            return SilentPacketFrame(bitmap, frameId, if (stale) now - 5000 else now,
                if (switchDisplay && captures > 1) display + 1 else display)
        }
        override fun tap(x: Int, y: Int, frameId: Long): Boolean { taps += frameId; return !failTap }
        override fun back(): Boolean { backs++; return true }
        val runner get() = SilentPacketRunner(read = { frame ->
            now += if (readDelays.isEmpty()) readDelay else readDelays.removeFirst()
            val match = matches.remove(frame.bitmap)!!
            frame.bitmap.recycle()
            PacketVisualSnapshot(match, frame.displayId, IntRect(0, 0, 100, 200), 0, 0, frame.capturedAt,
                match.targets.mapValues { "same" }, sourceFacts)
        }, clock = { now }, wallClock = { 1000L }, pause = { now += it })
        suspend fun run(mode: SilentPacketMode = SilentPacketMode.AUTO) = runner.run(request(), this, { safe }, mode)
    }
    @Test fun defaultProbeAndOffNeverInteract() = runBlocking {
        val off = Session(listOf(chat()))
        assertEquals("off", off.run(SilentPacketMode.OFF)); assertEquals(0, off.captures)
        val probe = Session(listOf(chat()))
        assertEquals("probe_candidate", probe.run(SilentPacketMode.PROBE))
        assertTrue(probe.taps.isEmpty()); assertEquals(0, probe.backs)
    }
    @Test fun autoVerifiesGroupAndEachClickWithNewFrameThenConfirmsResult() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), panel(), panel(), result(), result()))
        assertEquals("claimed", session.run())
        assertEquals(listOf(2L, 6L, 8L), session.taps)
        assertEquals(1, session.backs)
    }
    @Test fun wrongConversationNeverClicksEvenWithConfirmedNotification() = runBlocking {
        val session = Session(listOf(chat("其他群"), chat("其他群")))
        assertEquals("mismatch", session.runner.run(request(true), session, { true }, SilentPacketMode.AUTO))
        assertTrue(session.taps.isEmpty())
    }
    @Test fun stalePrimaryRepeatedFrameOrChangedDisplayNeverClicks() = runBlocking {
        for (kind in listOf("stale", "primary", "repeat")) {
            val session = Session(listOf(chat(), chat()))
            session.stale = kind == "stale"; session.display = if (kind == "primary") 0 else 7
            session.repeatId = kind == "repeat"
            assertEquals("unknown", session.run())
            assertTrue(session.taps.isEmpty())
        }
    }
    @Test fun changedConfirmationAndUnverifiedDetailsNeverReachPacketCard() = runBlocking {
        val changed = Session(listOf(chat(), chat("其他群")))
        assertEquals("unknown", changed.run()); assertTrue(changed.taps.isEmpty())
        val unknownDetails = Session(listOf(chat(), chat(), PacketVisualMatch(PacketPage(), emptyMap())))
        assertEquals("unknown", unknownDetails.run())
        assertEquals(1, unknownDetails.taps.size); assertEquals(0, unknownDetails.backs)
    }
    @Test fun protectionBeforeAndBetweenFramesPreventsInteraction() = runBlocking {
        val before = Session(listOf(chat())); before.safe = false
        assertEquals("protected", before.run()); assertEquals(0, before.captures)
        val between = Session(listOf(chat(), chat()))
        var checks = 0
        assertEquals("protected", between.runner.run(request(), between, { ++checks < 6 }, SilentPacketMode.AUTO))
        assertTrue(between.taps.isEmpty())
    }
    @Test fun unknownAfterOpenDoesNotReplayOpenOrBack() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), panel(), panel()))
        assertEquals("unknown", session.run())
        assertEquals(3, session.taps.size); assertEquals(1, session.backs)
    }
    @Test fun switchingDisplayOrSlowRecognitionInvalidatesConfirmation() = runBlocking {
        val changed = Session(listOf(chat(), chat())); changed.switchDisplay = true
        assertEquals("unknown", changed.run()); assertTrue(changed.taps.isEmpty())
        val slow = Session(listOf(chat(), chat())); slow.readDelay = 5000
        assertEquals("unknown", slow.run()); assertTrue(slow.taps.isEmpty())
    }
    @Test fun transientMissingRenderFramesAreReadAgainWithoutRetryingActions() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), panel(), panel(), result(), result()))
        session.transientNull = true
        assertEquals("claimed", session.run())
        assertEquals(listOf(2L, 6L, 8L), session.taps); assertEquals(1, session.backs)
    }
    @Test fun missingRenderFramesHaveOneSecondBoundAndNoInteraction() = runBlocking {
        val session = Session(emptyList())
        assertEquals("unknown", session.run())
        assertEquals(2000L, session.now)
        assertEquals(11, session.captures)
        assertTrue(session.taps.isEmpty()); assertEquals(0, session.backs)
    }
    @Test fun unconfirmedResultAndRepeatedOpenPanelsNeverBecomeSuccessOrRetry() = runBlocking {
        val unconfirmed = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), panel(), panel(), result(), panel()))
        assertEquals("unknown", unconfirmed.run()); assertEquals(3, unconfirmed.taps.size)
        val repeated = Session(listOf(chat(), chat(), details(), details(), chat(), chat()) + List(40) { panel() })
        assertEquals("unknown", repeated.run()); assertEquals(3, repeated.taps.size)
    }
    @Test fun failedTapDoesNotRetryOrNavigate() = runBlocking {
        val session = Session(listOf(chat(), chat())); session.failTap = true
        assertEquals("unknown", session.run())
        assertEquals(1, session.taps.size); assertEquals(0, session.backs)
    }
    @Test fun cardReservationFailureStopsBeforeCardAndRunsOnceAfterGroupVerification() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), panel(), panel()))
        var calls = 0
        assertEquals("duplicate", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            beforeCard = { calls++; assertEquals(1, session.backs); false }))
        assertEquals(1, calls); assertEquals(1, session.taps.size)
    }
    @Test fun cardReservationIsNeverUsedForProbeOrGroupInfoAndOnlyOnceForSuccess() = runBlocking {
        var calls = 0
        val probe = Session(listOf(chat()))
        assertEquals("probe_candidate", probe.runner.run(request(), probe, { true }, SilentPacketMode.PROBE, { calls++; true }))
        assertEquals(0, calls)
        val info = Session(listOf(chat(), chat()))
        assertEquals("unknown", info.runner.run(request(), info, { true }, SilentPacketMode.AUTO, { calls++; true }))
        assertEquals(0, calls)
        val success = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), panel(), panel(), result(), result()))
        assertEquals("claimed", success.runner.run(request(), success, { true }, SilentPacketMode.AUTO, { calls++; true }))
        assertEquals(1, calls)
    }
    @Test fun coroutineCancellationIsPropagatedWithoutAnyInteraction() = runBlocking {
        val session = Session(listOf(chat()))
        val runner = SilentPacketRunner(read = { throw CancellationException("cancelled") },
            clock = { 1000L }, wallClock = { 1000L }, pause = {})
        try { runner.run(request(), session, { true }, SilentPacketMode.AUTO); fail("must cancel") }
        catch (_: CancellationException) { }
        assertTrue(session.taps.isEmpty()); assertEquals(0, session.backs)
    }

    @Test fun diagnosticsSeparateMissingCaptureFromSlowRecognitionWithoutRelaxingTtl() = runBlocking {
        for (slow in listOf(false, true)) {
            val session = Session(if (slow) List(3) { chat() } else emptyList())
            if (slow) session.readDelay = 1_001
            val traces = mutableListOf<Triple<SilentPacketEngineStage, SilentPacketEngineEvent, Long>>()
            assertEquals("unknown", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
                onTrace = { stage, event, elapsed -> traces += Triple(stage, event, elapsed) }))
            if (slow) {
                assertTrue(traces.any { it.first == SilentPacketEngineStage.OCR && it.second == SilentPacketEngineEvent.COMPLETED })
                assertEquals(SilentPacketEngineEvent.FRAME_STALE, traces.last().second)
                assertTrue(traces.last().third >= 3_003L)
            } else {
                assertEquals(SilentPacketEngineStage.CAPTURE, traces.lastOrNull()?.first)
                assertEquals(SilentPacketEngineEvent.CAPTURE_UNAVAILABLE, traces.lastOrNull()?.second)
                assertEquals(1_000L, traces.lastOrNull()?.third)
            }
            assertTrue(session.taps.isEmpty())
        }
    }

    @Test fun diagnosticsIdentifyGroupMismatchAndFailedInfoClick() = runBlocking {
        for (mismatch in listOf(true, false)) {
            val session = Session(if (mismatch) listOf(chat("其他群"), chat("其他群")) else listOf(chat(), chat()))
            session.failTap = !mismatch
            val traces = mutableListOf<Pair<SilentPacketEngineStage, SilentPacketEngineEvent>>()
            assertEquals(if (mismatch) "mismatch" else "unknown",
                session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
                    onTrace = { stage, event, _ -> traces += stage to event }))
            assertEquals(if (mismatch) SilentPacketEngineStage.GROUP_CHECK else SilentPacketEngineStage.INFO_CLICK,
                traces.lastOrNull()?.first)
            assertEquals(if (mismatch) SilentPacketEngineEvent.GROUP_MISMATCH else SilentPacketEngineEvent.ACTION_FAILED,
                traces.lastOrNull()?.second)
            assertEquals(if (mismatch) 0 else 1, session.taps.size)
        }
    }

    @Test fun diagnosticsIdentifyOcrExceptionsAndRejectedFramesWithoutExceptionText() = runBlocking {
        for (throws in listOf(true, false)) {
            val session = Session(listOf(chat()))
            val traces = mutableListOf<Pair<SilentPacketEngineStage, SilentPacketEngineEvent>>()
            val runner = SilentPacketRunner(read = {
                if (throws) throw IllegalStateException("private recognition details") else null
            }, clock = { session.now }, wallClock = { 1000L }, pause = { session.now += it })
            assertEquals("unknown", runner.run(request(), session, { true }, SilentPacketMode.AUTO,
                onTrace = { stage, event, _ -> traces += stage to event }))
            assertEquals(SilentPacketEngineStage.OCR, traces.lastOrNull()?.first)
            assertEquals(if (throws) SilentPacketEngineEvent.READ_FAILED else SilentPacketEngineEvent.READ_REJECTED,
                traces.lastOrNull()?.second)
            assertTrue(session.taps.isEmpty())
        }
    }
    private suspend fun assertWaitingReason(pages: List<PacketVisualMatch>, expected: SilentPacketEngineEvent,
        stage: SilentPacketEngineStage = SilentPacketEngineStage.GROUP_CHECK, expectedTaps: Int = 0,
        facts: SilentPacketSourceFacts? = null) {
        val session = Session(pages)
        session.sourceFacts = facts
        val traces = mutableListOf<Pair<SilentPacketEngineStage, SilentPacketEngineEvent>>()
        assertEquals("unknown", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            onTrace = { phase, event, _ -> traces += phase to event }))
        assertEquals("same wait reason must not be logged for every frame", 1, traces.count { it == (stage to expected) })
        assertEquals(expectedTaps, session.taps.size)
        assertTrue(traces.none { it.second == SilentPacketEngineEvent.WAIT_GROUP_DETAILS } || expectedTaps > 0)
    }

    @Test fun missingChatTitleHasOneFixedWaitingReasonWithoutInteraction() = runBlocking {
        assertWaitingReason(List(8) { PacketVisualMatch(PacketPage(), emptyMap()) }, SilentPacketEngineEvent.WAIT_CHAT_TITLE)
    }
    @Test fun titleCandidateWithoutGroupOrMenuHasDistinctFixedReason() = runBlocking {
        assertWaitingReason(List(8) { PacketVisualMatch(PacketPage(), emptyMap()) },
            SilentPacketEngineEvent.WAIT_TITLE_UNVERIFIED, facts = SilentPacketSourceFacts(1, 0, 0, false, 0))
    }
    @Test fun verifiedChatWithoutInfoTargetWaitsWithoutClicking() = runBlocking {
        assertWaitingReason(List(8) { PacketVisualMatch(PacketPage(chatName = "测试群", groupChat = true), emptyMap()) },
            SilentPacketEngineEvent.WAIT_INFO_TARGET)
    }
    @Test fun afterInfoClickUnknownPageReportsMissingGroupDetailsRatherThanTitle() = runBlocking {
        assertWaitingReason(listOf(chat(), chat()) + List(8) { PacketVisualMatch(PacketPage(), emptyMap()) },
            SilentPacketEngineEvent.WAIT_GROUP_DETAILS, expectedTaps = 1)
    }
    @Test fun verifiedGroupReturnedToChatWithoutCardsReportsCardWait() = runBlocking {
        val emptyChat = PacketVisualMatch(PacketPage(chatName = "测试群", groupChat = true), emptyMap())
        assertWaitingReason(listOf(chat(), chat(), details(), details()) + List(8) { emptyChat },
            SilentPacketEngineEvent.WAIT_CARD, SilentPacketEngineStage.CARD_CLICK, 1)
    }
    @Test fun afterCardClickMissingPanelReportsPanelWaitWithoutReplay() = runBlocking {
        assertWaitingReason(listOf(chat(), chat(), details(), details(), chat(), chat()) +
            List(8) { PacketVisualMatch(PacketPage(), emptyMap()) },
            SilentPacketEngineEvent.WAIT_OPEN_PANEL, SilentPacketEngineStage.OPEN_CLICK, 2)
    }
    @Test fun panelWithoutOpenTargetHasDistinctWaitReason() = runBlocking {
        assertWaitingReason(listOf(chat(), chat(), details(), details(), chat(), chat()) +
            List(8) { PacketVisualMatch(PacketPage(packetPanel = true), emptyMap()) },
            SilentPacketEngineEvent.WAIT_OPEN_TARGET, SilentPacketEngineStage.OPEN_CLICK, 2)
    }
    @Test fun submittedOpenWaitsForResultAndNeverRepeatsOpen() = runBlocking {
        assertWaitingReason(listOf(chat(), chat(), details(), details(), chat(), chat(), panel(), panel()) + List(8) { panel() },
            SilentPacketEngineEvent.WAIT_RESULT, SilentPacketEngineStage.RESULT_CHECK, 3)
    }

    @Test fun validAutoFramesPublishPrimitiveFactsWithoutChatNameOrTargetIdentifiers() = runBlocking {
        val session = Session(listOf(chat()))
        session.sourceFacts = SilentPacketSourceFacts(1, 1, 1, false, 2)
        val pages = mutableListOf<SilentPacketReadDiagnostic>()
        assertEquals("unknown", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            onPageFacts = { pages += it }))
        assertEquals(1, pages.size)
        assertEquals(SilentPacketReadResult.VALID, pages.single().result)
        assertEquals(SilentPacketPageFacts(true, true, true, false, false, 1), pages.single().facts)
        assertEquals(session.sourceFacts, pages.single().sourceFacts)
        assertFalse(pages.single().renderFacts().contains("测试群"))
        assertFalse(pages.single().renderFacts().contains("visual:"))
    }
    @Test fun staleOrProtectedFramesNeverPublishPageFacts() = runBlocking {
        for (kind in listOf("capture_stale", "ocr_stale", "protected")) {
            val session = Session(listOf(chat()))
            session.stale = kind == "capture_stale"
            session.readDelay = if (kind == "ocr_stale") 2000 else 0
            session.safe = kind != "protected"
            val pages = mutableListOf<SilentPacketReadDiagnostic>()
            session.runner.run(request(), session, { session.safe }, SilentPacketMode.AUTO, onPageFacts = { pages += it })
            assertTrue(pages.isEmpty()); assertTrue(session.taps.isEmpty())
        }
    }
    @Test fun pageObserverFailureCannotChangeSuccessfulDecisionOrReplayActions() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), panel(), panel(), result(), result()))
        var observed = 0
        assertEquals("claimed", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            onPageFacts = { observed++; throw IllegalStateException("private observer failure") }))
        assertEquals(10, observed); assertEquals(3, session.taps.size); assertEquals(1, session.backs)
    }
    @Test fun lateWrongTitleReportsMismatchInsteadOfUnverifiedGroupWithoutClicking() = runBlocking {
        val session = Session(listOf(PacketVisualMatch(PacketPage(), emptyMap()), chat("其他群"), chat("其他群")))
        val traces = mutableListOf<SilentPacketEngineEvent>()
        val pages = mutableListOf<SilentPacketReadDiagnostic>()
        assertEquals("mismatch", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            onPageFacts = { pages += it }, onTrace = { _, event, _ -> traces += event }))
        assertTrue(traces.contains(SilentPacketEngineEvent.GROUP_MISMATCH))
        assertFalse(traces.contains(SilentPacketEngineEvent.GROUP_UNVERIFIED))
        assertFalse(pages.last().groupComparison?.exactEqual ?: true)
        assertEquals(3, pages.last().groupComparison?.observedCharacterCount)
        assertTrue(session.taps.isEmpty()); assertEquals(0, session.backs)
    }
    @Test fun normalizationOnlyMatchStillCannotAuthorizeInfoOrPacketClick() = runBlocking {
        val session = Session(listOf(PacketVisualMatch(PacketPage(), emptyMap()), chat("测 试群"), chat("测 试群")))
        val pages = mutableListOf<SilentPacketReadDiagnostic>()
        assertEquals("mismatch", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            onPageFacts = { pages += it }))
        assertTrue(pages.last().groupComparison?.whitespaceEqual == true)
        assertTrue(pages.last().groupComparison?.normalizedEqual == true)
        assertFalse(pages.last().groupComparison?.exactEqual ?: true)
        assertTrue(session.taps.isEmpty()); assertEquals(0, session.backs)
    }
    @Test fun isolatedWrongOcrBeforeInfoCanRecoverOnlyWithExactDoubleFrameConfirmation() = runBlocking {
        val session = Session(listOf(chat("误识别群"), chat(), chat(), details(), details(), chat(), chat(), panel(), panel(), result(), result()))
        assertEquals("claimed", session.run())
        assertEquals(listOf(3L, 7L, 9L), session.taps); assertEquals(1, session.backs)
    }
    @Test fun twoFreshStableWrongTitlesStopWithoutAnyAction() = runBlocking {
        val session = Session(listOf(chat("错误群"), chat("错误群"), chat(), chat()))
        assertEquals("mismatch", session.run())
        assertEquals(2, session.captures); assertTrue(session.taps.isEmpty()); assertEquals(0, session.backs)
    }
    @Test fun changingWrongTitlesWaitOnlyUntilOriginalDeadlineAndNeverClick() = runBlocking {
        val session = Session(List(40) { chat(if (it % 2 == 0) "错误甲" else "错误乙") })
        val traces = mutableListOf<SilentPacketEngineEvent>()
        assertEquals("unknown", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            onTrace = { _, event, _ -> traces += event }))
        assertEquals(SilentPacketEngineEvent.TIMEOUT, traces.last())
        assertTrue(session.now >= 13_000); assertTrue(session.captures < 40)
        assertTrue(session.taps.isEmpty()); assertEquals(0, session.backs)
    }
    @Test fun wrongTitleAfterInfoStillStopsOnFirstFrameWithoutRetry() = runBlocking {
        val session = Session(listOf(chat(), chat(), chat("错误群"), details(), details()))
        assertEquals("unknown", session.run())
        assertEquals(3, session.captures); assertEquals(1, session.taps.size); assertEquals(0, session.backs)
    }
    @Test fun initialOcrStaleIsDiscardedBeforeActionsAndNextFreshFrameCanRecover() = runBlocking {
        val session = Session(listOf(chat(), chat(), chat(), details(), details(), chat(), chat(), panel(), panel(), result(), result()))
        session.readDelays.addLast(1600)
        val pages = mutableListOf<SilentPacketReadDiagnostic>()
        assertEquals("claimed", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            onPageFacts = { pages += it }))
        assertEquals(10, pages.size); assertTrue(pages.all { it.frameAgeMs <= 1000 })
        assertEquals(listOf(3L, 7L, 9L), session.taps); assertEquals(1, session.backs)
    }
    @Test fun continuouslySlowOcrHasTwoDiscardRetriesAndCannotReachAnyAction() = runBlocking {
        val session = Session(List(10) { chat() }); session.readDelay = 1600
        val pages = mutableListOf<SilentPacketReadDiagnostic>()
        assertEquals("unknown", session.runner.run(request(), session, { true }, SilentPacketMode.AUTO,
            onPageFacts = { pages += it }))
        assertEquals(3, session.captures); assertTrue(pages.isEmpty()); assertTrue(session.taps.isEmpty())
    }
    @Test fun ocrStaleAfterInfoDoesNotRetryOrReplaySubmittedAction() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat()))
        session.readDelays.addAll(listOf(0L, 0L, 1600L))
        assertEquals("unknown", session.run())
        assertEquals(3, session.captures); assertEquals(1, session.taps.size); assertEquals(0, session.backs)
    }
    @Test fun wrongInfoConfirmationBeforeAnyTapNeedsNewExactDoubleFramesToRecover() = runBlocking {
        val session = Session(listOf(chat(), chat("误识别群"), chat(), chat(), details(), details(), chat(), chat(), panel(), panel(), result(), result()))
        assertEquals("claimed", session.run())
        assertEquals(listOf(4L, 8L, 10L), session.taps); assertEquals(1, session.backs)
    }
}
