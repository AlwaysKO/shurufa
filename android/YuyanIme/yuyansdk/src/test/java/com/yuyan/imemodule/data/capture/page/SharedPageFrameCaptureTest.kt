package com.yuyan.imemodule.data.capture.page

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class SharedPageFrameCaptureTest {
    private val frame = PageFrame(PageDecision(PageKind.MEDIA_FEED, "verified"), byteArrayOf(1), 10, 20)
    private val key = PageFrameCaptureKey("com.tencent.mm", 4, 1, 2, 3)

    @Test fun ordinaryAndVideoCandidatesShareOnePhysicalFrameEvenWhenConcurrent() = runBlocking {
        var clock = 1000L
        val shared = SharedPageFrameCapture({ clock }, { 10000L })
        var reads = 0
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val seen = mutableListOf<ObservedPageFrame>()
        suspend fun capture(onAccepted: (PageFrame) -> Unit) {
            reads++; started.complete(Unit); release.await(); onAccepted(frame)
        }
        val first = launch { shared.capture(key, { true }, ::capture) { seen += it } }
        started.await()
        val second = launch { shared.capture(key, { true }, ::capture) { seen += it } }
        yield(); release.complete(Unit); joinAll(first, second)
        assertEquals(1, reads); assertEquals(2, seen.size)
        assertSame(seen[0].frame, seen[1].frame)
        assertEquals(1000L, seen[1].elapsed); assertEquals(10000L, seen[1].wall)
    }

    @Test fun navigationConsentPolicyWindowAndPackageNeverReuseAnotherScope() = runBlocking {
        val shared = SharedPageFrameCapture({ 1000L }, { 10000L })
        var reads = 0
        for (scope in listOf(key, key.copy(navigation = 2), key.copy(consent = 4),
            key.copy(policy = 5), key.copy(windowId = 6), key.copy(packageName = "com.ss.android.ugc.aweme"))) {
            shared.capture(scope, { true }, { accepted -> reads++; accepted(frame) }) {}
        }
        assertEquals(6, reads)
    }

    @Test fun expiredOrExplicitlyClearedFrameIsNotReplayed() = runBlocking {
        var clock = 1000L
        val shared = SharedPageFrameCapture({ clock }, { 10000L })
        var reads = 0
        suspend fun take() = shared.capture(key, { true }, { accepted -> reads++; accepted(frame) }) {}
        take(); clock += 2000; take(); shared.clear(); take()
        assertEquals(3, reads)
    }

    @Test fun revokedOrStaleCallerCannotConsumeCachedFrame() = runBlocking {
        val shared = SharedPageFrameCapture({ 1000L }, { 10000L })
        var reads = 0; var accepts = 0
        shared.capture(key, { true }, { accepted -> reads++; accepted(frame) }) { accepts++ }
        shared.capture(key, { false }, { accepted -> reads++; accepted(frame) }) { accepts++ }
        assertEquals(1, reads); assertEquals(1, accepts)
    }

    @Test fun unsuccessfulCaptureIsNotCached() = runBlocking {
        val shared = SharedPageFrameCapture({ 1000L }, { 10000L })
        var reads = 0; var accepts = 0
        shared.capture(key, { true }, { reads++ }) { accepts++ }
        shared.capture(key, { true }, { accepted -> reads++; accepted(frame) }) { accepts++ }
        assertEquals(2, reads); assertEquals(1, accepts)
    }
}
