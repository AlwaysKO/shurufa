package com.yuyan.imemodule.data.redpacket

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
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
class NativePacketRunnerTest {
    private val expected = "原生测试群（项目2）"
    private val card = "node:card:0/4"
    private val info = "node:info"
    private val open = "node:open"
    private val cardBox = IntRect(10, 80, 65, 105)
    private val infoBox = IntRect(90, 10, 98, 20)
    private val openBox = IntRect(40, 110, 60, 130)
    private data class View(val page: PacketPage, val targets: Map<String, IntRect> = emptyMap(),
        val signatures: Map<String, String> = targets.mapValues { "stable:${it.key}" },
        val display: Int = 7, val user: Int = 128, val window: Int = 0, val width: Int = 100,
        val height: Int = 200, val age: Long = 0, val id: Long? = null,
        val beforeCaptureMs: Long = 0, val packetUi: NativePacketUi = NativePacketUi.NONE)
    private fun chat(name: String = expected) = View(PacketPage(chatName = name,
        cards = listOf(card), chatInfoButton = info, uniqueChatToolbar = true), mapOf(card to cardBox, info to infoBox))
    private fun details(name: String? = expected, count: Int? = 2) = View(PacketPage(
        verifiedGroupDetails = true, groupDetailsName = name, groupDetailsMemberCount = count))
    private fun panel() = View(PacketPage(packetPanel = true, openButton = open), mapOf(open to openBox), packetUi = NativePacketUi.RECEIVE)
    private fun result(value: String = "已领取") = View(PacketPage(packetPanel = true, result = value), packetUi = NativePacketUi.DETAIL)
    private inner class Session(views: List<View>, val confirmed: Boolean = true) : NativePacketBackend {
        var now = 1000L
        var safe = true
        var observed = 0
        var backs = 0
        var commits = 0
        var readCostMs = 0L
        var tapSucceeds = true
        var throwCancellation = false
        val taps = mutableListOf<String>()
        val trace = mutableListOf<Pair<SilentPacketEngineStage, SilentPacketEngineEvent>>()
        private val queue = ArrayDeque(views)
        private val returned = mutableMapOf<Long, View>()
        override fun observe(): NativePacketSnapshot? {
            if (throwCancellation) throw CancellationException("test cancellation")
            observed++; now++
            val view = queue.removeFirstOrNull() ?: return null
            now += view.beforeCaptureMs
            val capturedAt = now
            now += readCostMs
            val id = view.id ?: observed.toLong()
            returned[id] = view
            return NativePacketSnapshot(NativePacketTicket(id, capturedAt - view.age, view.display,
                view.user, view.width, view.height, view.packetUi), view.window, view.page, view.targets, view.signatures)
        }
        override fun tap(x: Int, y: Int, frameId: Long): Boolean {
            val view = returned.getValue(frameId)
            taps += view.targets.entries.single { (_, box) -> x == (box.left + box.right) / 2 && y == (box.top + box.bottom) / 2 }.key
            return tapSucceeds
        }
        override fun back(): Boolean { backs++; return true }
        suspend fun run(mode: SilentPacketMode = SilentPacketMode.AUTO, reserve: () -> Boolean = { true }): String {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val request = PacketRequest(PacketCandidate("a".repeat(64), expected, confirmed),
                PendingIntent.getActivity(context, 23, Intent("native-runner-test"), PendingIntent.FLAG_IMMUTABLE),
                1000, targetUserId = 128)
            return NativePacketRunner({ now }, { 1000 }, { now += it }).run(request, this, { safe }, mode,
                beforeCard = { commits++; reserve() }, onTrace = { stage, event, _ -> trace += stage to event })
        }
    }

    @Test fun confirmedNotificationRequiresTwoFreshMatchingNativeReadsThenSingleCardAndOpen() = runBlocking {
        val session = Session(listOf(chat(), chat(), panel(), panel(), result(), result()))
        assertEquals("claimed", session.run())
        assertEquals(listOf(card, open), session.taps)
        assertEquals(0, session.backs); assertEquals(1, session.commits)
        assertEquals(6, session.observed)
    }
    @Test fun offDoesNotReadAndProbeDoesNotActOrReserve() = runBlocking {
        val off = Session(emptyList()); assertEquals("off", off.run(SilentPacketMode.OFF)); assertEquals(0, off.observed)
        val probe = Session(listOf(chat(), chat()))
        assertEquals("probe_candidate", probe.run(SilentPacketMode.PROBE))
        assertTrue(probe.taps.isEmpty()); assertEquals(0, probe.backs); assertEquals(0, probe.commits)
    }
    @Test fun unconfirmedNotificationNeedsExactDetailsAndRestoredChatBeforeCard() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), chat(),
            panel(), panel(), result(), result()), confirmed = false)
        assertEquals("claimed", session.run())
        assertEquals(listOf(info, card, open), session.taps)
        assertEquals(1, session.backs); assertEquals(1, session.commits)
    }
    @Test fun rawHeaderMismatchCanOnlyNavigateInfoUntilExactDetailsProveGroup() = runBlocking {
        val raw = "原生测试群（项目2）2)"
        val session = Session(listOf(chat(raw), chat(raw), details(), details(), chat(raw), chat(raw), chat(raw),
            panel(), panel(), result(), result()))
        assertEquals("claimed", session.run())
        assertEquals(listOf(info, card, open), session.taps); assertEquals(1, session.backs)
    }
    @Test fun wrongMissingOrChangingDetailsNeverAuthorizeCard() = runBlocking {
        for ((first, second) in listOf(details("另一个群") to details("另一个群"),
            details(null) to details(null), details(count = 1) to details(count = 1), details() to details(count = 3))) {
            val session = Session(listOf(chat(), chat(), first, second), confirmed = false)
            assertEquals("unknown", session.run())
            assertEquals(listOf(info), session.taps); assertEquals(0, session.backs); assertEquals(0, session.commits)
        }
    }
    @Test fun returnMustRestoreOriginalRawHeaderWindowMenuAndSignature() = runBlocking {
        val original = chat("native-header-not-notice")
        for (changed in listOf(chat("another-native-header"), original.copy(window = 2),
            original.copy(signatures = original.signatures + (info to "changed")),
            original.copy(targets = original.targets + (info to IntRect(89, 10, 97, 20))))) {
            val session = Session(listOf(original, original, details(), details(), original, changed, original))
            assertEquals("unknown", session.run()); assertEquals(listOf(info), session.taps)
            assertEquals(1, session.backs); assertEquals(0, session.commits)
        }
    }
    @Test fun reserveFailureAndProtectionDuringReserveNeverTapCard() = runBlocking {
        val duplicate = Session(listOf(chat(), chat()))
        assertEquals("duplicate", duplicate.run { false }); assertTrue(duplicate.taps.isEmpty())
        val protected = Session(listOf(chat(), chat()))
        assertEquals("protected", protected.run { protected.safe = false; true }); assertTrue(protected.taps.isEmpty())
    }
    @Test fun confirmationRejectsAccountDisplayWindowDimensionsAndOldTicket() = runBlocking {
        for (changed in listOf(chat().copy(user = 0), chat().copy(display = 0), chat().copy(display = 8),
            chat().copy(window = 1), chat().copy(width = 101), chat().copy(age = 1001), chat().copy(id = 1))) {
            val session = Session(listOf(chat(), changed)); assertEquals("unknown", session.run())
            assertTrue(session.taps.isEmpty()); assertEquals(0, session.commits)
        }
    }
    @Test fun changingTargetPathSignatureOrGeometryCannotClick() = runBlocking {
        for (changed in listOf(chat().copy(signatures = chat().signatures + (card to "changed")),
            chat().copy(targets = chat().targets + (card to IntRect(11, 80, 66, 105))),
            chat().copy(page = chat().page.copy(cards = listOf("node:card:0/5"))))) {
            val session = Session(listOf(chat(), changed)); assertEquals("unknown", session.run()); assertTrue(session.taps.isEmpty())
        }
    }
    @Test fun failedCardSubmissionIsNeverReplayed() = runBlocking {
        val session = Session(listOf(chat(), chat(), chat(), chat(), panel(), panel()))
        session.tapSucceeds = false
        assertEquals("unknown", session.run()); assertEquals(listOf(card), session.taps)
        assertEquals(2, session.observed); assertEquals(1, session.commits)
    }
    @Test fun resultRequiresIndependentMatchingReadAndNeverReopensUnknownSubmission() = runBlocking {
        val session = Session(listOf(chat(), chat(), panel(), panel(), result(), result("已被领完"), panel(), panel()))
        assertEquals("unknown", session.run()); assertEquals(listOf(card, open), session.taps)
        assertEquals(6, session.observed); assertEquals(1, session.commits)
    }
    @Test fun unavailableNodesAreExplicitAndBoundedWithoutAnyClick() = runBlocking {
        val session = Session(emptyList())
        assertEquals("nodes_unavailable", session.run())
        assertTrue(session.taps.isEmpty()); assertTrue(session.now < 3000)
        assertTrue(session.trace.any { it.second == SilentPacketEngineEvent.NODES_UNAVAILABLE })
    }
    @Test fun unknownPageCannotWaitPastOriginalDeadline() = runBlocking {
        val session = Session(List(60) { View(PacketPage()) })
        assertEquals("unknown", session.run()); assertTrue(session.taps.isEmpty()); assertTrue(session.now in 13000..14000)
        assertTrue(session.trace.any { it.second == SilentPacketEngineEvent.TIMEOUT })
    }
    @Test fun cancellationPropagatesAndDiagnosticsNeverContainNativeHeader() = runBlocking {
        val session = Session(emptyList()); session.throwCancellation = true
        try { session.run(); fail("cancellation must propagate") } catch (_: CancellationException) { }
        assertTrue(session.taps.isEmpty()); assertFalse(session.trace.toString().contains(expected))
    }

    @Test fun slowNativeObservationKeepsOnlyLatestTicketFreshForMoneyClicks() = runBlocking {
        val session = Session(listOf(chat(), chat(), panel(), panel(), result(), result()))
        session.readCostMs = 800
        assertEquals("claimed", session.run())
        assertEquals(listOf(card, open), session.taps)
        assertEquals(1, session.commits)
    }
    @Test fun slowDetailsAndRestorationUseBoundedBaselineButFreshConfirmation() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), chat(),
            panel(), panel(), result(), result()), confirmed = false)
        session.readCostMs = 800
        assertEquals("claimed", session.run())
        assertEquals(listOf(info, card, open), session.taps)
        assertEquals(1, session.backs)
    }
    @Test fun comparisonBaselineOlderThanFourSecondsStillCannotAuthorizeCard() = runBlocking {
        val session = Session(listOf(chat(), chat().copy(beforeCaptureMs = 4001)))
        assertEquals("unknown", session.run())
        assertTrue(session.taps.isEmpty()); assertEquals(0, session.commits)
        assertTrue(session.trace.any { it.second == SilentPacketEngineEvent.TARGET_UNCONFIRMED })
    }

    @Test fun ordinaryChatResultWordsCannotClaimWithoutDedicatedDetailActivity() = runBlocking {
        val ordinary = result().copy(packetUi = NativePacketUi.NONE)
        val session = Session(listOf(chat(), chat(), panel(), panel(), ordinary, ordinary))
        assertNotEquals("claimed", session.run())
        assertEquals(listOf(card, open), session.taps)
    }
    @Test fun openRequiresReceiveActivityInBothIndependentObservations() = runBlocking {
        for (views in listOf(listOf(panel().copy(packetUi = NativePacketUi.NONE), panel()),
            listOf(panel(), panel().copy(packetUi = NativePacketUi.DETAIL)))) {
            val session = Session(listOf(chat(), chat()) + views)
            assertEquals("unknown", session.run())
            assertEquals(listOf(card), session.taps)
        }
    }
    @Test fun resultActivityCannotChangeBetweenConfirmationReads() = runBlocking {
        val session = Session(listOf(chat(), chat(), panel(), panel(), result(), result().copy(packetUi = NativePacketUi.RECEIVE)))
        assertNotEquals("claimed", session.run())
        assertEquals(listOf(card, open), session.taps)
    }
}
