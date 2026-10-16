package com.yuyan.imemodule.data.capture.page

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.collect.CollectionConsent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PageRecoveryDelayTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val now = 1_800_000_000_000L
    @Before fun setup() {
        app.deleteDatabase(File(app.noBackupFilesDir, "page_capture_outbox.db").absolutePath)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY, true).commit()
    }
    @Test fun recoveryRespectsRetryWithoutReadingImageAndFiltersDisabledPackages() {
        PageCaptureOutbox(app).use { outbox ->
            outbox.enqueue("com.tencent.mm", PageFrame(PageDecision(PageKind.PAYMENT, "verified"), "image".toByteArray(), 500, 1000), now)
            val row = outbox.pending().single()
            assertEquals(0L, outbox.nextRecoveryDelay(now, setOf("com.tencent.mm")))
            outbox.defer(row, now)
            assertEquals(30_000L, outbox.nextRecoveryDelay(now, setOf("com.tencent.mm")))
            assertEquals(20_000L, outbox.nextRecoveryDelay(now + 10_000, setOf("com.tencent.mm")))
            assertNull(outbox.nextRecoveryDelay(now, setOf("com.ss.android.ugc.aweme")))
            assertNull(outbox.nextRecoveryDelay(now, emptySet()))
            assertEquals(0L, outbox.nextRecoveryDelay(now - 1, setOf("com.tencent.mm")))
            assertArrayEquals("image".toByteArray(), outbox.image(row.id))
        }
    }
    @Test fun absentOutboxDoesNotCreateDatabaseForRecovery() {
        assertNull(PageCaptureSync.pendingRecoveryDelay(app, now))
        assertFalse(File(app.noBackupFilesDir, "page_capture_outbox.db").exists())
    }
}
