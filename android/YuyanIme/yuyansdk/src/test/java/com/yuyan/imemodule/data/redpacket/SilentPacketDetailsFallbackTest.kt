package com.yuyan.imemodule.data.redpacket

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SilentPacketDetailsFallbackTest {
    private val expected = "测试群"
    private val rawHeader = "测试群2)"
    private val info = IntRect(88, 8, 98, 14)
    private val card = IntRect(8, 70, 60, 100)
    private val open = IntRect(40, 110, 60, 130)
    private data class View(val match: PacketVisualMatch, val infoSignature: String? = "raw-header-menu",
                            val delay: Long = 0)
    private fun chat(header: String = rawHeader, signature: String? = "raw-header-menu", box: IntRect = info) =
        View(PacketVisualMatch(PacketPage(chatName = header, chatInfoButton = "visual:info", uniqueChatToolbar = true,
            cards = listOf("visual:card:one")), mapOf("visual:info" to box, "visual:card:one" to card)), signature)
    private fun details(name: String? = expected, count: Int? = 2) = View(PacketVisualMatch(
        PacketPage(verifiedGroupDetails = true, groupDetailsName = name, groupDetailsMemberCount = count), emptyMap()))
    private fun panel() = View(PacketVisualMatch(PacketPage(packetPanel = true, openButton = "visual:open"),
        mapOf("visual:open" to open)))
    private fun result() = View(PacketVisualMatch(PacketPage(packetPanel = true, result = "已领取"), emptyMap()))

    private inner class Session(views: List<View>) : SilentPacketBackend {
        var now = 1000L
        var sequence = 0L
        var backs = 0
        var sourceFacts: SilentPacketSourceFacts? = SilentPacketSourceFacts(1, 1, 0, false, 1)
        val traces = mutableListOf<SilentPacketEngineEvent>()
        val pages = mutableListOf<SilentPacketReadDiagnostic>()
        val taps = mutableListOf<String>()
        val bitmaps = mutableListOf<Bitmap>()
        private val queue = ArrayDeque(views)
        private val images = java.util.IdentityHashMap<Bitmap, View>()
        private val frames = mutableMapOf<Long, View>()
        override fun capture(): SilentPacketFrame? {
            if (queue.isEmpty()) return null
            val view = queue.removeFirst()
            val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
            bitmaps += bitmap; images[bitmap] = view; frames[++sequence] = view
            return SilentPacketFrame(bitmap, sequence, now, 7)
        }
        override fun tap(x: Int, y: Int, frameId: Long): Boolean {
            taps += frames.getValue(frameId).match.targets.entries.single { (_, box) ->
                x == (box.left + box.right) / 2 && y == (box.top + box.bottom) / 2
            }.key
            return true
        }
        override fun back(): Boolean { backs++; return true }
        suspend fun run(): String {
            val context = ApplicationProvider.getApplicationContext<Context>()
            val request = PacketRequest(PacketCandidate("fallback-id", expected, true),
                PendingIntent.getActivity(context, 17, Intent("details-fallback-test"), PendingIntent.FLAG_IMMUTABLE), 1000)
            return SilentPacketRunner(read = { frame ->
                val view = images.remove(frame.bitmap)!!
                now += view.delay
                val signatures = view.match.targets.filterKeys { it != "visual:info" }.mapValues { "stable-card-panel" }
                    .toMutableMap().apply { view.infoSignature?.let { if ("visual:info" in view.match.targets) put("visual:info", it) } }
                PacketVisualSnapshot(view.match, frame.displayId, IntRect(0, 0, 100, 200), 0, 0,
                    frame.capturedAt, signatures, sourceFacts)
            }, clock = { now }, wallClock = { 1000 }, pause = { now += it })
                .run(request, this, { true }, SilentPacketMode.AUTO, onPageFacts = { pages += it },
                    onTrace = { _, event, _ -> traces += event })
        }
        fun assertNoMoney() {
            assertTrue(taps.none { it.startsWith("visual:card:") || it == "visual:open" })
            assertTrue(bitmaps.all { it.isRecycled })
        }
    }

    @Test fun stableRawHeaderCanUseExactFullGroupDetailsThenRestoreBeforeCard() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), chat(),
            panel(), panel(), result(), result()))
        assertEquals("claimed", session.run())
        assertEquals(listOf("visual:info", "visual:card:one", "visual:open"), session.taps)
        assertEquals(1, session.backs)
        assertTrue(session.bitmaps.all { it.isRecycled })
    }

    @Test fun fallbackStillOpensOnlyReadOnlyInfoWhenDetailsBelongToAnotherGroup() = runBlocking {
        val session = Session(listOf(chat(), chat(), details("其他群"), details("其他群")))
        session.run()
        assertEquals(listOf("visual:info"), session.taps)
        assertEquals(0, session.backs); session.assertNoMoney()
    }

    @Test fun incompletePrivateOrChangingDetailsCannotAuthorizeBackOrCard() = runBlocking {
        for ((first, second) in listOf(details(null) to details(null), details(count = 1) to details(count = 1),
            details(count = null) to details(count = null), details(count = 2) to details(count = 3),
            details() to details("其他群"))) {
            val session = Session(listOf(chat(), chat(), first, second))
            session.run()
            assertEquals(listOf("visual:info"), session.taps)
            assertEquals(0, session.backs); session.assertNoMoney()
        }
    }

    @Test fun returnRequiresOriginalRawHeaderMenuSignatureAndBounds() = runBlocking {
        for (changed in listOf(chat("其他群"), chat(signature = "changed-header-menu"),
            chat(box = IntRect(87, 8, 97, 14)))) {
            val session = Session(listOf(chat(), chat(), details(), details(), chat(), changed, chat()))
            session.run()
            assertEquals(listOf("visual:info"), session.taps)
            assertEquals(1, session.backs); session.assertNoMoney()
        }
    }

    @Test fun fallbackNeverUsesMissingEmptyOrChangingInfoSignature() = runBlocking {
        for ((first, second) in listOf(chat(signature = null) to chat(signature = null),
            chat(signature = "") to chat(signature = ""), chat() to chat(signature = "different"))) {
            val session = Session(listOf(first, second, details(), details()))
            session.run(); assertTrue(session.taps.isEmpty()); session.assertNoMoney()
        }
    }

    @Test fun explicitListTitlePanelOrResultCannotBecomeFallbackInfo() = runBlocking {
        val base = chat()
        for (view in listOf(chat("微信"), chat("WeChat"), base.copy(match = base.match.copy(
            page = base.match.page.copy(uniqueChatToolbar = false))), base.copy(match = base.match.copy(
            page = base.match.page.copy(packetPanel = true))), base.copy(match = base.match.copy(
            page = base.match.page.copy(result = "已领取"))))) {
            val session = Session(listOf(view, view, details(), details()))
            session.run(); assertTrue(session.taps.isEmpty()); session.assertNoMoney()
        }
    }

    @Test fun staleDetailsAfterInfoCannotRetryOrReachMoney() = runBlocking {
        val session = Session(listOf(chat(), chat(), details().copy(delay = 1500), details(), chat(), chat()))
        session.run()
        assertEquals(listOf("visual:info"), session.taps)
        assertEquals(0, session.backs); session.assertNoMoney()
    }

    @Test fun missingAmbiguousOrExplicitNonChatFactsCannotEnableFallback() = runBlocking {
        val facts = SilentPacketSourceFacts(1, 1, 0, false, 1)
        for (rejected in listOf(null, facts.copy(menuDotCount = 2),
            facts.copy(bottomWechatTabsPresent = true), facts.copy(wechatTitleLabelPresent = true),
            facts.copy(loginPromptPresent = true), facts.copy(loadingPromptPresent = true),
            facts.copy(networkErrorPresent = true), facts.copy(permissionPromptPresent = true))) {
            val session = Session(listOf(chat(), chat(), details(), details()))
            session.sourceFacts = rejected
            session.run(); assertTrue(session.taps.isEmpty()); session.assertNoMoney()
        }
    }

    @Test fun twoTopOcrCandidatesWithOneStableMenuStillRequireExactDetailsAndRestoration() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), chat(),
            panel(), panel(), result(), result()))
        session.sourceFacts = SilentPacketSourceFacts(2, 1, 0, false, 1)
        assertEquals("claimed", session.run())
        assertEquals(listOf("visual:info", "visual:card:one", "visual:open"), session.taps)
        assertEquals(1, session.backs)
    }

    @Test fun defaultFlowDoesNotAllowFallbackAndExplicitFlowCannotCardBeforeRestore() {
        val candidate = PacketCandidate("fallback-policy", expected, true)
        val normal = PacketFlow(candidate, 1000)
        assertFalse(normal.beginDetailsFallback(rawHeader))
        assertEquals(PacketAction.Stop, normal.step(chat().match.page, 1001))
        val fallback = PacketFlow(candidate, 1000, allowDetailsFallback = true)
        assertTrue(fallback.beginDetailsFallback(rawHeader))
        assertFalse(fallback.restoreVerifiedChat())
        assertEquals(PacketAction.Click("visual:info"), fallback.step(chat().match.page, 1001))
        assertEquals(PacketAction.Back, fallback.step(details().match.page, 1002))
        assertEquals(PacketAction.Wait, fallback.step(chat().match.page, 1003))
        assertTrue(fallback.restoreVerifiedChat())
        assertFalse(fallback.restoreVerifiedChat())
        assertEquals(PacketAction.Click("visual:card:one"), fallback.step(chat().match.page, 1004))
    }

    @Test fun fallbackDoesNotRestartOriginalDeadlineOnDetailsOrRestoration() {
        val flow = PacketFlow(PacketCandidate("deadline", expected, true), 1000, allowDetailsFallback = true)
        assertTrue(flow.beginDetailsFallback(rawHeader))
        assertEquals(PacketAction.Click("visual:info"), flow.step(chat().match.page, 12000))
        assertEquals(PacketAction.Back, flow.step(details().match.page, 12500))
        assertTrue(flow.restoreVerifiedChat())
        assertEquals(PacketAction.Stop, flow.step(chat().match.page, 13000))
        assertFalse(flow.canClick(13000))
    }

    @Test fun fallbackPublishesFixedEligibilityAndExactDetailsFactsWithoutNames() = runBlocking {
        val session = Session(listOf(chat(), chat(), details(), details(), chat(), chat(), chat(),
            panel(), panel(), result(), result()))
        assertEquals("claimed", session.run())
        assertEquals(1, session.traces.count { it == SilentPacketEngineEvent.FALLBACK_STARTED })
        assertTrue(session.pages.first().facts!!.uniqueChatToolbar)
        val facts = session.pages.first { it.facts!!.verifiedGroupDetails }.facts!!
        assertTrue(facts.detailsNamePresent)
        assertEquals(true, facts.detailsNameExact)
        assertEquals(2, facts.detailsMemberCount)
        assertFalse(session.pages.joinToString { it.renderFacts() }.contains(expected))
        assertTrue(session.pages.first { it.facts!!.verifiedGroupDetails }.renderFacts().contains("detailsNameExact=true"))
    }

    @Test fun rejectedDetailsReportSpecificFixedReasonBeforeAnyMoney() = runBlocking {
        for ((view, reason) in listOf(details(null) to SilentPacketEngineEvent.DETAILS_NAME_MISSING,
            details("other-private-name") to SilentPacketEngineEvent.DETAILS_NAME_MISMATCH,
            details(count = 1) to SilentPacketEngineEvent.DETAILS_MEMBER_INVALID)) {
            val session = Session(listOf(chat(), chat(), view, view))
            assertEquals("unknown", session.run())
            assertTrue(session.traces.contains(reason))
            assertEquals(listOf("visual:info"), session.taps)
            session.assertNoMoney()
        }
    }

    @Test fun restorationReportsWhichAnchorChangedWithoutLeakingHeaderOrSignature() = runBlocking {
        for ((view, reason) in listOf(chat("other-private-name") to SilentPacketEngineEvent.ANCHOR_HEADER_MISMATCH,
            chat(signature = "private-signature") to SilentPacketEngineEvent.ANCHOR_SIGNATURE_MISMATCH,
            chat(box = IntRect(87, 8, 97, 14)) to SilentPacketEngineEvent.ANCHOR_MENU_MISMATCH)) {
            val session = Session(listOf(chat(), chat(), details(), details(), chat(), view))
            assertEquals("unknown", session.run())
            assertTrue(session.traces.contains(reason))
            session.assertNoMoney()
        }
    }
}
