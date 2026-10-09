package com.yuyan.imemodule.data.capture.page

import android.graphics.Bitmap
import com.yuyan.imemodule.data.capture.media.ScreenshotSource
import com.yuyan.imemodule.data.capture.media.WindowScreenshotResult
import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PageFrameProbeTest {
    private val bounds = IntRect(10, 20, 510, 1020)
    private fun listLabels() = listOf(
        PageLabel("微信", IntRect(10, 50, 90, 80)), PageLabel("微信", IntRect(10, 950, 90, 980)),
        PageLabel("通讯录", IntRect(130, 950, 210, 980)), PageLabel("发现", IntRect(260, 950, 340, 980)),
        PageLabel("我", IntRect(400, 950, 480, 980)))

    @Test fun allowedListFrameIsCroppedToHostWindowBeforeRecognitionAndEncoding() = runBlocking {
        val bitmap = Bitmap.createBitmap(600, 1100, Bitmap.Config.ARGB_8888)
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> WindowScreenshotResult.Success(bitmap, 0, 0) },
            recognize = { image -> assertEquals(500, image.width); assertEquals(1000, image.height); listLabels() },
            allowed = { true })
        val frame = probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true })!!
        assertEquals(PageKind.CONVERSATION_LIST, frame.page.kind)
        assertTrue(frame.bytes.isNotEmpty()); assertTrue(bitmap.isRecycled)
    }
    @Test fun sameFrameMiniAppCapsuleStartsPageButSensitiveInputAndRegularChatMenuDoNot() = runBlocking {
        suspend fun capture(dark: Boolean = false, capsule: Boolean = true, sensitive: Boolean = false, ocrFailed: Boolean = false): PageFrame? {
            val bitmap = Bitmap.createBitmap(1200, 2400, Bitmap.Config.ARGB_8888)
            val background = if (dark) android.graphics.Color.rgb(17, 17, 17) else android.graphics.Color.WHITE
            val foreground = if (dark) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            bitmap.eraseColor(background)
            val h = 144.0; val cx = 1200 - h * .72; val cy = 96 + h * .5
            fun disk(x: Double, y: Double, radius: Double, inner: Double = 0.0) {
                for (py in (y - radius).toInt()..(y + radius).toInt())
                    for (px in (x - radius).toInt()..(x + radius).toInt()) {
                        val d = (px - x) * (px - x) + (py - y) * (py - y)
                        if (d <= radius * radius && d >= inner * inner) bitmap.setPixel(px, py, foreground)
                    }
            }
            if (capsule) {
                disk(cx, cy, h * .055)
                disk(cx, cy, h * .20, h * .145)
                for (offset in listOf(.93, 1.10, 1.28)) disk(cx - h * offset, cy, h * .025)
            } else {
                for (offset in listOf(.36, .51, .66)) disk(1200 - h * offset, cy, h * .025)
            }
            val probe = PageFrameProbe(ScreenshotSource { _, _ -> WindowScreenshotResult.Success(bitmap, 0, 0) },
                recognize = { if (ocrFailed) emptyList() else listOf(PageLabel("示例小程序", IntRect(100, 130, 700, 200))) +
                    if (sensitive) listOf(PageLabel("请输入支付密码", IntRect(100, 500, 800, 560))) else emptyList() },
                allowed = { true })
            return probe.capture("com.tencent.mm", 7, IntRect(0, 0, 1200, 2400), emptyList(), current = { true })
        }
        assertEquals(PageKind.MINI_APP, capture()?.page?.kind)
        assertEquals(PageKind.MINI_APP, capture(dark = true)?.page?.kind)
        assertNull(capture(capsule = false))
        assertNull(capture(sensitive = true))
        assertNull(capture(ocrFailed = true))
    }

    @Test fun rejectionDiagnosticsDistinguishSystemOcrScopeAndNeverIncludeContent() = runBlocking {
        fun messages() = org.robolectric.shadows.ShadowLog.getLogsForTag("BrowsingPageProbe").map { it.msg }
        org.robolectric.shadows.ShadowLog.clear()
        val failed = PageFrameProbe(ScreenshotSource { _, _ -> WindowScreenshotResult.Failed(6) },
            recognize = { error("system failure must not reach OCR") }, allowed = { true })
        assertNull(failed.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true }))
        assertEquals(listOf("system_failed:6"), messages())
        suspend fun rejected(labels: List<PageLabel>): List<String> {
            org.robolectric.shadows.ShadowLog.clear()
            val bitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888)
            val probe = PageFrameProbe(ScreenshotSource { _, _ -> WindowScreenshotResult.Success(bitmap, 10, 20) },
                recognize = { labels }, allowed = { true })
            assertNull(probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true }))
            assertTrue(bitmap.isRecycled)
            return messages()
        }
        fun emptyEvidence(count: Int) = "feed_navigation:labels=$count,top_follow=0,top_recommend=0," +
            "top_friend=0,top_drama=0,bottom_home=0,bottom_message=0,bottom_me=0,bottom_follow=0"
        assertEquals(listOf("ocr_empty", "classification_rejected:insufficient_evidence", emptyEvidence(0)), rejected(emptyList()))
        val privateBody = "不允许写入日志的私密正文"
        val privateMessages = rejected(listOf(PageLabel(privateBody, IntRect(100, 400, 400, 450))))
        assertEquals(listOf("classification_rejected:insufficient_evidence", emptyEvidence(1)), privateMessages)
        assertFalse(privateMessages.any { it.contains(privateBody) })
        org.robolectric.shadows.ShadowLog.clear()
        val stale = PageFrameProbe(ScreenshotSource { _, _ -> error("must not capture stale window") },
            recognize = { emptyList() }, allowed = { true })
        assertNull(stale.capture("com.tencent.mm", 7, bounds, emptyList(), current = { false }))
        assertEquals(listOf("scope_lost"), messages())
    }

    @Test fun knownPasswordPageDoesNotEvenRequestScreenshot() = runBlocking {
        var requested = false
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> requested = true; WindowScreenshotResult.Unsupported },
            recognize = { emptyList() }, allowed = { true })
        assertNull(probe.capture("com.tencent.mm", 7, bounds,
            listOf(PageLabel("", IntRect(0, 0, 1, 1), password = true)), current = { true }))
        assertFalse(requested)
    }
    @Test fun staleFrameAfterOcrIsDiscardedRatherThanSavedToNextPage() = runBlocking {
        val bitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888)
        var current = true
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> WindowScreenshotResult.Success(bitmap, 10, 20) },
            recognize = { current = false; listLabels() }, allowed = { true })
        assertNull(probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { current }))
        assertTrue(bitmap.isRecycled)
    }
    @Test fun sensitiveOcrPromptNeverProducesEncodedFrame() = runBlocking {
        val bitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888)
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> WindowScreenshotResult.Success(bitmap, 10, 20) },
            recognize = { listLabels() + PageLabel("请输入支付密码", IntRect(100, 400, 400, 450)) }, allowed = { true })
        assertNull(probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true }))
        assertTrue(bitmap.isRecycled)
    }
    @Test fun pausedInputOrGameDoesNotRequestAnyFrame() = runBlocking {
        var requested = false
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> requested = true; WindowScreenshotResult.Unsupported },
            recognize = { emptyList() }, allowed = { false })
        assertNull(probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true }))
        assertFalse(requested)
    }
    @Test fun failedSecureScreenshotDoesNotFallbackToAnotherCaptureMethod() = runBlocking {
        var requests = 0
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> requests++; WindowScreenshotResult.Failed(6) },
            recognize = { fail("must not OCR failed screenshot"); emptyList() }, allowed = { true })
        assertNull(probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true }))
        assertEquals(1, requests)
    }
    @Test fun oldTreeCannotSupplyPositiveNavigationMissingFromCurrentFrame() = runBlocking {
        val bitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888)
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> WindowScreenshotResult.Success(bitmap, 10, 20) },
            recognize = { emptyList() }, allowed = { true })
        val oldTree = listLabels().map { it.copy(bounds = IntRect(it.bounds.left + 10, it.bounds.top + 20, it.bounds.right + 10, it.bounds.bottom + 20)) }
        assertNull(probe.capture("com.tencent.mm", 7, bounds, oldTree, current = { true }))
        assertTrue(bitmap.isRecycled)
    }
    @Test fun cancellationWaitsForNonCancellableOcrTaskBeforeRecyclingItsBitmap() = runBlocking {
        val bitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888)
        val started = CompletableDeferred<Unit>()
        lateinit var continuation: kotlinx.coroutines.CancellableContinuation<List<PageLabel>>
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> WindowScreenshotResult.Success(bitmap, 10, 20) },
            recognize = { suspendCancellableCoroutine { c ->
                continuation = c
                c.invokeOnCancellation { cancelled.set(true) }
                started.complete(Unit)
            } }, allowed = { true })
        val job = async { probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true }) }
        started.await()
        job.cancel()
        if (cancelled.get()) job.join()
        try { assertFalse("bitmap must live until OCR callback", bitmap.isRecycled) }
        finally { continuation.resume(emptyList()); job.join() }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun cancellationRetainsPhysicalCaptureUntilSystemCallbackFinishes() = runBlocking {
        val bitmap = Bitmap.createBitmap(500, 1000, Bitmap.Config.ARGB_8888)
        val started = CompletableDeferred<Unit>()
        lateinit var continuation: kotlinx.coroutines.CancellableContinuation<WindowScreenshotResult>
        val cancelled = java.util.concurrent.atomic.AtomicBoolean(false)
        val source = ScreenshotSource { _, _ -> suspendCancellableCoroutine { c ->
            continuation = c; c.invokeOnCancellation { cancelled.set(true) }; started.complete(Unit)
        } }
        val probe = PageFrameProbe(source, recognize = { listLabels() }, allowed = { true })
        val job = async { probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true }) }
        started.await(); job.cancel()
        if (cancelled.get()) job.join()
        try { assertFalse("physical request must retain its slot until callback", job.isCompleted) }
        finally { continuation.resume(WindowScreenshotResult.Success(bitmap, 10, 20)); job.join() }
        assertTrue(bitmap.isRecycled)
    }

    @Test fun browsingCannotInheritSpecialChatSendPermission() = runBlocking {
        val send = com.yuyan.imemodule.data.capture.media.ChatCaptureAttempt(
            framePermission = { true }, scopeCurrent = { true }, authorized = { true }, allowsSettledSendFrame = true)
        var calls = 0
        val probe = PageFrameProbe(ScreenshotSource { _, _ -> calls++; WindowScreenshotResult.Unsupported },
            recognize = { emptyList() }, allowed = { true })
        kotlinx.coroutines.withContext(send) {
            assertNull(probe.capture("com.tencent.mm", 7, bounds, emptyList(), current = { true }))
        }
        assertEquals(0, calls)
    }
}
