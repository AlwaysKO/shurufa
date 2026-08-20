package com.yuyan.imemodule.data.capture.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MediaCropperTest {
    @Test
    fun multipleMediaRequestsShareOneWindowScreenshot() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = solidBitmap(100, 100, Color.BLUE)
        var screenshots = 0
        val capturer = WindowMediaCapturer(
            context = context,
            screenshotSource = ScreenshotSource { _, _ ->
                screenshots += 1
                WindowScreenshotResult.Success(bitmap.copy(Bitmap.Config.ARGB_8888, false), 0, 0)
            },
        )
        val bounds = IntRect(10, 10, 60, 60)

        val captured = capturer.capture(
            windowId = 1,
            windowBounds = IntRect(0, 0, 100, 100),
            requests = listOf(
                MediaCaptureRequest(0, bounds),
                MediaCaptureRequest(1, bounds),
            ),
        )

        assertEquals(1, screenshots)
        assertEquals(2, captured.size)
        assertEquals(captured.getValue(0).sha256, captured.getValue(1).sha256)
        assertTrue(java.io.File(captured.getValue(0).localPath).isFile)
        captured.values.map { it.localPath }.distinct().forEach { java.io.File(it).delete() }
        bitmap.recycle()
    }

    @Test
    fun cropRectangleIsClampedSafelyToWindow() {
        val bitmap = solidBitmap(100, 100, Color.RED)
        val cropped = MediaCropper().crop(
            bitmap = bitmap,
            requested = IntRect(-10, 10, 40, 60),
            windowBounds = IntRect(0, 0, 100, 100),
            screenshotOriginX = 0,
            screenshotOriginY = 0,
        )

        assertEquals(40, cropped?.width)
        assertEquals(50, cropped?.height)
        cropped?.recycle()
        bitmap.recycle()
    }

    @Test
    fun rejectsZeroSmallInputAreaAndFullyOutsideRectangles() {
        val bitmap = solidBitmap(100, 100, Color.GREEN)
        val cropper = MediaCropper(minimumSide = 16)
        val window = IntRect(0, 0, 100, 100)

        assertNull(cropper.crop(bitmap, IntRect(20, 20, 20, 30), window, 0, 0))
        assertNull(cropper.crop(bitmap, IntRect(20, 20, 30, 30), window, 0, 0))
        assertNull(cropper.crop(
            bitmap,
            IntRect(10, 60, 60, 90),
            window,
            0,
            0,
            inputAreaBounds = IntRect(0, 70, 100, 100),
        ))
        assertNull(cropper.crop(bitmap, IntRect(110, 10, 140, 40), window, 0, 0))
        assertFalse(bitmap.isRecycled)
        bitmap.recycle()
    }

    private fun solidBitmap(width: Int, height: Int, color: Int): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
}
