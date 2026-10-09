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
class VideoVisitStoreTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "video_visits.db").absolutePath)
    }

    @Test fun firstFrameIsPersistedAtomicallyWithEntryAndCannotReplaceEarlierFrame() {
        val first = "00000000-0000-4000-8000-000000000001"
        val other = "00000000-0000-4000-8000-000000000002"
        VideoVisitStore(app).use { store ->
            val started = store.enter("douyin", "confirmed", 100, 1000, firstImage = first)
            assertEquals(first, started.active.firstImage)
            val same = store.enter("douyin", "confirmed", 200, 1100, firstImage = other)
            assertFalse(same.started); assertEquals(first, same.active.firstImage)
            store.finish(started.active.id, 300, 1200, VideoExitReason.LOCKED)
        }
        VideoVisitStore(app).use { assertEquals(first, it.completed().single().firstImage) }
    }

    @Test fun versionOneMigrationPreservesActiveAndCompleted() {
        val file = File(app.noBackupFilesDir, "video_visits.db")
        val visit = VideoVisit("00000000-0000-4000-8000-000000000001", "wechat", "local", 1000, 100)
        val done = visit.copy(id = "00000000-0000-4000-8000-000000000002", reason = VideoExitReason.INTERRUPTED)
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE active (id INTEGER PRIMARY KEY CHECK(id=1), payload TEXT NOT NULL)")
            db.execSQL("CREATE TABLE completed (id TEXT PRIMARY KEY, entered_at INTEGER NOT NULL, payload TEXT NOT NULL)")
            db.execSQL("CREATE INDEX completed_order ON completed(entered_at,id)")
            db.execSQL("INSERT INTO active VALUES(1,?)", arrayOf(kotlinx.serialization.json.Json.encodeToString(VideoVisit.serializer(), visit)))
            db.execSQL("INSERT INTO completed VALUES(?,?,?)", arrayOf<Any>(done.id, done.enteredAt, kotlinx.serialization.json.Json.encodeToString(VideoVisit.serializer(), done)))
            db.version = 1
        }
        VideoVisitStore(app).use { s ->
            assertEquals(visit, s.active()); assertEquals(done, s.due(10000).single())
            assertEquals(2, s.readableDatabase.version)
            assertTrue(s.acknowledge(done) { true }); assertEquals(visit, s.active())
        }
    }

    @Test fun backgroundExitAndScreenOffPersistDurationWithoutImages() {
        VideoVisitStore(app).use { store ->
            for (reason in listOf(VideoExitReason.BACKGROUND, VideoExitReason.EXIT, VideoExitReason.LOCKED)) {
                val visit = store.enter("wechat", "video", 1_000, 10_000).active
                store.finish(visit.id, 44_000, 53_000, reason)
                assertNull(store.active())
            }
        }
        VideoVisitStore(app).use { store ->
            val records = store.completed()
            assertEquals(3, records.size)
            records.forEach { assertEquals(43_000L, it.durationMillis); assertTrue(it.complete); assertNull(it.lastImage) }
        }
    }

    @Test fun recoveryMarksOnlyActiveVisitIncompleteAndDoesNotInventAnEnd() {
        VideoVisitStore(app).use { store ->
            store.enter("douyin", "first", 100, 1000)
            store.enter("douyin", "second", 200, 1100)
        }
        VideoVisitStore(app).use { store ->
            store.recoverInterrupted()
            store.recoverInterrupted()
            assertNull(store.active())
            val records = store.completed()
            assertEquals(2, records.size)
            assertTrue(records.single { it.videoKey == "first" }.complete)
            val lost = records.single { it.videoKey == "second" }
            assertEquals(VideoExitReason.INTERRUPTED, lost.reason)
            assertNull(lost.endedAt); assertNull(lost.durationMillis); assertFalse(lost.complete)
        }
    }

    @Test fun failedEndWriteRollsBackActiveStateAndCanBeRetried() {
        VideoVisitStore(app).use { store ->
            val visit = store.enter("wechat", "video", 100, 1000).active
            store.writableDatabase.execSQL("CREATE TRIGGER fail_end BEFORE INSERT ON completed BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            assertTrue(runCatching { store.finish(visit.id, 200, 1100, VideoExitReason.BACKGROUND) }.isFailure)
            assertEquals(visit, store.active())
            assertTrue(store.completed().isEmpty())
            store.writableDatabase.execSQL("DROP TRIGGER fail_end")
            store.finish(visit.id, 200, 1100, VideoExitReason.BACKGROUND)
            assertNull(store.active()); assertEquals(1, store.completed().size)
        }
    }

    @Test fun failedSwitchDoesNotLoseOldVisitOrCreateNewOne() {
        VideoVisitStore(app).use { store ->
            val first = store.enter("wechat", "first", 100, 1000).active
            store.writableDatabase.execSQL("CREATE TRIGGER fail_end BEFORE INSERT ON completed BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            assertTrue(runCatching { store.enter("wechat", "second", 200, 1100) }.isFailure)
            assertEquals(first, store.active())
        }
    }

    @Test fun activeCheckpointFailureRollsBackAlreadyInsertedCompletedRecord() {
        VideoVisitStore(app).use { store ->
            val first = store.enter("wechat", "first", 100, 1000).active
            store.writableDatabase.execSQL("CREATE TRIGGER fail_active BEFORE INSERT ON active BEGIN SELECT RAISE(ABORT, 'checkpoint failure'); END")
            assertTrue(runCatching { store.enter("wechat", "second", 200, 1100) }.isFailure)
            assertEquals(first, store.active())
            assertTrue(store.completed().isEmpty())
            store.writableDatabase.execSQL("DROP TRIGGER fail_active")
            store.enter("wechat", "second", 200, 1100)
            assertEquals(first.id, store.completed().single().id)
        }
    }

    @Test fun completedQueueOrderingHasAnIndexForOfflineBacklogs() {
        VideoVisitStore(app).use { store ->
            store.readableDatabase.rawQuery("EXPLAIN QUERY PLAN SELECT payload FROM completed ORDER BY entered_at,id LIMIT 100", null).use { cursor ->
                val details = buildList { while (cursor.moveToNext()) add(cursor.getString(3)) }.joinToString(" ")
                assertFalse(details, details.contains("TEMP B-TREE", ignoreCase = true))
            }
        }
    }

    @Test fun lateEndAndLateFrameCannotModifyNextVisit() {
        VideoVisitStore(app).use { store ->
            val first = store.enter("wechat", "first", 100, 1000).active
            val second = store.enter("wechat", "second", 200, 1100).active
            assertNull(store.finish(first.id, 300, 1200, VideoExitReason.EXIT))
            assertFalse(store.attachFrame(first.id, "first", VideoFrameRole.LAST, "old"))
            assertEquals(second, store.active()); assertEquals(1, store.completed().size)
        }
    }

    @Test fun rejectedLateCallbacksDoNotRewriteUnchangedCheckpoint() {
        VideoVisitStore(app).use { store ->
            val current = store.enter("wechat", "current", 100, 1000).active
            store.writableDatabase.execSQL("CREATE TRIGGER fail_active BEFORE INSERT ON active BEGIN SELECT RAISE(ABORT, 'unexpected rewrite'); END")
            assertFalse(store.attachFrame("old-id", "old-video", VideoFrameRole.LAST, "old-image"))
            assertNull(store.finish("old-id", 200, 1100, VideoExitReason.EXIT))
            assertEquals(current, store.active())
        }
    }

    @Test fun framesSurviveRecoveryButDoNotTurnUnknownDurationIntoZero() {
        val id = VideoVisitStore(app).use { store ->
            val v = store.enter("douyin", "video", 100, 1000).active
            assertTrue(store.attachFrame(v.id, "video", VideoFrameRole.FIRST, "asset-sha"))
            v.id
        }
        VideoVisitStore(app).use { store ->
            store.recoverInterrupted()
            val v = store.completed().single()
            assertEquals(id, v.id); assertEquals("asset-sha", v.firstImage); assertNull(v.durationMillis)
            assertTrue(store.completed(0).isEmpty())
        }
    }
}
