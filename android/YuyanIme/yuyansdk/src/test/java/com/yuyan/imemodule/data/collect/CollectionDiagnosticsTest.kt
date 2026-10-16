package com.yuyan.imemodule.data.collect

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CollectionDiagnosticsTest {
    private lateinit var app: Application
    private fun history() = File(app.noBackupFilesDir, "capture_history.db")

    @Before fun setup() = runBlocking {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true).commit()
        CollectionDiagnostics.end(app)
    }
    @After fun cleanup() = runBlocking { CollectionDiagnostics.end(app) }

    @Test fun defaultOffDoesNotCreateHistoryOrChatSnapshots() = runBlocking {
        CaptureHistory.record(app, CaptureHistoryEvent.LOCATION_RECEIVED)
        ChatCaptureDiagnostics.record(app, "wechat", "screenshot", "ready")
        CollectionDiagnostics.clearIfInactive(app)
        assertFalse(CollectionDiagnostics.enabled(app))
        assertFalse(history().exists())
        assertTrue(ChatCaptureDiagnostics.latest(app).isEmpty())
    }

    @Test fun endingDeletesOnlyDiagnosticFilesAndClearsMemory() = runBlocking {
        CollectionDiagnostics.begin(app)
        val token = requireNotNull(CollectionDiagnostics.token(app))
        assertTrue(CollectionDiagnostics.writeIfCurrent(app, token) {
            CaptureHistoryStore(app).use { it.append(listOf(CaptureHistoryEntry(System.currentTimeMillis(), CaptureHistoryEvent.CHAT_FRAME_READY)), System.currentTimeMillis()) }
        })
        app.getSharedPreferences("chat_capture_diagnostics_v1", 0).edit().putString("latest", "old-diagnostic").commit()
        app.getSharedPreferences("douyin_capture_diagnostics", 0).edit().putString("status", "old-diagnostic").commit()
        val business = File(app.noBackupFilesDir, "business-pending-sentinel").apply { writeText("must-survive") }
        try {
            CollectionDiagnostics.end(app)
            assertFalse(history().exists())
            assertFalse(app.getSharedPreferences("chat_capture_diagnostics_v1", 0).contains("latest"))
            assertFalse(app.getSharedPreferences("douyin_capture_diagnostics", 0).contains("status"))
            assertTrue(ChatCaptureDiagnostics.latest(app).isEmpty())
            assertEquals("must-survive", business.readText())
        } finally { business.delete() }
    }

    @Test fun expirationRejectsNewWritesAndClearsHistory() = runBlocking {
        CollectionDiagnostics.begin(app, durationMs = 1000)
        val token = requireNotNull(CollectionDiagnostics.token(app))
        CollectionDiagnostics.writeIfCurrent(app, token) { history().writeText("temporary-diagnostic") }
        ShadowSystemClock.advanceBy(Duration.ofMillis(1001))
        assertFalse(CollectionDiagnostics.enabled(app))
        assertFalse(CollectionDiagnostics.writeIfCurrent(app, token) { history().writeText("late") })
        CollectionDiagnostics.clearIfInactive(app)
        assertFalse(history().exists())
    }

    @Test fun oldSessionCannotRecreateFilesInANewSession() = runBlocking {
        CollectionDiagnostics.begin(app)
        val old = requireNotNull(CollectionDiagnostics.token(app))
        CollectionDiagnostics.end(app)
        CollectionDiagnostics.begin(app)
        assertNotEquals(old, CollectionDiagnostics.token(app))
        assertFalse(CollectionDiagnostics.writeIfCurrent(app, old) { history().writeText("old-session") })
        assertFalse(history().exists())
    }

    @Test fun consentWithdrawalStopsAndClearsDiagnostics() = runBlocking {
        CollectionDiagnostics.begin(app)
        val token = requireNotNull(CollectionDiagnostics.token(app))
        CollectionDiagnostics.writeIfCurrent(app, token) { history().writeText("temporary-diagnostic") }
        CollectionConsent.setEnabled(app, false)
        assertFalse(CollectionDiagnostics.enabled(app))
        CollectionDiagnostics.clearIfInactive(app)
        assertFalse(history().exists())
    }

    @Test fun leaseExpiresOnRebootOrClockRollback() {
        val lease = DiagnosticLease(100_000, 200_000, 7, 60_000)
        assertTrue(lease.active(110_000, 210_000, 7))
        assertFalse(lease.active(110_000, 210_000, 8))
        assertFalse(lease.active(99_999, 210_000, 7))
        assertFalse(lease.active(110_000, 199_999, 7))
        assertFalse(lease.active(160_000, 260_000, 7))
    }

    @Test fun leavingSettingsCannotCancelPersistentCloseCleanup() = runBlocking {
        CollectionDiagnostics.begin(app)
        val token = requireNotNull(CollectionDiagnostics.token(app))
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val write = async(Dispatchers.IO) {
            CollectionDiagnostics.writeIfCurrent(app, token) {
                history().writeText("in-flight-diagnostic")
                started.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        val closing = launch(Dispatchers.IO) { CollectionDiagnostics.end(app) }
        try {
            withTimeout(5000) { while (CollectionDiagnostics.enabled(app)) yield() }
            closing.cancel()
        } finally { release.countDown() }
        write.await(); closing.join()
        assertFalse(history().exists())
        assertFalse(app.getSharedPreferences("collection_diagnostic_session", 0).contains("duration"))
    }
}
