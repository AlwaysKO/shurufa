package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DeliverablePendingKindsTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val hash = "a".repeat(64)
    private val online = "https://example.test"
    private val other = "https://other.test"

    private fun fixture(block: (LocalInputStore) -> Unit) {
        val store = LocalInputStore(app, "deliverable-kinds.db")
        try { block(store) } finally { store.close() }
    }
    private fun asset() = PendingReport("image", "chat_asset", """{"sha256":"$hash","file_base64":"AA=="}""")
    private fun text(id: String, dependency: Boolean) = PendingReport(id, "chat_messages",
        if (dependency) """{"messages":[{"asset_sha256":["$hash"]}]}""" else """{"messages":[{"text":"fixture"}]}""")

    @Test fun imageDependentTextIsBlockedPerTargetUntilTheImageReceipt() = fixture { store ->
        store.enqueueReport(asset(), listOf(online, other))
        store.enqueueReport(text("dependent", true), listOf(online, other))
        assertEquals(setOf("chat_asset"), store.deliverablePendingKinds(listOf(online)))
        store.acknowledgeReports(online, listOf("image"))
        assertEquals(setOf("chat_messages"), store.deliverablePendingKinds(listOf(online)))
        assertEquals(setOf("chat_asset"), store.deliverablePendingKinds(listOf(other)))
        assertEquals(setOf("chat_asset", "chat_messages"), store.pendingKinds())
    }

    @Test fun independentTextAndOrdinaryReportsAreNotBlockedByAnImage() = fixture { store ->
        store.enqueueReport(asset(), listOf(online))
        store.enqueueReport(text("dependent", true), listOf(online))
        store.enqueueReport(text("independent", false), listOf(online))
        store.enqueueReport(PendingReport("location", "location", "{}"), listOf(online))
        assertEquals(setOf("chat_asset", "chat_messages", "location"), store.deliverablePendingKinds(listOf(online)))
        assertTrue(store.deliverablePendingKinds(emptyList()).isEmpty())
    }

    @Test fun legacyTextGetsBoundedIndexingWhileUnknownImagePayloadIsNeverIndexed() = fixture { store ->
        store.enqueueReport(asset(), listOf(online))
        store.enqueueReport(text("dependent", true), listOf(online))
        store.enqueueReport(text("independent", false), listOf(other))
        store.writableDatabase.delete("report_image_meta", null, null)
        store.writableDatabase.delete("report_image_dependency", null, null)
        assertEquals(setOf("chat_asset"), store.deliverablePendingKinds(listOf(online)))
        assertEquals(setOf("chat_messages"), store.deliverablePendingKinds(listOf(other)))
        store.readableDatabase.rawQuery("SELECT report_id FROM report_image_meta ORDER BY report_id", null).use { rows ->
            val ids = buildList { while (rows.moveToNext()) add(rows.getString(0)) }
            assertEquals(listOf("dependent", "independent"), ids)
        }
    }
    @Test fun legacyMigrationIsBoundedAndLaterQueriesContinueRemainingMessages() = fixture { store ->
        repeat(70) { store.enqueueReport(text("legacy-$it", false), listOf(online)) }
        store.writableDatabase.delete("report_image_meta", null, null)
        assertEquals(setOf("chat_messages"), store.deliverablePendingKinds(listOf(online)))
        fun indexed() = store.readableDatabase.rawQuery("SELECT COUNT(*) FROM report_image_meta", null).use {
            it.moveToFirst(); it.getInt(0)
        }
        assertEquals(64, indexed())
        assertEquals(setOf("chat_messages"), store.deliverablePendingKinds(listOf(online)))
        assertEquals(70, indexed())
    }

}
