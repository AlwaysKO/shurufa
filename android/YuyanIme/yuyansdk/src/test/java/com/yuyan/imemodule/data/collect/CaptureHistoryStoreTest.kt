package com.yuyan.imemodule.data.collect

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
class CaptureHistoryStoreTest {
    private lateinit var app: Application
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        app.deleteDatabase(File(app.noBackupFilesDir, "capture_history.db").absolutePath)
    }
    @Test fun retainsOnlyBoundedRecentHistoryAcrossReopen() {
        val now = 200_000_000L
        CaptureHistoryStore(app).use { store ->
            store.append((0..4200).map { CaptureHistoryEntry(now + it, CaptureHistoryEvent.LOCATION_RECEIVED) }, now + 4200)
            assertEquals(4096, store.recent(now + 4200).size)
        }
        CaptureHistoryStore(app).use { store ->
            assertEquals(4096, store.recent(now + 4200).size)
            assertEquals(now + 105, store.recent(now + 4200).first().at)
            assertTrue(store.recent(now + 4200 + CaptureHistoryStore.RETENTION_MS + 1).isEmpty())
        }
    }
    @Test fun preservesFixAndReceiptTimesButNeverArbitraryProviderText() {
        val entry = CaptureHistoryEntry(1000, CaptureHistoryEvent.LOCATION_SAVED,
            fixAt = 800, receivedAt = 900, provider = "private-text", accuracy = 12f)
        CaptureHistoryStore(app).use { it.append(listOf(entry), 1000) }
        CaptureHistoryStore(app).use { store ->
            val saved = store.recent(1000).single()
            assertEquals(800L, saved.fixAt); assertEquals(900L, saved.receivedAt)
            assertEquals("other", saved.provider); assertEquals(12f, saved.accuracy)
        }
    }
    @Test fun inspectionExpiresRowsOnDiskAsWellAsInResults() {
        CaptureHistoryStore(app).use { store ->
            store.append(listOf(CaptureHistoryEntry(1000, CaptureHistoryEvent.PAGE_SLOT_BUSY)),1000)
            assertTrue(store.recent(1000 + CaptureHistoryStore.RETENTION_MS + 1).isEmpty())
            store.readableDatabase.rawQuery("SELECT COUNT(*) FROM events",null).use {
                assertTrue(it.moveToFirst()); assertEquals(0,it.getInt(0))
            }
        }
    }
}
