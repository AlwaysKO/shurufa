package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class TitleOcrInputTest {
    @Test fun configuredTitleBandCopiesOnlyToolbarPixels() {
        val source=pattern(300,500)
        TitleOcrInput(24,44).use { input ->
            input.captureFrom(source)
            val header=requireNotNull(input.takeOrDecode("/missing/title-ocr"))
            try {
                assertEquals(44,header.height)
                for(y in 0 until 44) for(x in 0 until 300) assertEquals(source.getPixel(x,y+24),header.getPixel(x,y))
            } finally { header.recycle() }
        }
        assertFalse(source.isRecycled)
        source.recycle()
    }
    @Test fun titleBandOutsideTheImageDoesNotFallbackToUnrelatedPixels() {
        val source=pattern(40,20)
        TitleOcrInput(100,44).use { input -> input.captureFrom(source);assertNull(input.takeOrDecode("/missing/title-ocr")) }
        source.recycle()
    }

    @Test fun phoneTitleBandExcludesSystemBarAndMessageBody() {
        val band = wechatTitleBand(126,0,3.25f)
        assertEquals(126,band.top)
        assertEquals(143,band.height)
        assertTrue(221 < band.top+band.height)
        assertTrue(band.top+band.height <= 270)
    }
    @Test fun windowAlreadyBelowStatusBarDoesNotLoseItsTitle() {
        assertEquals(0,wechatTitleBand(126,126,3.25f).top)
        assertEquals(0,wechatTitleBand(126,400,3.25f).top)
        assertEquals(44,wechatTitleBand(24,24,1f).height)
    }

    @Test fun corruptAssetFailsClosed() {
        val file = File.createTempFile("title-ocr-invalid", ".webp")
        try {
            file.writeText("not an image")
            assertNull(decodeTitleHeader(file.path))
        } finally { file.delete() }
    }

    @Test fun legacyWebpHeaderMatchesFullDecode() {
        val source = pattern()
        val file = File.createTempFile("title-ocr-legacy", ".webp")
        try {
            file.writeBytes(encodeWebp(source))
            val full = requireNotNull(BitmapFactory.decodeFile(file.path))
            val header = requireNotNull(decodeTitleHeader(file.path))
            try {
                assertEquals(108, header.height)
                for (y in 0 until header.height) for (x in 0 until header.width) {
                    assertEquals(full.getPixel(x, y), header.getPixel(x, y))
                }
            } finally { full.recycle(); header.recycle() }
        } finally { file.delete(); source.recycle() }
    }

    @Test fun cancellationWaitsForNativeCompletionBeforeRecycling() = runBlocking {
        val bitmap = pattern(40, 20)
        val started = CompletableDeferred<Unit>()
        val completion = CompletableDeferred<Unit>()
        var consumed = false
        val job = launch {
            try {
                awaitTitleOcrCompletion {
                    started.complete(Unit)
                    completion.await()
                    assertFalse(bitmap.isRecycled)
                }
                consumed = true
            } finally { bitmap.recycle() }
        }
        started.await()
        job.cancel()
        yield()
        assertFalse(bitmap.isRecycled)
        completion.complete(Unit)
        job.join()
        assertTrue(bitmap.isRecycled)
        assertFalse("取消结果不得更新会话身份", consumed)
    }

    private fun pattern(width: Int = 600, height: Int = 900): Bitmap =
        Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            for (y in 0 until height) for (x in 0 until width) {
                setPixel(x, y, if ((x + y) % 3 == 0) Color.BLACK else Color.WHITE)
            }
        }

    @Test fun rawPixelsSurviveSourceRecycleAndAreConsumedOnlyOnce() {
        val source = pattern()
        val input = TitleOcrInput()
        input.captureFrom(source)
        source.recycle()
        val header = requireNotNull(input.takeOrDecode("/missing/title-ocr"))
        try {
            assertEquals(600, header.width)
            assertEquals(108, header.height)
            for (y in 0 until header.height) for (x in 0 until header.width) {
                assertEquals(if ((x + y) % 3 == 0) Color.BLACK else Color.WHITE, header.getPixel(x, y))
            }
            assertNull(input.takeOrDecode("/missing/title-ocr"))
            input.close()
            assertFalse("取出的 Bitmap 所有权属于调用方", header.isRecycled)
        } finally { header.recycle(); input.close() }
    }

    @Test fun closedInputCannotRetainMoreScreenshots() {
        val source = pattern(40, 20)
        val input = TitleOcrInput()
        input.captureFrom(source)
        input.close()
        input.close()
        input.captureFrom(source)
        assertNull(input.takeOrDecode("/missing/title-ocr"))
        assertFalse(source.isRecycled)
        source.recycle()
    }

    @Test fun tinyImagesClampWithoutThrowingOrSharingTheSource() {
        val source = pattern(40, 20)
        TitleOcrInput().use { input ->
            input.captureFrom(source)
            val header = requireNotNull(input.takeOrDecode("/missing/title-ocr"))
            assertEquals(20, header.height)
            assertNotSame(source, header)
            header.recycle()
            assertFalse(source.isRecycled)
        }
        source.recycle()
    }

    @Test fun legacyPngReturnsTitleHeaderWithUnchangedPixels() {
        val source = pattern()
        val file = File.createTempFile("title-ocr", ".png")
        try {
            file.writeBytes(encodeLossless(source))
            TitleOcrInput().use { input ->
                val header = requireNotNull(input.takeOrDecode(file.path))
                try {
                    assertEquals(108, header.height)
                    assertTrue(header.allocationByteCount < source.allocationByteCount / 4)
                    for (y in 0 until header.height) for (x in 0 until header.width) {
                        assertEquals(source.getPixel(x, y), header.getPixel(x, y))
                    }
                } finally { header.recycle() }
            }
        } finally { file.delete(); source.recycle() }
    }

    @Test fun captureHandsOffRawHeaderButKeepsUploadedScreenshotWebp() = runBlocking {
        val source = pattern()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val capturer = WindowMediaCapturer(context, ScreenshotSource { _, _ ->
            WindowScreenshotResult.Success(source, 0, 0)
        })
        TitleOcrInput().use { input ->
            val asset = capturer.capture(1, IntRect(0, 0, 600, 900), listOf(
                MediaCaptureRequest(0, IntRect(0, 0, 600, 900), lossyWebp = true, titleOcrInput = input),
            )).getValue(0)
            assertTrue(source.isRecycled)
            assertEquals("image/webp", asset.mimeType)
            val header = requireNotNull(input.takeOrDecode(asset.localPath))
            try {
                for (y in 0 until header.height) for (x in 0 until header.width) {
                    assertEquals(if ((x + y) % 3 == 0) Color.BLACK else Color.WHITE, header.getPixel(x, y))
                }
            } finally { header.recycle(); File(asset.localPath).delete() }
        }
    }
}
