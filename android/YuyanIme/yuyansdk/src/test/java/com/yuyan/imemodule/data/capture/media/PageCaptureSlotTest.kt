package com.yuyan.imemodule.data.capture.media

import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PageCaptureSlotTest {
    private val bounds = IntRect(0, 0, 100, 200)
    @Before fun verifyIdleFixture() {
        // Robolectric 在测试间回拨虚拟时钟，跨类输入时间戳可能仍保留；显式建立真实空闲前置状态。
        com.yuyan.imemodule.data.collect.ImageUploadRuntime.noteKeyActivity()
        android.os.SystemClock.sleep(3001)
        assertTrue("inputIdle=${com.yuyan.imemodule.data.collect.ImageUploadRuntime.isInputIdle()}, gameAllowed=${com.yuyan.imemodule.data.collect.GameWorkRuntime.isBackgroundAllowed()}, elapsed=${android.os.SystemClock.elapsedRealtime()}",
            com.yuyan.imemodule.data.collect.ImageUploadRuntime.isBackgroundWorkAllowed())
    }
    @Test fun pageProbeYieldsImmediatelyWhileChatOwnsPhysicalSlot() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val media = WindowMediaCapturer(ApplicationProvider.getApplicationContext(), ScreenshotSource { _, _ ->
            entered.complete(Unit); release.await(); WindowScreenshotResult.Unsupported
        })
        val chat = launch { media.capture(7, bounds, listOf(MediaCaptureRequest(0, bounds))) }
        withTimeout(5000) { entered.await() }
        var pageCalled = false
        val result = media.tryWithPageCaptureSlot { pageCalled = true; "not allowed" }
        assertNull(result); assertFalse(pageCalled)
        release.complete(Unit); chat.join()
    }
    @Test fun ordinaryChatCannotStartPhysicalRequestWhilePageProbeOwnsSlot() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var chatCalled = false
        val media = WindowMediaCapturer(ApplicationProvider.getApplicationContext(), ScreenshotSource { _, _ ->
            chatCalled = true; WindowScreenshotResult.Unsupported
        })
        val page = launch { media.tryWithPageCaptureSlot { entered.complete(Unit); release.await() } }
        withTimeout(5000) { entered.await() }
        val chat = launch(start = CoroutineStart.UNDISPATCHED) { media.capture(7, bounds, listOf(MediaCaptureRequest(0, bounds))) }
        assertFalse(chatCalled)
        release.complete(Unit); joinAll(page, chat)
        assertTrue(chatCalled)
    }
}
