package com.yuyan.imemodule.data.capture.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.*
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
    fun queuedOldConversationCannotCaptureTheNextConversationInTheSameWindow() = runBlocking {
        var generation = 1L
        val release = CompletableDeferred<Unit>()
        var screenshots = 0
        val capturer = WindowMediaCapturer(ApplicationProvider.getApplicationContext(), ScreenshotSource { _, _ ->
            screenshots++
            release.await()
            WindowScreenshotResult.Unsupported
        }, captureGeneration = { generation })
        suspend fun capture() = capturer.capture(1, IntRect(0, 0, 100, 100),
            listOf(MediaCaptureRequest(0, IntRect(0, 0, 50, 50))))
        val active = launch(start = CoroutineStart.UNDISPATCHED) { capture() }
        val stale = launch(start = CoroutineStart.UNDISPATCHED) { capture() }
        generation++
        val fresh = launch(start = CoroutineStart.UNDISPATCHED) { capture() }
        release.complete(Unit)
        joinAll(active, stale, fresh)
        assertEquals("旧排队请求取消，新会话首采不被取消", 2, screenshots)
    }

    @Test
    fun queuedCaptureRechecksConsentAfterAcquiringSharedLock() = runBlocking {
        var allowed = true
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val capturer = WindowMediaCapturer(ApplicationProvider.getApplicationContext(), ScreenshotSource { id, _ ->
            entered += id
            release.await()
            WindowScreenshotResult.Unsupported
        }, captureAllowed = { allowed })
        val jobs = (1..2).map { id -> launch(start = CoroutineStart.UNDISPATCHED) {
            capturer.capture(id, IntRect(0, 0, 100, 100), listOf(MediaCaptureRequest(0, IntRect(0, 0, 50, 50))))
        } }
        allowed = false
        release.complete(Unit)
        jobs.joinAll()
        assertEquals(listOf(1), entered)
    }

    @Test
    fun cancellationBeforeEncodingDispatchRecyclesCapturedBitmap() = runBlocking {
        val bitmap = solidBitmap(100, 100, Color.BLUE)
        val cancelBeforeExecution = object : CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) {
                context[Job]!!.cancel()
                block.run()
            }
        }
        val capturer = WindowMediaCapturer(
            ApplicationProvider.getApplicationContext(),
            ScreenshotSource { _, _ -> WindowScreenshotResult.Success(bitmap, 0, 0) },
            processingDispatcher = cancelBeforeExecution,
        )
        val task = launch {
            capturer.capture(1, IntRect(0, 0, 100, 100), listOf(MediaCaptureRequest(0, IntRect(0, 0, 50, 50))))
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue("取消发生在编码调度边界也必须释放原图", bitmap.isRecycled)
    }

    @Test
    fun allScreenshotEntrypointsShareOnePhysicalCaptureAtATime() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val capturer = WindowMediaCapturer(ApplicationProvider.getApplicationContext(), ScreenshotSource { id, _ ->
            entered += id
            release.await()
            WindowScreenshotResult.Unsupported
        })
        val jobs = (1..3).map { id -> launch(start = CoroutineStart.UNDISPATCHED) {
            capturer.capture(id, IntRect(0, 0, 100, 100), listOf(MediaCaptureRequest(0, IntRect(0, 0, 50, 50))))
        } }
        try {
            assertEquals(listOf(1), entered)
        } finally {
            release.complete(Unit)
            jobs.joinAll()
        }
        assertEquals(listOf(1, 2, 3), entered)
    }

    @Test
    fun cancellationDoesNotReleasePhysicalCaptureBeforeItsCallback() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val entered = mutableListOf<Int>()
        val capturer = WindowMediaCapturer(ApplicationProvider.getApplicationContext(), ScreenshotSource { id, _ ->
            entered += id
            release.await()
            WindowScreenshotResult.Unsupported
        })
        suspend fun capture(id: Int) = capturer.capture(id, IntRect(0, 0, 100, 100),
            listOf(MediaCaptureRequest(0, IntRect(0, 0, 50, 50))))
        val first = launch(start = CoroutineStart.UNDISPATCHED) { capture(1) }
        first.cancel()
        yield()
        val second = launch(start = CoroutineStart.UNDISPATCHED) { capture(2) }
        try {
            assertEquals(listOf(1), entered)
        } finally {
            release.complete(Unit)
            joinAll(first, second)
        }
        assertEquals(listOf(1, 2), entered)
    }

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
    fun conversationScreenshotUsesCompressedWebp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val bitmap = solidBitmap(1080, 1600, Color.BLUE)
        val capturer = WindowMediaCapturer(
            context = context,
            screenshotSource = ScreenshotSource { _, _ ->
                WindowScreenshotResult.Success(bitmap.copy(Bitmap.Config.ARGB_8888, false), 0, 0)
            },
        )

        val asset = capturer.capture(
            windowId = 1,
            windowBounds = IntRect(0, 0, 1080, 1600),
            requests = listOf(MediaCaptureRequest(0, IntRect(0, 0, 1080, 1500), lossyWebp = true)),
        ).getValue(0)

        assertEquals("image/webp", asset.mimeType)
        assertTrue(java.io.File(asset.localPath).length() < 5 * 1024 * 1024)
        java.io.File(asset.localPath).delete()
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
