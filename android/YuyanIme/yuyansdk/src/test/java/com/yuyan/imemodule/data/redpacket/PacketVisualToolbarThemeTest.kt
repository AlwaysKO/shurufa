package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Synthetic pixels only: verifies toolbar compatibility, never relaxes group verification. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PacketVisualToolbarThemeTest {
    private fun frame(background: Int, foreground: Int, count: Int = 3): PacketVisualFrame {
        val bitmap = Bitmap.createBitmap(480, 800, Bitmap.Config.ARGB_8888)
        try {
            bitmap.eraseColor(background)
            repeat(count) { index ->
                val cx = (480 * (.91 + index * .017)).toInt()
                for (dy in -2..2) for (dx in -2..2) if (dx * dx + dy * dy <= 4)
                    bitmap.setPixel(cx + dx, 52 + dy, foreground)
            }
            return packetVisualPixels(bitmap, listOf(PacketVisualLine("合成测试群", IntRect(160, 40, 320, 64))))
        } finally { bitmap.recycle() }
    }

    @Test fun lightToolbarWithDarkDotsRetainsExistingMenuRecognition() {
        val parsed = parsePacketVisualFrame(frame(Color.rgb(238, 238, 238), Color.BLACK))
        assertEquals("合成测试群", parsed.page.chatName)
        assertEquals("visual:info", parsed.page.chatInfoButton)
    }

    @Test fun darkToolbarWithLightDotsRecognizesSameMenuGeometry() {
        val pixels = frame(Color.rgb(30, 30, 30), Color.rgb(230, 230, 230))
        assertEquals(1, pixels.menuDots.size)
        val parsed = parsePacketVisualFrame(pixels)
        assertEquals("合成测试群", parsed.page.chatName)
        assertEquals("visual:info", parsed.page.chatInfoButton)
        assertFalse("menu recognition must not assert verified group details", parsed.page.verifiedGroupDetails)
        assertFalse(parsed.page.groupChat)
    }

    @Test fun insufficientContrastDoesNotInventToolbarTargets() {
        for ((background, foreground) in listOf(
            Color.rgb(238, 238, 238) to Color.rgb(230, 230, 230),
            Color.rgb(30, 30, 30) to Color.rgb(45, 45, 45),
        )) assertTrue(frame(background, foreground).menuDots.isEmpty())
    }

    @Test fun darkThemeStillRejectsTwoOrFourDotsAndSaturatedIcons() {
        for (count in listOf(2, 4))
            assertTrue(frame(Color.rgb(30, 30, 30), Color.rgb(230, 230, 230), count).menuDots.isEmpty())
        assertTrue(frame(Color.rgb(30, 30, 30), Color.rgb(230, 50, 50)).menuDots.isEmpty())
    }

    @Test fun findingMenuDoesNotPermitCardBeforeIndependentGroupDetailsVerification() {
        val parsed = parsePacketVisualFrame(frame(Color.rgb(238, 238, 238), Color.BLACK))
        val flow = PacketFlow(PacketCandidate("a".repeat(64), "合成测试群", true), 0)
        assertEquals(PacketAction.Click("visual:info"), flow.step(parsed.page, 1))
        assertEquals(PacketAction.Wait, flow.step(parsed.page.copy(cards = listOf("visual:card:test")), 2))
    }
}
