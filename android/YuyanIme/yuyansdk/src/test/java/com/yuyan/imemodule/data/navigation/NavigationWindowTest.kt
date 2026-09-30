package com.yuyan.imemodule.data.navigation

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.media.WindowScreenshotResult
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NavigationWindowTest {
    @Test fun wholeScreenCaptureOnlyKeepsMapWindowAndRejectsInvalidBounds() {
        val bitmap = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888)
        val image = WindowScreenshotResult.Success(bitmap, 0, 0)
        val cropped = cropNavigationWindow(image, IntRect(10, 20, 90, 120))!!
        assertEquals(80, cropped.width); assertEquals(100, cropped.height)
        assertNull(cropNavigationWindow(image, IntRect(-1, 0, 90, 120)))
        cropped.recycle(); bitmap.recycle()
    }
    @Test fun windowScopedCaptureUsesItsWindowOrigin() {
        val bitmap = Bitmap.createBitmap(80, 100, Bitmap.Config.ARGB_8888)
        val cropped = cropNavigationWindow(WindowScreenshotResult.Success(bitmap, 10, 20), IntRect(10, 20, 90, 120))!!
        assertEquals(80, cropped.width); assertEquals(100, cropped.height)
        if (cropped !== bitmap) cropped.recycle()
        bitmap.recycle()
    }
}
