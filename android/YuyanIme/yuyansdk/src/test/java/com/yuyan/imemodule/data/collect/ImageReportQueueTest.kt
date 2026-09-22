package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ImageReportQueueTest {
    private fun fixture(block: (LocalInputStore) -> Unit) {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "image-queue-${UUID.randomUUID()}.db"
        val store = LocalInputStore(ctx, name)
        try { block(store) } finally { store.close(); ctx.deleteDatabase(name) }
    }
    private fun asset(id: String, hash: String, size: Int = 100) =
        PendingReport(id, "chat_asset", "{\"sha256\":\"$hash\",\"file_base64\":\"${"A".repeat(size)}\"}")
    private fun message(id: String, hash: String) = PendingReport(id, "chat_messages",
        "{\"messages\":[{\"asset_sha256\":[\"$hash\"]}]}")

    @Test fun pausedImagesDoNotBlockOtherReportsOrLoadImagePayloads() = fixture { store ->
        store.enqueueReport(asset("a", "a".repeat(64)), listOf("local"))
        store.enqueueReport(PendingReport("text", "personal_choice", "{}"), listOf("local"))
        assertEquals(listOf("text"), store.pendingReports("local", maxImageBytes = { 0 }).map { it.id })
        assertTrue(store.pendingReports("local").any { it.id == "a" })
    }

    @Test fun largeImageDoesNotStarveSmallImageOnMobileAndRemainsForWifi() = fixture { store ->
        store.enqueueReport(asset("large", "a".repeat(64), 1_100_000), listOf("local"))
        store.enqueueReport(asset("small", "b".repeat(64)), listOf("local"))
        assertEquals(listOf("small"), store.pendingReports("local", maxImageBytes = { 1_048_576 }).map { it.id })
        assertEquals("large", store.pendingReports("local").first().id)
    }

    @Test fun readyMessagesPrecedeUnrelatedHistoricalImagesAndAreTargetSpecific() = fixture { store ->
        val hash = "a".repeat(64)
        store.enqueueReport(asset("required", hash), listOf("local", "online"))
        store.enqueueReport(asset("unrelated", "b".repeat(64)), listOf("local", "online"))
        store.enqueueReport(message("chat", hash), listOf("local", "online"))
        assertFalse(store.pendingReports("local").any { it.id == "chat" })
        store.acknowledgeReports("local", listOf("required"))
        assertEquals("chat", store.pendingReports("local").first().id)
        assertFalse(store.pendingReports("online").any { it.id == "chat" })
    }

    @Test fun oldUnindexedReportsAreIndexedWithoutLosingDependencies() = fixture { store ->
        val hash = "a".repeat(64)
        val db = store.writableDatabase
        for (r in listOf(asset("old-a", hash), message("old-m", hash))) {
            db.execSQL("INSERT INTO pending_report(id,kind,payload) VALUES(?,?,?)", arrayOf(r.id, r.kind, r.payload))
            db.execSQL("INSERT INTO report_target(report_id,target) VALUES(?,?)", arrayOf(r.id, "local"))
        }
        assertEquals(listOf("old-a"), store.pendingReports("local").map { it.id })
        store.acknowledgeReports("local", listOf("old-a"))
        assertEquals(listOf("old-m"), store.pendingReports("local", maxImageBytes = { 0 }).map { it.id })
    }

    @Test fun sharedBudgetIsAppliedBeforeLoadingSecondImage() = fixture { store ->
        store.enqueueReport(asset("a", "a".repeat(64), 100), listOf("local"))
        store.enqueueReport(asset("b", "b".repeat(64), 100), listOf("local"))
        assertEquals(1, store.pendingReports("local", maxImageBytes = { 250 }).size)
    }

    @Test fun versionNineQueueIsMigratedWithoutRemovingReports() {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val name = "image-upgrade-${UUID.randomUUID()}.db"
        val hash = "a".repeat(64)
        LocalInputStore(ctx, name).use { store ->
            store.enqueueReport(asset("a", hash), listOf("local"))
            store.enqueueReport(message("m", hash), listOf("local"))
            store.writableDatabase.apply {
                execSQL("DROP TRIGGER report_image_cleanup")
                execSQL("DROP TABLE report_image_meta")
                execSQL("DROP TABLE report_image_dependency")
                version = 9
            }
        }
        try { LocalInputStore(ctx, name).use { store ->
            assertEquals(listOf("a"), store.pendingReports("local").map { it.id })
            store.acknowledgeReports("local", listOf("a"))
            assertEquals(listOf("m"), store.pendingReports("local").map { it.id })
        } } finally { ctx.deleteDatabase(name) }
    }

    @Test fun derivedMetadataIsRemovedOnlyWhenAllTargetsAcknowledge() = fixture { store ->
        store.enqueueReport(message("m", "a".repeat(64)), listOf("local", "online"))
        fun count(table: String) = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        store.acknowledgeReports("local", listOf("m"))
        assertEquals(1, count("report_image_dependency"))
        store.acknowledgeReports("online", listOf("m"))
        assertEquals(0, count("report_image_meta"))
        assertEquals(0, count("report_image_dependency"))
    }

    @Test fun budgetIsRecheckedBetweenImageReads() = fixture { store ->
        store.enqueueReport(asset("a", "a".repeat(64)), listOf("local"))
        store.enqueueReport(asset("b", "b".repeat(64)), listOf("local"))
        var checks = 0
        val reports = store.pendingReports("local", maxImageBytes = {
            checks++; if (checks <= 2) Long.MAX_VALUE else 0
        })
        assertEquals(listOf("a"), reports.map { it.id })
        assertEquals(2, store.pendingReports("local").size)
    }
}
