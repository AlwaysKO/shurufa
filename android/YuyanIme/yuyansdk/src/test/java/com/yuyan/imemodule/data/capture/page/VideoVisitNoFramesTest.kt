package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VideoVisitNoFramesTest {
    private lateinit var app: Application
    private val image = "00000000-0000-4000-8000-000000000001"
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "video_visits.db").absolutePath)
    }

    @Test fun framelessObservationStaysInMemoryAndEveryExitDiscardsIt() {
        VideoVisitStore(app).use { store ->
            for (kind in VideoObservationKind.entries) for (reason in VideoExitReason.entries) {
                val visit = store.enter("douyin", "feed", 100, 1000, observationKind = kind).active
                assertEquals(visit, store.active())
                store.readableDatabase.rawQuery("SELECT count(*) FROM active", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
                assertNull(store.finish(visit.id, 200, 1100, reason))
                assertNull(store.active()); assertTrue(store.completed().isEmpty())
            }
        }
    }

    @Test fun firstFrameMakesOriginalClockDurableAndRecoveryKeepsIt() {
        val visit = VideoVisitStore(app).use { store ->
            val visit = store.enter("wechat", "feed", 100, 1000).active
            assertTrue(store.attachFrame(visit.id, visit.videoKey, VideoFrameRole.FIRST, image))
            visit
        }
        VideoVisitStore(app).use { store ->
            store.recoverInterrupted()
            val recovered = store.completed().single()
            assertEquals(visit.id, recovered.id); assertEquals(1000L, recovered.enteredAt)
            assertEquals(image, recovered.firstImage); assertNull(recovered.durationMillis)
        }
    }

    @Test fun closingWithoutAnyFrameLeavesNoRecoveryRecord() {
        VideoVisitStore(app).use { it.enter("wechat", "feed", 100, 1000) }
        VideoVisitStore(app).use {
            assertNull(it.active()); it.recoverInterrupted(); assertTrue(it.completed().isEmpty())
        }
    }

    @Test fun migrationDropsOldFramelessRowsButKeepsFirstOrLastImageReferences() {
        VideoVisitStore(app).use { store ->
            val empty = VideoVisit("empty", "wechat", "feed", 1000, 100)
            store.writableDatabase.execSQL("INSERT INTO active VALUES(1,?)", arrayOf(Json.encodeToString(empty)))
            for (row in listOf(empty.copy(reason = VideoExitReason.INTERRUPTED),
                empty.copy(id = "blank", firstImage = " ", lastImage = "", reason = VideoExitReason.INTERRUPTED),
                empty.copy(id = "first", firstImage = image, reason = VideoExitReason.INTERRUPTED),
                empty.copy(id = "last", lastImage = image, reason = VideoExitReason.INTERRUPTED))) {
                insertCompleted(store, row)
            }
            store.writableDatabase.version = 3
        }
        VideoVisitStore(app).use { store ->
            assertNull(store.active()); store.recoverInterrupted()
            assertEquals(setOf("first", "last"), store.completed().map { it.id }.toSet())
        }
    }

    @Test fun deliveryDiscardsFramelessLegacyRowWithoutPreparingOrSending() = runBlocking {
        VideoVisitStore(app).use { store ->
            insertCompleted(store, VideoVisit("old", "wechat", "feed", 1000, 100, reason = VideoExitReason.INTERRUPTED))
            var prepared = 0; var sent = 0
            val result = VideoVisitDelivery(store, { 2000 }, { true }, { prepared++; Closeable {} },
                { _, _ -> sent++; null }).runOnce()
            assertEquals(0, prepared); assertEquals(0, sent)
            assertEquals(0, result.saved); assertEquals(1, result.discarded); assertEquals(0, result.failed)
            assertTrue(store.completed().isEmpty())
        }
    }

    @Test fun migrationCleansFramelessBacklogAcrossCursorWindows() {
        VideoVisitStore(app).use { store ->
            val db = store.writableDatabase
            db.beginTransaction()
            try {
                repeat(600) { index ->
                    insertCompleted(store, VideoVisit("old-$index", "douyin", "x".repeat(8192), 1000, 100,
                        firstImage = if (index == 599) image else null, reason = VideoExitReason.INTERRUPTED))
                }
                db.version = 3
                db.setTransactionSuccessful()
            } finally { db.endTransaction() }
        }
        VideoVisitStore(app).use { store ->
            assertEquals(listOf("old-599"), store.completed().map { it.id })
        }
    }

    @Test fun failedFirstFrameCheckpointKeepsOriginalInMemoryVisitForRetry() {
        VideoVisitStore(app).use { store ->
            val visit = store.enter("wechat", "feed", 100, 1000).active
            store.writableDatabase.execSQL("CREATE TRIGGER fail_active BEFORE INSERT ON active BEGIN SELECT RAISE(ABORT, 'disk failure'); END")
            assertTrue(runCatching { store.attachFrame(visit.id, visit.videoKey, VideoFrameRole.FIRST, image) }.isFailure)
            assertEquals(visit, store.active())
            store.writableDatabase.execSQL("DROP TRIGGER fail_active")
            assertTrue(store.attachFrame(visit.id, visit.videoKey, VideoFrameRole.FIRST, image))
            val done = store.finish(visit.id, 200, 1100, VideoExitReason.LOCKED)!!
            assertEquals(100L, done.durationMillis); assertEquals(image, done.firstImage)
        }
    }

    private fun insertCompleted(store: VideoVisitStore, row: VideoVisit) {
        store.writableDatabase.execSQL("INSERT INTO completed(id,entered_at,payload,platform) VALUES(?,?,?,?)",
            arrayOf<Any>(row.id, row.enteredAt, Json.encodeToString(row), row.platform))
    }
}
