package com.yuyan.imemodule.data.collect

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
class GameImagePreparationTest {
    @Before @After fun reset() {
        resetGameWorkRuntimeForTest()
        resetImageInputForTest()
    }
    @Test fun gameStopsImagePreparationWithoutBlockingNecessaryInputPersistence() {
        GameWorkRuntime.setGaming(true)
        assertTrue(ImageUploadRuntime.isInputIdle())
        val permit = ImageUploadRuntime.beginPreparation()
        try { assertNull("游戏中不得开始截图或编码", permit) } finally { permit?.close() }
        GameWorkRuntime.setGaming(false)
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        val resumed = ImageUploadRuntime.beginPreparation()
        try { assertNotNull(resumed) } finally { resumed?.close() }
    }
    @Test fun gamePauseIsRetryableWorkFailureRatherThanSystemJobCancellation() {
        GameWorkRuntime.setGaming(true)
        val error = runCatching { GameWorkRuntime.requireBackgroundAllowed() }.exceptionOrNull()
        assertTrue(error is java.io.IOException)
        assertFalse(error is kotlinx.coroutines.CancellationException)
    }
}
