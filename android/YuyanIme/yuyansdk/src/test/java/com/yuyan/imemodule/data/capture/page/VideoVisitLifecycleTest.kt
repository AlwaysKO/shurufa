package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VideoVisitLifecycleTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "video_visits.db").absolutePath)
    }

    @Test fun confirmedOtherAppEndsAtEventTimeRatherThanProcessingTime() {
        VideoVisitStore(app).use { store ->
            val visit = store.enter("wechat", "video", 1000, 10000).active
            val lifecycle = VideoVisitLifecycle(store)
            lifecycle.foreground(visit.id, "com.ss.android.ugc.aweme", applicationWindow = true,
                home = false, elapsed = 43000, wallTime = 52000)
            val result = store.completed().single()
            assertEquals(VideoExitReason.BACKGROUND, result.reason)
            assertEquals(42000L, result.durationMillis)
            assertEquals(52000L, result.endedAt)
        }
    }

    @Test fun homeAfterClosingHostEndsVisitAndReturningStartsFresh() {
        VideoVisitStore(app).use { store ->
            val visit = store.enter("douyin", "video", 1000, 10000).active
            VideoVisitLifecycle(store).foreground(visit.id, "launcher", true, true, 2000, 11000)
            assertEquals(VideoExitReason.EXIT, store.completed().single().reason)
            val returned = store.enter("douyin", "video", 100000, 109000).active
            assertNotEquals(visit.id, returned.id)
            store.finish(returned.id, 101000, 110000, VideoExitReason.EXIT)
            assertTrue(store.completed().all { it.durationMillis == 1000L })
        }
    }

    @Test fun overlaysUnknownAndSameApplicationDoNotEndVisit() {
        VideoVisitStore(app).use { store ->
            val visit = store.enter("wechat", "video", 1000, 10000).active
            val lifecycle = VideoVisitLifecycle(store)
            lifecycle.foreground(visit.id, "input.method", false, false, 2000, 11000)
            lifecycle.foreground(visit.id, "com.android.systemui", false, false, 2100, 11100)
            lifecycle.foreground(visit.id, null, true, false, 2200, 11200)
            lifecycle.foreground(visit.id, "com.tencent.mm", true, false, 2300, 11300)
            assertEquals(visit, store.active()); assertTrue(store.completed().isEmpty())
        }
    }

    @Test fun screenOffEndsWithoutWaitingForLockOrAnotherWindowEvent() {
        VideoVisitStore(app).use { store ->
            val visit = store.enter("wechat", "video", 1000, 10000).active
            VideoVisitLifecycle(store).screenOff(visit.id, 3000, 12000)
            val result = store.completed().single()
            assertEquals(VideoExitReason.LOCKED, result.reason)
            assertEquals(2000L, result.durationMillis)
        }
    }

    @Test fun lateScreenOffCannotEndAVisitStartedAfterUnlock() {
        VideoVisitStore(app).use { store ->
            val old = store.enter("wechat", "old", 1000, 10000).active
            val current = store.enter("wechat", "new", 2000, 11000).active
            VideoVisitLifecycle(store).screenOff(old.id, 1500, 10500)
            assertEquals(current, store.active())
        }
    }

    @Test fun retryAfterDiskFailureKeepsOriginalScreenOffTime() {
        VideoVisitStore(app).use { store ->
            val visit = store.enter("wechat", "video", 1000, 10000).active
            val lifecycle = VideoVisitLifecycle(store)
            store.writableDatabase.execSQL("CREATE TRIGGER fail_end BEFORE INSERT ON completed BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            assertTrue(runCatching { lifecycle.screenOff(visit.id, 3000, 12000) }.isFailure)
            store.writableDatabase.execSQL("DROP TRIGGER fail_end")
            lifecycle.screenOff(visit.id, 3000, 12000)
            val result = store.completed().single()
            assertEquals(2000L, result.durationMillis)
            assertEquals(12000L, result.endedAt)
        }
    }

    @Test fun serviceLostIsIncompleteRatherThanCountingUntilReconnect() {
        VideoVisitStore(app).use { store ->
            val visit = store.enter("wechat", "video", 1000, 10000).active
            VideoVisitLifecycle(store).interrupted(visit.id)
            val result = store.completed().single()
            assertEquals(VideoExitReason.INTERRUPTED, result.reason)
            assertFalse(result.complete); assertNull(result.durationMillis); assertNull(result.endedAt)
        }
    }
}
