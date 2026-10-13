package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SilentPacketPageFactsTest {
    private fun frame() = SilentPacketFrame(Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888), 1, 1000, 7)
    private fun snapshot(frame: SilentPacketFrame, page: PacketPage, targets: Map<String, IntRect> = emptyMap()) =
        PacketVisualSnapshot(PacketVisualMatch(page, targets), frame.displayId, IntRect(0, 0, 100, 200), 0, 0, frame.capturedAt)

    @Test fun validReadReportsOnlyFixedPageEvidenceWithoutNamesOrTargetIds() = runBlocking {
        val frame = frame()
        val secretGroup = "不应输出的群名"
        val secretInfo = "不应输出的控件ID"
        val page = PacketPage(chatName = secretGroup, groupChat = true, cards = listOf("private-card-1", "private-card-2"),
            packetPanel = true, chatInfoButton = secretInfo, verifiedGroupDetails = true)
        val diagnostic = inspectSilentPacketFrame(frame, { true }, {
            snapshot(it, page, mapOf(secretInfo to IntRect(1, 1, 2, 2)))
        }, { 1100 })
        assertEquals(SilentPacketPageFacts(true, true, true, true, true, 2), diagnostic.facts)
        assertTrue(diagnostic.renderFacts().contains("titlePresent=true"))
        assertTrue(diagnostic.renderFacts().contains("availableCardCount=2"))
        for (secret in listOf(secretGroup, secretInfo, "private-card-1", "private-card-2")) {
            assertFalse(diagnostic.toString().contains(secret))
            assertFalse(diagnostic.renderFacts().contains(secret))
        }
        assertTrue(frame.bitmap.isRecycled)
    }
    @Test fun blankTitleAndUnmappedInfoDoNotBecomeUsableTargets() = runBlocking {
        val frame = frame()
        val diagnostic = inspectSilentPacketFrame(frame, { true }, {
            snapshot(it, PacketPage(chatName = "  ", chatInfoButton = "unmapped"))
        }, { 1100 })
        assertEquals(SilentPacketPageFacts(false, false, false, false, false, 0), diagnostic.facts)
    }
    @Test fun verifiedGroupDetailsAreVisibleEvenWithoutChatTitle() = runBlocking {
        val frame = frame()
        val diagnostic = inspectSilentPacketFrame(frame, { true }, {
            snapshot(it, PacketPage(verifiedGroupDetails = true))
        }, { 1100 })
        assertEquals(SilentPacketPageFacts(false, false, false, true, false, 0), diagnostic.facts)
    }
    @Test fun staleProtectedAndRejectedResultsNeverPublishPageEvidence() = runBlocking {
        for (kind in 0..2) {
            val frame = frame()
            var now = 1100L
            var allowed = true
            val diagnostic = inspectSilentPacketFrame(frame, { allowed }, {
                if (kind == 0) now = 2500
                if (kind == 1) allowed = false
                if (kind == 2) null else snapshot(it, PacketPage(chatName = "private"))
                    .copy(sourceFacts = SilentPacketSourceFacts(1, 1, 1, true, 1))
            }, { now })
            assertNotEquals(SilentPacketReadResult.VALID, diagnostic.result)
            assertNull(diagnostic.facts)
            assertNull(diagnostic.sourceFacts)
            assertEquals("页面证据：未验证", diagnostic.renderFacts())
        }
    }
    @Test fun validReadCarriesOnlyPrimitiveSourceEvidenceAndRendersFixedKeys() = runBlocking {
        val frame = frame()
        val source = SilentPacketSourceFacts(2, 1, 0, true, 3)
        val diagnostic = inspectSilentPacketFrame(frame, { true }, {
            snapshot(it, PacketPage()).copy(sourceFacts = source)
        }, { 1100 })
        assertEquals(source, diagnostic.sourceFacts)
        assertTrue(diagnostic.renderFacts().contains("titleCandidateCount=2"))
        assertTrue(diagnostic.renderFacts().contains("menuDotCount=1"))
        assertTrue(diagnostic.renderFacts().contains("groupSuffixCount=0"))
        assertTrue(diagnostic.renderFacts().contains("bottomWechatTabsPresent=true"))
        assertTrue(diagnostic.renderFacts().contains("orangeRegionCount=3"))
    }
    @Test fun pageLabelFlagsRenderAsFixedBooleansWithoutOriginalText() {
        val source = SilentPacketSourceFacts(0, 0, 0, false, 0,
            loginPromptPresent = true, loadingPromptPresent = true, networkErrorPresent = true,
            permissionPromptPresent = true, chatComposerPresent = true)
        val diagnostic = SilentPacketReadDiagnostic(SilentPacketReadResult.VALID, 0, 0,
            SilentPacketPageFacts(false, false, false, false, false, 0), source)
        for (key in listOf("loginPromptPresent", "loadingPromptPresent", "networkErrorPresent",
            "permissionPromptPresent", "chatComposerPresent"))
            assertTrue(diagnostic.renderFacts().contains("$key=true"))
    }
    @Test fun originalThreeArgumentDiagnosticRemainsCompatibleAndFactsAreFixedScalarOnly() {
        val diagnostic = SilentPacketReadDiagnostic(SilentPacketReadResult.READ_FAILED, 0, 0)
        assertNull(diagnostic.facts)
        assertEquals("页面证据：未验证", diagnostic.renderFacts())
        val fields = SilentPacketPageFacts::class.java.declaredFields
        assertTrue(fields.all { field -> field.type.isPrimitive ||
            field.type == java.lang.Boolean::class.java || field.type == java.lang.Integer::class.java })
        assertFalse(fields.any { field -> listOf("text", "image", "intent").any {
            field.name.contains(it, ignoreCase = true)
        } })
    }
}
