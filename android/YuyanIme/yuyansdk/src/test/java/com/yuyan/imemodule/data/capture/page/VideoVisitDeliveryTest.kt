package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.Closeable

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class VideoVisitDeliveryTest {
    private lateinit var app: Application
    private var now = 1_791_504_000_000L
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "video_visits.db").absolutePath)
        app.deleteDatabase(File(app.noBackupFilesDir, "page_capture_outbox.db").absolutePath)
    }
    private fun finished(s: VideoVisitStore): VideoVisit {
        val v = s.enter("wechat", "private-local-key", 100, 1_791_503_900_000L).active
        return s.finish(v.id, 200, 1_791_503_900_100L, VideoExitReason.BACKGROUND)!!
    }
    private fun ack(v: VideoVisit, discard: Boolean = false) = PageUploadResponse(200,
        JSONObject().put("ok", true).put("record", videoVisitPayload(v)).apply { if(discard) put("discarded", true) }.toString())
    @Test fun imageDependencyConflictRecoversAfterPageAckAndStoreReopen() = runBlocking {
        val remoteImages = mutableSetOf<String>()
        lateinit var visit: VideoVisit
        PageCaptureOutbox(app).use { pages ->
            val image = pages.enqueue("com.tencent.mm", PageFrame(
                PageDecision(PageKind.MEDIA_FEED, "verified"), "feed-frame".toByteArray(), 500, 1000), now).id!!
            VideoVisitStore(app).use { visits ->
                val active = visits.enter("wechat", "feed-observation:$image", 100, now,
                    image, VideoObservationKind.UNCONFIRMED_FEED).active
                visit = visits.finish(active.id, 200, now + 100, VideoExitReason.PAGE_CHANGED)!!
                val result = VideoVisitDelivery(visits, { now }, { true }, { Closeable {} }, { row, _ ->
                    assertFalse(remoteImages.contains(row.firstImage))
                    PageUploadResponse(409, "{\"error\":\"missing_page_captures\"}")
                }).runOnce()
                assertEquals(1, result.failed)
                assertEquals(visit, visits.completed().single())
                assertNotNull(pages.image(image))
            }
            val result = PageCaptureDelivery(pages, { now }, { true }, { Closeable {} }, { Closeable {} }, { row, _ ->
                remoteImages.add(row.id)
                PageUploadResponse(200, JSONObject().put("ok", true).put("id", row.id)
                    .put("sha256", row.sha256).toString())
            }).runOnce()
            assertEquals(1, result.saved)
            assertNull(pages.image(image))
        }
        VideoVisitStore(app).use { visits ->
            assertEquals(visit, visits.completed().single())
            assertTrue(visits.due(now).isEmpty())
            now += 30_000
            val result = VideoVisitDelivery(visits, { now }, { true }, { Closeable {} }, { row, bytes ->
                assertTrue(remoteImages.contains(row.firstImage))
                assertEquals("unconfirmed_feed", JSONObject(String(bytes, Charsets.UTF_8)).getString("observation_kind"))
                assertEquals(visit, row)
                ack(row)
            }).runOnce()
            assertEquals(1, result.saved)
            assertTrue(visits.completed().isEmpty())
        }
    }
    @Test fun disabledPlatformBacklogDoesNotHideEnabledPlatformBeyondTwentyRows() = runBlocking {
        VideoVisitStore(app).use { s ->
            repeat(21) { index ->
                val v = s.enter("douyin", "old-$index", 100, 1_791_503_000_000L + index).active
                s.finish(v.id, 200, 1_791_503_000_100L + index, VideoExitReason.BACKGROUND)
            }
            val enabled = finished(s)
            var sent: String? = null
            val r = VideoVisitDelivery(s, { now }, { it == "wechat" }, { Closeable {} }, { v, _ -> sent = v.id; ack(v) }).runOnce()
            assertEquals(enabled.id, sent); assertEquals(1, r.saved); assertEquals(21, s.completed().size)
        }
    }
    @Test fun repeatedFailuresDoNotStarveNeverAttemptedRecordsAfterBackoffExpires() = runBlocking {
        VideoVisitStore(app).use { s ->
            repeat(4) { finished(s) }
            val attempts = mutableListOf<String>()
            val delivery = VideoVisitDelivery(s, { now }, { true }, { Closeable {} }, { v, _ ->
                attempts.add(v.id); PageUploadResponse(409, "{}")
            })
            delivery.runOnce(); now += 60_000; delivery.runOnce()
            assertEquals(4, attempts.size); assertEquals(4, attempts.toSet().size)
            assertEquals(4, s.completed().size)
        }
    }

    @Test fun exactAckDeletesOnlyItsCompletedRecordAndBatchIsBounded() = runBlocking {
        VideoVisitStore(app).use { s ->
            repeat(3) { finished(s) }
            val active = s.enter("douyin", "still-active", 300, 1_791_503_900_200L).active
            val r = VideoVisitDelivery(s, { now }, { true }, { Closeable {} }, { v, bytes ->
                val payload = JSONObject(String(bytes, Charsets.UTF_8))
                assertFalse(payload.has("videoKey")); assertFalse(payload.has("enteredElapsed"))
                ack(v)
            }).runOnce()
            assertEquals(2, r.saved); assertEquals(1, s.completed().size); assertEquals(active, s.active())
        }
    }
    @Test fun failurePersistsBackoffAndWallClockRollbackDoesNotStrandRecord() = runBlocking {
        VideoVisitStore(app).use { s ->
            val v = finished(s)
            val r = VideoVisitDelivery(s, { now }, { true }, { Closeable {} }, { _, _ -> PageUploadResponse(409, "{}") }).runOnce()
            assertEquals(1, r.failed); assertTrue(s.due(now).isEmpty())
            assertEquals(v, s.due(now + 30_000).single())
            assertEquals(v, s.due(now - 1).single())
        }
        VideoVisitStore(app).use { assertTrue(it.due(now).isEmpty()); assertEquals(1, it.completed().size) }
    }
    @Test fun wrongPayloadOrMalformedReceiptCannotDelete() {
        VideoVisitStore(app).use { s ->
            val v = finished(s)
            assertNull(validateVideoVisitReceipt(ack(v.copy(durationMillis = 101)), v))
            assertNull(validateVideoVisitReceipt(ack(v).copy(body = ack(v).body + "garbage"), v))
            assertNull(validateVideoVisitReceipt(PageUploadResponse(200, "{\"ok\":true,\"record\":{\"id\":\"${v.id}\"}}"), v))
            assertNull(validateVideoVisitReceipt(ack(v).copy(status = 409), v))
            assertEquals(false, validateVideoVisitReceipt(ack(v), v))
            assertEquals(true, validateVideoVisitReceipt(ack(v, true), v))
        }
    }
    @Test fun consentRevocationDuringAckTransactionRollsBack() {
        VideoVisitStore(app).use { s ->
            val v = finished(s); var checks = 0
            assertFalse(s.acknowledge(v) { ++checks == 1 })
            assertEquals(v, s.completed().single())
            assertFalse(s.acknowledge(v.copy(durationMillis = 0)) { true })
            assertEquals(v, s.completed().single())
        }
    }
    @Test fun scopeChangedDuringSendKeepsRecord() = runBlocking {
        VideoVisitStore(app).use { s ->
            val v = finished(s); var allowed = true
            VideoVisitDelivery(s, { now }, { allowed }, { Closeable {} }, { _, _ -> allowed = false; ack(v) }).runOnce()
            assertEquals(v, s.completed().single())
        }
    }
    @Test fun interruptedPayloadKeepsUnknownTimeNull() {
        VideoVisitStore(app).use { s ->
            s.enter("douyin", "private", 100, 1_791_503_900_000L); s.recoverInterrupted()
            val p = videoVisitPayload(s.completed().single())
            assertTrue(p.isNull("ended_at")); assertTrue(p.isNull("duration_ms"))
            assertEquals(false, p.getBoolean("complete")); assertEquals("interrupted", p.getString("exit_reason"))
            assertEquals("confirmed_video", p.getString("observation_kind"))
            assertEquals(10, p.length())
        }
    }
}
