package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.collect.GameWorkPausedException
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest
import com.yuyan.imemodule.data.collect.resetImageInputForTest
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Before
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ScreenshotBackgroundPauseTest {
    @Before @After fun reset() {
        resetGameWorkRuntimeForTest()
        resetImageInputForTest()
    }

    @Test fun gamePauseCancelsScreenshotWithoutEscapingToUncaughtLaunchHandler() = runBlocking {
        val uncaught = mutableListOf<Throwable>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined +
            CoroutineExceptionHandler { _, error -> uncaught.add(error) })
        try {
            GameWorkRuntime.setGaming(true)
            var enteredPixels = false
            val job = scope.launch {
                requireScreenshotBackgroundWork()
                enteredPixels = true
            }
            var completion: Throwable? = null
            job.invokeOnCompletion { completion = it }
            job.join()
            assertFalse(enteredPixels)
            assertTrue("游戏暂停不得逃逸到进程异常处理器", uncaught.isEmpty())
            assertTrue(completion is CancellationException)
            assertTrue(completion?.cause is GameWorkPausedException)

            GameWorkRuntime.setGaming(false)
            ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
            scope.launch { requireScreenshotBackgroundWork(); enteredPixels = true }.join()
            assertTrue("退出游戏后可以重新处理截图", enteredPixels)
            assertTrue(uncaught.isEmpty())
        } finally { scope.cancel() }
    }
}
