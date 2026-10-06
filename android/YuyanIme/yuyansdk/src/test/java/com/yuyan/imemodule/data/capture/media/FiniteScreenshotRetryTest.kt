package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.resetImageInputForTest
import com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class FiniteScreenshotRetryTest {
    @Before @After fun reset() { resetImageInputForTest(); resetGameWorkRuntimeForTest() }
    private val bounds = IntRect(0, 0, 100, 100)
    @Test fun internalAndShortIntervalErrorsRetryTwiceOnly() = runBlocking {
        for (code in listOf(1, 3)) {
            var calls = 0
            val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
                calls++; WindowScreenshotResult.Failed(code)
            })
            assertTrue(media.capture(1, bounds, listOf(MediaCaptureRequest(0, bounds))).isEmpty())
            assertEquals(3, calls)
        }
    }
    @Test fun permissionSecureAndWindowErrorsNeverRetry() = runBlocking {
        for (code in listOf(2, 4, 5, 6, -1001, -1002)) {
            var calls = 0
            val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
                calls++; WindowScreenshotResult.Failed(code)
            })
            media.capture(1, bounds, listOf(MediaCaptureRequest(0, bounds)))
            assertEquals(1, calls)
        }
    }
    @Test fun generationChangeStopsRetryBeforeSecondPhysicalRequest() = runBlocking {
        var generation = 1L; var calls = 0
        val media = WindowMediaCapturer(RuntimeEnvironment.getApplication(), ScreenshotSource { _, _ ->
            calls++; generation++; WindowScreenshotResult.Failed(1)
        }, captureGeneration = { generation })
        media.capture(1, bounds, listOf(MediaCaptureRequest(0, bounds)))
        assertEquals(1, calls)
    }
}
