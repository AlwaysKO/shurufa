package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class WechatTitlePixelsTest {
    private val bounds = OcrTextLine("联系人", 100, 45, 200, 85)
    private fun image(): Bitmap = Bitmap.createBitmap(320, 120, Bitmap.Config.ARGB_8888).apply {
        eraseColor(Color.WHITE)
        for (y in 50 until 80) for (x in 105 until 190) {
            if (x % 11 < 4) setPixel(x, y, Color.BLACK)
        }
    }
    @Test fun titleSignatureIgnoresStatusBarAndOCRSpelling() {
        val image = image()
        val first = wechatTitlePixelSignature(image, bounds)
        for (x in 5..35) image.setPixel(x, 8, Color.BLACK)
        assertNotNull(first)
        assertEquals(first, wechatTitlePixelSignature(image, bounds.copy(text = "联糸人")))
    }
    @Test fun aDifferentGlyphCannotBeMergedByCoarsePerceptualHash() {
        val image = image()
        val first = wechatTitlePixelSignature(image, bounds)
        image.setPixel(109, 60, Color.BLACK)
        assertNotEquals(first, wechatTitlePixelSignature(image, bounds))
    }
    @Test fun blankOrOutOfBoundsTitleDoesNotProvideIdentityEvidence() {
        val image = image()
        assertNull(wechatTitlePixelSignature(image, bounds.copy(left = -100, right = -1)))
        image.eraseColor(Color.WHITE)
        assertNull(wechatTitlePixelSignature(image, bounds))
    }
    @Test fun minorBackgroundCompressionAndWhitespaceBoundsDoNotChangeSignature() {
        val image = image()
        val first = wechatTitlePixelSignature(image, bounds)
        image.setPixel(102, 47, Color.rgb(247, 247, 247))
        assertEquals(first, wechatTitlePixelSignature(image, bounds.copy(left = 98, top = 43, right = 202, bottom = 87)))
    }
}
