package com.yuyan.imemodule.data.redpacket

import android.graphics.Bitmap
import android.graphics.Color
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PacketVisualPixelsTest {
    private fun bitmap(width: Int = 1000, height: Int = 2200): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(238, 238, 238)) }

    private fun rect(bitmap: Bitmap, left: Double, top: Double, right: Double, bottom: Double, color: Int) {
        for (y in (bitmap.height * top).toInt() until (bitmap.height * bottom).toInt())
            for (x in (bitmap.width * left).toInt() until (bitmap.width * right).toInt()) bitmap.setPixel(x, y, color)
    }

    private fun dots(bitmap: Bitmap, y: Double = .06, count: Int = 3, radius: Double = .004) {
        for (index in 0 until count) {
            val cx = (bitmap.width * (.91 + index * .017)).toInt()
            val cy = (bitmap.height * y).toInt()
            val r = (bitmap.width * radius).toInt().coerceAtLeast(2)
            for (dy in -r..r) for (dx in -r..r) if (dx * dx + dy * dy <= r * r)
                bitmap.setPixel(cx + dx, cy + dy, Color.BLACK)
        }
    }

    private fun title(bitmap: Bitmap, y: Double = .06) = PacketVisualLine("测试群(12)",
        IntRect((bitmap.width * .32).toInt(), (bitmap.height * (y - .012)).toInt(),
            (bitmap.width * .68).toInt(), (bitmap.height * (y + .012)).toInt()))

    @Test fun returnsOriginalDimensionsAndOcrLines() {
        val image = bitmap(720, 1600)
        val lines = listOf(title(image))
        val frame = packetVisualPixels(image, lines)
        assertEquals(720, frame.width)
        assertEquals(1600, frame.height)
        assertEquals(lines, frame.lines)
        assertTrue(frame.orangeRegions.isEmpty())
        assertTrue(frame.redRegions.isEmpty())
    }

    @Test fun detectsPartialOrangeCoverAndSeparateRedPanelAcrossResolutions() {
        for (width in listOf(600, 1000, 1500)) {
            val image = bitmap(width, (width * 2.2).toInt())
            rect(image, .15, .30, .48, .40, Color.rgb(255, 155, 45))
            rect(image, .48, .30, .76, .40, Color.rgb(100, 120, 95))
            rect(image, .12, .48, .88, .87, Color.rgb(220, 65, 48))
            val frame = packetVisualPixels(image, listOf(title(image)))
            assertEquals("width=$width", 1, frame.orangeRegions.size)
            assertEquals("width=$width", 1, frame.redRegions.size)
            val orange = frame.orangeRegions.single()
            assertTrue(orange.left in (width * .145).toInt()..(width * .155).toInt())
            assertTrue(orange.right in (width * .475).toInt()..(width * .485).toInt())
            image.recycle()
        }
    }

    @Test fun recognizesWideShallowRedHeaderOfResultPage() {
        val image = bitmap()
        rect(image, 0.0, 0.0, 1.0, .13, Color.rgb(245, 83, 64))
        assertEquals(1, packetVisualPixels(image, emptyList()).redRegions.size)
    }

    @Test fun coralReceivePanelIsRedRatherThanDiscardedAsOversizedOrangeCard() {
        val image = bitmap()
        rect(image, .15, .23, .85, .76, Color.rgb(249, 92, 73))
        val frame = packetVisualPixels(image, emptyList())
        assertEquals(1, frame.redRegions.size)
        assertTrue(frame.orangeRegions.isEmpty())
    }

    @Test fun ignoresSmallColoredIconsAndSparseColoredNoise() {
        val image = bitmap()
        rect(image, .03, .30, .10, .34, Color.rgb(255, 155, 45))
        rect(image, .18, .32, .24, .36, Color.rgb(220, 65, 48))
        for (x in 100 until 800 step 12) for (y in 900 until 1100 step 12)
            image.setPixel(x, y, Color.rgb(255, 155, 45))
        val frame = packetVisualPixels(image, listOf(title(image)))
        assertTrue(frame.orangeRegions.isEmpty())
        assertTrue(frame.redRegions.isEmpty())
    }

    @Test fun findsExactlyThreeToolbarDotsAlignedWithTitleAcrossResolutions() {
        for (width in listOf(600, 1000, 1500)) {
            val image = bitmap(width, (width * 2.2).toInt())
            dots(image)
            val frame = packetVisualPixels(image, listOf(title(image)))
            assertEquals("width=$width", 1, frame.menuDots.size)
            val menu = frame.menuDots.single()
            assertTrue(menu.left > width * .88)
            assertTrue(menu.right < width * .98)
            image.recycle()
        }
    }

    @Test fun rejectsDotsWithoutTitleOrOutsideToolbar() {
        val image = bitmap()
        dots(image)
        assertTrue(packetVisualPixels(image, emptyList()).menuDots.isEmpty())
        assertTrue(packetVisualPixels(image, listOf(title(image, .12))).menuDots.isEmpty())
        val body = bitmap()
        dots(body, .50)
        assertTrue(packetVisualPixels(body, listOf(title(body, .50))).menuDots.isEmpty())
    }

    @Test fun rejectsTwoFourOrOversizedDots() {
        for ((count, radius) in listOf(2 to .004, 4 to .004, 3 to .013)) {
            val image = bitmap()
            dots(image, count = count, radius = radius)
            assertTrue(packetVisualPixels(image, listOf(title(image))).menuDots.isEmpty())
        }
    }
}
