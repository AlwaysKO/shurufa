package com.yuyan.imemodule.data.capture.page

import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
@LooperMode(LooperMode.Mode.PAUSED)
class VideoVisitMonitorTest {
    private lateinit var app: Application
    private var elapsed = 1000L
    private var wall = 10000L
    private var allowed = true
    private var evidence = VideoForegroundEvidence(VideoWindowKind.APPLICATION, "other.app", 20)
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "video_visits.db").absolutePath)
    }
    private fun idle() = shadowOf(Looper.getMainLooper()).idle()
    private fun monitor() = VideoVisitMonitor(app, Handler(Looper.getMainLooper()),
        readForeground = { _, _ -> evidence }, allowed = { allowed }, interactive = { true },
        elapsed = { elapsed }, wall = { wall })
    private fun enter(m: VideoVisitMonitor) {
        idle()
        m.confirmVideo(m.generation(), "wechat", "video", elapsed, wall)
        idle()
    }
    @Test fun revokedAndRegrantedConsentCannotAcceptQueuedOldFrame() {
        var epoch = 1L
        val m = VideoVisitMonitor(app, Handler(Looper.getMainLooper()), { _, _ -> evidence },
            { true }, { true }, { elapsed }, { wall }, consentEpoch = { epoch })
        idle()
        m.pageCaptured(m.generation(), "com.tencent.mm", PageKind.MEDIA_FEED,
            PageWriteResult(PageWriteStatus.SAVED, "00000000-0000-4000-8000-000000000001"), elapsed, wall)
        epoch += 2; idle()
        VideoVisitStore(app).use { assertNull(it.active()); assertTrue(it.completed().isEmpty()) }
        m.close(); idle()
    }

    @Test fun consentChangeDuringActiveObservationCannotReportAcrossRevokedInterval() {
        var epoch = 1L
        val m = VideoVisitMonitor(app, Handler(Looper.getMainLooper()), { _, _ -> evidence },
            { true }, { true }, { elapsed }, { wall }, consentEpoch = { epoch })
        enter(m); epoch += 2
        elapsed += 3000; wall += 3000; m.contentScrolled("com.tencent.mm"); idle()
        VideoVisitStore(app).use {
            assertEquals(VideoExitReason.INTERRUPTED, it.completed().single().reason)
            assertNull(it.completed().single().durationMillis)
        }
        m.close(); idle()
    }

    @Test fun newAuthorizedFrameCannotCompleteObservationFromOldConsentOrPolicy() {
        for (changeConsent in listOf(true, false)) {
            var consent = 1L
            var policy = 1L
            val m = VideoVisitMonitor(app, Handler(Looper.getMainLooper()), { _, _ -> evidence },
                { true }, { true }, { elapsed }, { wall }, consentEpoch = { consent }, policyEpoch = { policy })
            idle()
            m.pageCaptured(m.generation(), "com.tencent.mm", PageKind.MEDIA_FEED,
                PageWriteResult(PageWriteStatus.SAVED, "00000000-0000-4000-8000-000000000001"), elapsed, wall)
            idle()
            if (changeConsent) consent += 2 else policy += 2
            elapsed += 3000; wall += 3000
            m.pageCaptured(m.generation(), "com.tencent.mm", PageKind.MEDIA_FEED,
                PageWriteResult(PageWriteStatus.SAVED, "00000000-0000-4000-8000-000000000002"), elapsed, wall)
            idle()
            VideoVisitStore(app).use {
                val previous = it.completed().single()
                assertEquals(VideoExitReason.INTERRUPTED, previous.reason)
                assertNull(previous.durationMillis)
                assertFalse(previous.complete)
                assertEquals("00000000-0000-4000-8000-000000000002", it.active()!!.firstImage)
            }
            m.close(); idle()
            app.deleteDatabase(File(app.noBackupFilesDir, "video_visits.db").absolutePath)
        }
    }

    @Test fun topologyOnlyEventKeepsSameObservedApplicationWindowButNotAnotherWindow() {
        val m = monitor(); idle()
        m.pageCaptured(m.generation(), "com.tencent.mm", PageKind.MEDIA_FEED,
            PageWriteResult(PageWriteStatus.SAVED, "00000000-0000-4000-8000-000000000001"), elapsed, wall, windowId = 10)
        idle()
        evidence = VideoForegroundEvidence(VideoWindowKind.APPLICATION, "com.tencent.mm", 10)
        m.windowChanged(null, -1, topologyOnly = true); idle()
        VideoVisitStore(app).use { assertNotNull(it.active()); assertTrue(it.completed().isEmpty()) }
        evidence = VideoForegroundEvidence(VideoWindowKind.APPLICATION, "com.tencent.mm", 11)
        m.windowChanged(null, -1, topologyOnly = true); idle()
        VideoVisitStore(app).use { assertNull(it.active()); assertEquals(VideoExitReason.INTERRUPTED, it.completed().single().reason) }
        m.close(); idle()
    }

    @Test fun savedFeedFrameStartsExplicitlyUnconfirmedVisitAndScrollEndsIt() {
        val m = monitor(); idle()
        val image = "00000000-0000-4000-8000-000000000001"
        m.pageCaptured(m.generation(), "com.tencent.mm", PageKind.MEDIA_FEED,
            PageWriteResult(PageWriteStatus.SAVED, image), elapsed, wall); idle()
        VideoVisitStore(app).use {
            assertEquals(VideoObservationKind.UNCONFIRMED_FEED, it.active()!!.observationKind)
            assertEquals(image, it.active()!!.firstImage)
        }
        elapsed += 3000; wall += 3000
        m.contentScrolled("com.tencent.mm"); idle()
        VideoVisitStore(app).use {
            val end = it.completed().single()
            assertEquals(VideoExitReason.PAGE_CHANGED, end.reason)
            assertEquals(3000L, end.durationMillis); assertNull(end.lastImage)
        }
        m.close(); idle()
    }

    @Test fun nonFeedOrUnpersistedOrStaleFrameCannotStartVisit() {
        val m = monitor(); idle()
        val image = "00000000-0000-4000-8000-000000000001"
        for (kind in PageKind.entries.filter { it != PageKind.MEDIA_FEED })
            m.pageCaptured(m.generation(), "com.tencent.mm", kind, PageWriteResult(PageWriteStatus.SAVED, image), elapsed, wall)
        for (status in PageWriteStatus.entries.filter { it != PageWriteStatus.SAVED })
            m.pageCaptured(m.generation(), "com.tencent.mm", PageKind.MEDIA_FEED, PageWriteResult(status, image), elapsed, wall)
        val old = m.generation(); m.invalidate()
        m.pageCaptured(old, "com.tencent.mm", PageKind.MEDIA_FEED, PageWriteResult(PageWriteStatus.SAVED, image), elapsed, wall)
        m.pageCaptured(m.generation(), "other.app", PageKind.MEDIA_FEED, PageWriteResult(PageWriteStatus.SAVED, image), elapsed, wall)
        idle(); VideoVisitStore(app).use { assertNull(it.active()); assertTrue(it.completed().isEmpty()) }
        m.close(); idle()
    }

    @Test fun sameHostApplicationWindowChangeCannotCountLaterChatAsVideo() {
        val m = monitor(); enter(m)
        evidence = VideoForegroundEvidence(VideoWindowKind.APPLICATION, "com.tencent.mm", 10)
        elapsed += 2000; wall += 2000
        m.windowChanged("com.tencent.mm", 10); idle()
        VideoVisitStore(app).use {
            assertNull(it.active()); assertEquals(VideoExitReason.PAGE_CHANGED, it.completed().single().reason)
        }
        m.close(); idle()
    }

    @Test fun completedRecoverySwitchAndCloseWakeOnlyAfterPersistence() {
        VideoVisitStore(app).use { it.enter("wechat", "before-restart", 1, 1) }
        var wakes = 0
        val m = VideoVisitMonitor(app, Handler(Looper.getMainLooper()),
            readForeground = { _, _ -> evidence }, allowed = { true }, interactive = { true },
            elapsed = { elapsed }, wall = { wall }, onCompleted = {
                VideoVisitStore(app).use { assertTrue(it.completed().isNotEmpty()) }; wakes++
            })
        idle(); assertEquals(1, wakes)
        enter(m); assertEquals(1, wakes)
        m.confirmVideo(m.generation(), "wechat", "next", 2000, 11000); idle()
        assertEquals(2, wakes)
        m.close(); idle(); assertEquals(3, wakes)
    }

    @Test fun actualScreenOffBroadcastPersistsEndUsingReceiptTime() {
        val m = monitor(); enter(m)
        elapsed = 4000; wall = 13000
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); idle()
        elapsed = 100000; wall = 109000
        VideoVisitStore(app).use { store ->
            assertNull(store.active())
            val end = store.completed().single()
            assertEquals(VideoExitReason.LOCKED, end.reason); assertEquals(3000L, end.durationMillis)
        }
        m.close(); idle()
    }
    @Test fun verifiedOtherApplicationEndsAtWindowEventNotWorkerTime() {
        val m = monitor(); enter(m)
        elapsed = 4000; wall = 13000
        m.windowChanged("other.app", 20)
        elapsed = 9000; wall = 18000
        idle()
        VideoVisitStore(app).use { store -> assertEquals(3000L, store.completed().single().durationMillis) }
        m.close(); idle()
    }
    @Test fun inputAndSystemOverlayDoNotEndTheVideo() {
        val m = monitor(); enter(m)
        evidence = VideoForegroundEvidence(VideoWindowKind.OVERLAY)
        m.windowChanged("input.method", 20); idle()
        VideoVisitStore(app).use { store -> assertNotNull(store.active()); assertTrue(store.completed().isEmpty()) }
        m.close(); idle()
    }
    @Test fun windowChangeInvalidatesAnObservationQueuedBeforeIt() {
        val m = monitor(); idle()
        m.confirmVideo(m.generation(), "wechat", "old-video", elapsed, wall)
        m.windowChanged("other.app", 20)
        idle()
        VideoVisitStore(app).use { store -> assertNull(store.active()); assertTrue(store.completed().isEmpty()) }
        m.close(); idle()
    }
    @Test fun foregroundReadNoLongerMatchingEventCannotInventPreciseEndTime() {
        val m = monitor(); enter(m)
        evidence = VideoForegroundEvidence(VideoWindowKind.APPLICATION, "third.app", 30)
        m.windowChanged("other.app", 20); idle()
        VideoVisitStore(app).use { store ->
            val end = store.completed().single()
            assertEquals(VideoExitReason.INTERRUPTED, end.reason); assertNull(end.durationMillis)
        }
        m.close(); idle()
    }
    @Test fun consentRevokedBeforeQueuedConfirmationPreventsNewVisit() {
        val m = monitor(); idle()
        m.confirmVideo(m.generation(), "wechat", "video", elapsed, wall)
        allowed = false; idle()
        VideoVisitStore(app).use { store -> assertNull(store.active()) }
        m.close(); idle()
    }
    @Test fun screenOffAlreadyReceivedIsNotDiscardedByClose() {
        val m = monitor(); enter(m)
        elapsed = 4000; wall = 13000
        val before = m.generation()
        app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF))
        shadowOf(Looper.getMainLooper()).runOneTask() // 广播已到达，持久化任务仍排队
        assertTrue(m.generation() > before)
        m.close(); idle()
        VideoVisitStore(app).use { store ->
            val end = store.completed().single()
            assertEquals(VideoExitReason.LOCKED, end.reason); assertEquals(3000L, end.durationMillis)
        }
    }
    @Test fun invalidationDuringEntryCannotLeaveAnActiveBackgroundVisit() {
        lateinit var m: VideoVisitMonitor
        var boundary = false
        m = VideoVisitMonitor(app, Handler(Looper.getMainLooper()), { _, _ -> evidence },
            { true }, {
                if (!boundary) { boundary = true; m.windowChanged("other.app", 20) }
                true
            }, { elapsed }, { wall })
        enter(m)
        VideoVisitStore(app).use { store ->
            assertNull(store.active())
            assertEquals(VideoExitReason.INTERRUPTED, store.completed().single().reason)
        }
        m.close(); idle()
    }
    @Test fun failedScreenOffKeepsOriginalBoundaryAndBlocksNextVisitUntilRetry() {
        val m = monitor(); enter(m)
        VideoVisitStore(app).use { store ->
            store.writableDatabase.execSQL("CREATE TRIGGER fail_end BEFORE INSERT ON completed BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            elapsed = 4000; wall = 13000
            app.sendBroadcast(Intent(Intent.ACTION_SCREEN_OFF)); idle()
            elapsed = 100000; wall = 109000
            m.confirmVideo(m.generation(), "douyin", "next", elapsed, wall); idle()
            assertEquals("video", store.active()!!.videoKey)
            store.writableDatabase.execSQL("DROP TRIGGER fail_end")
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(30))
            assertNull(store.active())
            val end = store.completed().single()
            assertEquals(VideoExitReason.LOCKED, end.reason); assertEquals(3000L, end.durationMillis)
        }
        m.close(); idle()
    }

    @Test fun overlayMustNotHideAConfirmedDifferentUnderlyingApplication() {
        val m = monitor(); enter(m)
        evidence = VideoForegroundEvidence(VideoWindowKind.OVERLAY, "other.app", 30)
        m.windowChanged("input.method", 20); idle()
        VideoVisitStore(app).use { store ->
            assertNull(store.active())
            assertEquals(VideoExitReason.INTERRUPTED, store.completed().single().reason)
        }
        m.close(); idle()
    }

    @Test fun closePersistsInterruptionBeforeWorkerShutdown() {
        var stopped = false
        val m = VideoVisitMonitor(app, Handler(Looper.getMainLooper()), { _, _ -> evidence },
            { allowed }, { true }, { elapsed }, { wall }, onClosed = { stopped = true })
        enter(m); m.close(); idle()
        assertTrue(stopped)
        VideoVisitStore(app).use { store ->
            assertNull(store.active()); assertEquals(VideoExitReason.INTERRUPTED, store.completed().single().reason)
        }
    }
}
