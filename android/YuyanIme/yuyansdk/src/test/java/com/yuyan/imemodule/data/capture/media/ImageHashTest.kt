package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ImageHashTest {
    @Test
    fun identicalCropHasSameSha256() {
        val first = gradientBitmap(32, 32)
        val second = first.copy(Bitmap.Config.ARGB_8888, false)

        assertEquals(imageContentSha256(first), imageContentSha256(second))

        first.recycle()
        second.recycle()
    }

    @Test
    fun scaledOrSlightlyChangedImageHasNearbyDifferenceHash() {
        val original = gradientBitmap(32, 32)
        val scaled = Bitmap.createScaledBitmap(original, 64, 64, true)
        scaled.setPixel(2, 2, Color.WHITE)

        val distance = hammingDistance(differenceHash(original), differenceHash(scaled))

        assertTrue("distance=$distance", distance <= 8)
        original.recycle()
        scaled.recycle()
    }

    private fun gradientBitmap(width: Int, height: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val value = (x * 255 / (width - 1)).coerceIn(0, 255)
                    setPixel(x, y, Color.rgb(value, value, value))
                }
            }
        }
}
