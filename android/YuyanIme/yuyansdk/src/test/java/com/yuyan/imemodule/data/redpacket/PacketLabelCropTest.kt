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
class PacketLabelCropTest {
    private fun image() = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.rgb(255, 155, 45))
        setPixel(3, 3, Color.WHITE)
        setPixel(4, 3, Color.rgb(220, 222, 224))
        setPixel(3, 4, Color.BLACK)
    }

    @Test fun turnsWhiteLettersBlackAndOtherPixelsWhiteAtDoubleSize() {
        val source = image()
        val crop = packetLabelCrop(source, IntRect(2, 2, 6, 5))!!
        assertEquals(8, crop.width)
        assertEquals(6, crop.height)
        assertEquals(Color.BLACK, crop.getPixel(2, 2))
        assertEquals(Color.BLACK, crop.getPixel(3, 3))
        assertEquals(Color.BLACK, crop.getPixel(4, 2))
        assertEquals(Color.WHITE, crop.getPixel(0, 0))
        assertEquals(Color.WHITE, crop.getPixel(2, 4))
    }

    @Test fun preservesTranslucentWhiteLabelOnOrangeCard() {
        val source = Bitmap.createBitmap(2, 1, Bitmap.Config.ARGB_8888)
        source.setPixel(0, 0, Color.rgb(254, 235, 216))
        source.setPixel(1, 0, Color.rgb(255, 155, 45))
        val crop = packetLabelCrop(source, IntRect(0, 0, 2, 1))!!
        assertEquals(Color.BLACK, crop.getPixel(0, 0))
        assertEquals(Color.WHITE, crop.getPixel(2, 0))
    }

    @Test fun leavesSourcePixelsAndLifetimeUnchanged() {
        val source = image()
        val before = IntArray(48).also { source.getPixels(it, 0, 8, 0, 0, 8, 6) }
        val crop = packetLabelCrop(source, IntRect(2, 2, 6, 5))!!
        crop.recycle()
        assertFalse(source.isRecycled)
        val after = IntArray(48).also { source.getPixels(it, 0, 8, 0, 0, 8, 6) }
        assertArrayEquals(before, after)
    }

    @Test fun rejectsOutOfBoundsEmptyAndReversedRegions() {
        val source = image()
        for (box in listOf(IntRect(-1, 0, 3, 3), IntRect(0, -1, 3, 3),
            IntRect(0, 0, 9, 3), IntRect(0, 0, 3, 7), IntRect(2, 2, 2, 4),
            IntRect(2, 2, 4, 2), IntRect(5, 2, 3, 4))) {
            assertNull(packetLabelCrop(source, box))
        }
    }
}
