package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import java.io.Closeable
import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PageCaptureDeliveryTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "page_capture_outbox.db").absolutePath)
    }
    private fun add(s: PageCaptureOutbox, value: String = "image") = s.enqueue("com.tencent.mm",
        PageFrame(PageDecision(PageKind.PAYMENT, "verified"), value.toByteArray(), 500, 1000), 1780000000000).id!!
    private fun ack(payload: String, discarded: Boolean? = null): String {
        val p = JSONObject(payload)
        return JSONObject().put("ok", true).put("id", p.getString("id")).put("sha256", p.getString("sha256"))
            .apply { discarded?.let { put("discarded", it) } }.toString()
    }
    private fun delivery(s: PageCaptureOutbox, allowed: (String) -> Boolean = { true },
        send: suspend (String) -> PageUploadResponse?) = PageCaptureDelivery(s, { 2000 }, allowed,
        { Closeable {} }, { Closeable {} }, { _, bytes -> send(bytes.toString(Charsets.UTF_8)) })

    @Test fun validReceiptDeletesOnlyMatchingImageAndKeepsDurableDedupe() = runBlocking {
        PageCaptureOutbox(app).use { s ->
            val id = add(s)
            val result = delivery(s) { payload ->
                val p = JSONObject(payload)
                assertEquals(id, p.getString("id")); assertEquals("com.tencent.mm", p.getString("package_name"))
                assertEquals("payment", p.getString("kind")); assertEquals("image/webp", p.getString("mime_type"))
                assertEquals(1780000000000L, p.getLong("captured_at")); assertFalse(p.has("device_id"))
                PageUploadResponse(200, ack(payload))
            }.runOnce()
            assertEquals(1, result.saved); assertTrue(s.pending().isEmpty()); assertNull(s.image(id))
        }
        PageCaptureOutbox(app).use { s ->
            assertEquals(PageWriteStatus.DUPLICATE, s.enqueue("com.tencent.mm",
                PageFrame(PageDecision(PageKind.PAYMENT, "verified"), "image".toByteArray(), 500, 1000), 1780000001000).status)
            assertTrue(s.pending().isEmpty())
        }
    }
    @Test fun invalidResponsesNeverDeleteAndBackoffSurvivesReopen() = runBlocking {
        for (bad in listOf("{}", "null", "x".repeat(4097))) {
            setup(); var sends = 0
            PageCaptureOutbox(app).use { s ->
                add(s)
                delivery(s) { sends++; PageUploadResponse(200, bad) }.runOnce()
                assertEquals(1, sends); assertEquals(1, s.pending().size)
                assertTrue(s.due(31999).isEmpty()); assertEquals(1, s.due(32000).size)
            }
            PageCaptureOutbox(app).use { s -> assertTrue(s.due(31999).isEmpty()); assertEquals(1, s.due(32000).size) }
        }
    }

    @Test fun mismatchedOrCoercedReceiptsAnd409KeepOriginal() = runBlocking {
        for (mode in 0..6) {
            setup()
            PageCaptureOutbox(app).use { s ->
                val id = add(s)
                delivery(s) { payload ->
                    val r = JSONObject(ack(payload))
                    when (mode) {
                        0 -> r.put("id", "other")
                        1 -> r.put("sha256", "f".repeat(64))
                        2 -> r.put("ok", "true")
                        3 -> r.put("discarded", "true")
                        4 -> r.put("error", "failure")
                    }
                    PageUploadResponse(if (mode == 5) 409 else 200, r.toString() + if (mode == 6) "garbage" else "")
                }.runOnce()
                assertArrayEquals("image".toByteArray(), s.image(id))
            }
        }
    }
    @Test fun discardedIsExplicitNotStored() = runBlocking {
        PageCaptureOutbox(app).use { s ->
            add(s)
            val result = delivery(s) { PageUploadResponse(200, ack(it, true)) }.runOnce()
            assertEquals(0, result.saved); assertEquals(1, result.discarded); assertTrue(s.pending().isEmpty())
        }
    }
    @Test fun permissionOrTargetInvalidatedDuringSendCannotAcknowledge() = runBlocking {
        PageCaptureOutbox(app).use { s ->
            val id = add(s); var current = true
            delivery(s, { current }) { current = false; PageUploadResponse(200, ack(it)) }.runOnce()
            assertNotNull(s.image(id))
        }
    }
    @Test fun preparationReleasedBeforeUploadPermitAndAtMostTwoRecords() = runBlocking {
        PageCaptureOutbox(app).use { s ->
            repeat(3) { add(s, "image$it") }; var preparing = false; var uploads = 0; var released = 0
            val d = PageCaptureDelivery(s, { 2000 }, { true },
                { preparing = true; Closeable { preparing = false } },
                { bytes -> assertFalse(preparing); assertTrue(bytes > 0); uploads++; Closeable { released++ } },
                { _, bytes -> PageUploadResponse(200, ack(bytes.toString(Charsets.UTF_8))) })
            assertEquals(2, d.runOnce().saved); assertEquals(2, uploads); assertEquals(2, released)
            assertEquals(1, s.pending().size)
        }
    }
    @Test fun pauseBeforeReadOrPermitDoesNotSendOrDelete() = runBlocking {
        PageCaptureOutbox(app).use { s ->
            add(s); var sends = 0
            val d = PageCaptureDelivery(s, { 2000 }, { true }, { null }, { Closeable {} }, { _, _ -> sends++; null })
            d.runOnce(); assertEquals(0, sends); assertEquals(1, s.due(2000).size)
            val noUpload = PageCaptureDelivery(s, { 2000 }, { true }, { Closeable {} }, { null }, { _, _ -> sends++; null })
            noUpload.runOnce(); assertEquals(0, sends); assertEquals(1, s.due(2000).size)
        }
    }
    @Test fun failingOldRecordDoesNotStarveNewerOnNextPass() = runBlocking {
        PageCaptureOutbox(app).use { s ->
            repeat(3) { add(s, "image$it") }
            assertEquals(2, delivery(s) { null }.runOnce().failed)
            assertEquals(1, delivery(s) { PageUploadResponse(200, ack(it)) }.runOnce().saved)
            assertEquals(2, s.pending().size)
        }
    }
    @Test fun expiredFailuresRotateBehindUnattemptedPagesAcrossMinuteSpacedPassesAndReopen() = runBlocking {
        val ids = PageCaptureOutbox(app).use { s ->
            (0..5).map { index ->
                s.enqueue("com.tencent.mm", PageFrame(PageDecision(PageKind.PAYMENT, "verified"),
                    "image$index".toByteArray(), 500, 1000), 1780000000000L + index).id!!
            }
        }
        var now = 2000L
        for (expected in listOf(ids.take(2), ids.drop(2).take(2), ids.drop(4), ids.take(2))) {
            PageCaptureOutbox(app).use { s ->
                val attempted = mutableListOf<String>()
                val d = PageCaptureDelivery(s, { now }, { true }, { Closeable {} }, { Closeable {} },
                    { row, _ -> attempted += row.id; null })
                assertEquals(2, d.runOnce().failed)
                assertEquals(expected, attempted)
                // 重试调度不改变只读队列的采集顺序，也不删除任何未确认图片。
                assertEquals(ids, s.pending().map { it.id })
                ids.forEach { assertNotNull(s.image(it)) }
            }
            now += 60_000 // 每轮开始时，上一轮失败的 30 秒退避已经到期。
        }
    }
    @Test fun acknowledgementMustRollbackWhenAuthorizationChanges() {
        PageCaptureOutbox(app).use { s ->
            val id = add(s); val row = s.pending().single(); var checks = 0
            assertFalse(s.acknowledge(row) { ++checks < 2 })
            assertNotNull(s.image(id)); assertEquals(id, add(s))
            assertEquals(0, s.readableDatabase.rawQuery("SELECT COUNT(*) FROM page_receipts", null).use { it.moveToFirst(); it.getInt(0) })
        }
    }

    @Test fun wallClockRollbackCannotStrandDeferredImage() = runBlocking {
        PageCaptureOutbox(app).use { s ->
            add(s); delivery(s) { null }.runOnce()
            assertEquals(1, s.due(-100_000).size)
        }
    }
    @Test fun schemaOneUpgradeKeepsOriginalPendingImage() {
        val file = File(app.noBackupFilesDir, "page_capture_outbox.db")
        android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE pages(id TEXT PRIMARY KEY,package_name TEXT NOT NULL,kind TEXT NOT NULL,captured_at INTEGER NOT NULL,width INTEGER NOT NULL,height INTEGER NOT NULL,sha256 TEXT NOT NULL,image BLOB NOT NULL,UNIQUE(package_name,kind,sha256))")
            db.execSQL("INSERT INTO pages VALUES('old','com.tencent.mm','PAYMENT',1000,500,1000,'hash',?)", arrayOf("old".toByteArray()))
            db.version = 1
        }
        PageCaptureOutbox(app).use { s ->
            assertEquals("old", s.pending().single().id); assertEquals(1, s.due(0).size)
            assertEquals(2, s.readableDatabase.version)
            assertEquals(3, s.readableDatabase.rawQuery("SELECT length(image) FROM pages", null).use { it.moveToFirst(); it.getInt(0) })
        }
    }
}
