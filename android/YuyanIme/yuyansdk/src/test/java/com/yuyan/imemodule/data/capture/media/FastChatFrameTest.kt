package com.yuyan.imemodule.data.capture.media

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.resetImageInputForTest
import com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FastChatFrameTest {
    @Before @After fun reset() { resetImageInputForTest(); resetGameWorkRuntimeForTest() }
    private val bounds = IntRect(0, 0, 100, 100)
    private val requests = listOf(MediaCaptureRequest(0, bounds))

    @Test fun acceptedFrameWaitsForIdleAndSurvivesNavigationWithoutReadingNewPage() = runBlocking {
        var scope = true
        var generation = 1L
        var captures = 0
        var encodes = 0
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            captures++; WindowScreenshotResult.Success(frame, 0, 0)
        }, processingDispatcher = Dispatchers.Unconfined, captureAllowed = { scope }, captureGeneration = { generation },
            encodeAsset = { _, _ -> encodes++; byteArrayOf(1, 2, 3) })
        ImageUploadRuntime.noteKeyActivity()
        val attempt = ChatCaptureAttempt(ImageUploadRuntime.fastScreenshotPermit(), { scope }, { true })
        val work = async(Dispatchers.Unconfined + attempt) { media.capture(1, bounds, requests) }
        try {
            assertTrue("发送后应先取到原页帧", attempt.isAccepted)
            val takenAt = attempt.capturedAtMillis
            assertNotNull(takenAt)
            assertEquals(1, captures)
            assertEquals("残余输入冷却期不得编码", 0, encodes)
            scope = false; generation++
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            assertEquals(1, withTimeout(2_000) { work.await() }.size)
            assertEquals(1, encodes)
            assertEquals("处理等待不能改写实际取帧时间", takenAt, attempt.capturedAtMillis)
            assertTrue("已使用的许可不能复用来确认另一帧", withContext(attempt) { media.capture(1, bounds, requests) }.isEmpty())
            assertEquals(1, captures)
            assertTrue(frame.isRecycled)
        } finally { work.cancelAndJoin() }
    }

    @Test fun changedScopeBeforeCallbackCannotAcceptFrame() = runBlocking {
        var scope = true
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val attempt = ChatCaptureAttempt({ true }, { scope }, { true })
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            scope = false; WindowScreenshotResult.Success(frame, 0, 0)
        }, encodeAsset = { _, _ -> fail("错页不得编码"); byteArrayOf() })
        assertTrue(withContext(attempt) { media.capture(1, bounds, requests) }.isEmpty())
        assertFalse(attempt.isAccepted)
        assertTrue(frame.isRecycled)
    }

    @Test fun revokedAuthorizationDropsAcceptedFrameAndReleasesSingleSlot() = runBlocking {
        var allowed = true
        var captures = 0
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            captures++; WindowScreenshotResult.Success(frame, 0, 0)
        }, encodeAsset = { _, _ -> fail("撤权后不得编码"); byteArrayOf() })
        ImageUploadRuntime.noteKeyActivity()
        val attempt = ChatCaptureAttempt(ImageUploadRuntime.fastScreenshotPermit(), { true }, { allowed })
        val work = async(Dispatchers.Unconfined + attempt) { media.capture(1, bounds, requests) }
        try {
            assertTrue(attempt.isAccepted)
            val second = ChatCaptureAttempt(ImageUploadRuntime.fastScreenshotPermit(), { true }, { true })
            assertTrue(withTimeout(500) { withContext(second) { media.capture(1, bounds, requests) } }.isEmpty())
            assertEquals("共享槽忙不能累积物理帧", 1, captures)
            allowed = false
            assertTrue(withTimeout(2_000) { work.await() }.isEmpty())
            assertTrue(frame.isRecycled)
        } finally { work.cancelAndJoin() }
    }

    @Test fun gameOrNewInputPreventsQuickFrame() = runBlocking {
        var captures = 0
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            captures++; WindowScreenshotResult.Failed(2)
        })
        val beforeInput = ChatCaptureAttempt(ImageUploadRuntime.fastScreenshotPermit(), { true }, { true })
        ImageUploadRuntime.noteKeyActivity()
        assertTrue(withContext(beforeInput) { media.capture(1, bounds, requests) }.isEmpty())
        val beforeGame = ChatCaptureAttempt(ImageUploadRuntime.fastScreenshotPermit(), { true }, { true })
        GameWorkRuntime.setGaming(true)
        assertTrue(withContext(beforeGame) { media.capture(1, bounds, requests) }.isEmpty())
        assertEquals(0, captures)
    }

    @Test fun sendAttemptLeavesTransientRetriesToItsBoundedServiceLoop() = runBlocking {
        var captures = 0
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            captures++; WindowScreenshotResult.Failed(3)
        })
        val attempt = ChatCaptureAttempt({ true }, { true }, { true }, allowsSettledSendFrame = true)
        assertTrue(withContext(attempt) { media.capture(1, bounds, requests) }.isEmpty())
        assertEquals("单次发送快帧不应叠加媒体层400ms重试", 1, captures)
        assertFalse(attempt.isAccepted)
    }

    @Test fun cancelledIdleWaitRecyclesFrameAndAllowsNextPhysicalRequest() = runBlocking {
        var captures = 0
        val frame = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            captures++
            if (captures == 1) WindowScreenshotResult.Success(frame, 0, 0) else WindowScreenshotResult.Failed(2)
        })
        ImageUploadRuntime.noteKeyActivity()
        val attempt = ChatCaptureAttempt(ImageUploadRuntime.fastScreenshotPermit(), { true }, { true })
        val work = async(Dispatchers.Unconfined + attempt) { media.capture(1, bounds, requests) }
        try { assertTrue(attempt.isAccepted) } finally { work.cancelAndJoin() }
        assertTrue(frame.isRecycled)
        val next = ChatCaptureAttempt(ImageUploadRuntime.fastScreenshotPermit(), { true }, { true })
        assertTrue(withTimeout(500) { withContext(next) { media.capture(1, bounds, requests) } }.isEmpty())
        assertEquals(2, captures)
    }
}
