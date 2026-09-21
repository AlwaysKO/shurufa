package com.yuyan.imemodule.data.capture.adapter

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class DouyinCaptureDiagnosticsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("douyin_capture_diagnostics", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
        CollectionConsent.setEnabled(context, true)
    }

    @Test fun storesOnlyLatestEnumAndTimestampAcrossStoreInstances() {
        val store = DouyinCaptureDiagnostics(context)
        store.record(DouyinPageStatus.MATCHED_STRUCTURE, 1_000L)
        store.record(DouyinPageStatus.AMBIGUOUS_TITLE, 2_000L)
        assertEquals(DouyinDiagnosticSnapshot(DouyinPageStatus.AMBIGUOUS_TITLE, 2_000L), DouyinCaptureDiagnostics(context).read())
        assertEquals(setOf("status", "observed_at"), prefs.all.keys)
        assertEquals("AMBIGUOUS_TITLE", prefs.getString("status", null))
    }

    @Test fun rateLimitsUnchangedStatusButUpdatesChangedStatusImmediately() {
        val store = DouyinCaptureDiagnostics(context)
        store.record(DouyinPageStatus.MATCHED_STRUCTURE, 1_000L)
        store.record(DouyinPageStatus.MATCHED_STRUCTURE, 2_000L)
        assertEquals(1_000L, store.read()!!.observedAt)
        store.record(DouyinPageStatus.MATCHED_STRUCTURE, 61_000L)
        assertEquals(61_000L, store.read()!!.observedAt)
        store.record(DouyinPageStatus.NON_CHAT_PAGE, 61_001L)
        assertEquals(DouyinPageStatus.NON_CHAT_PAGE, store.read()!!.status)
    }

    @Test fun missingOrInvalidStatusIsUnknownNotSuccessfulCapture() {
        assertNull(DouyinCaptureDiagnostics(context).read())
        prefs.edit().putString("status", "not-a-status").putLong("observed_at", 10L).commit()
        assertNull(DouyinCaptureDiagnostics(context).read())
    }

    @Test fun disabledConsentDoesNotRecordNewDiagnostics() {
        CollectionConsent.setEnabled(context, false)
        DouyinCaptureDiagnostics(context).record(DouyinPageStatus.MATCHED_STRUCTURE, 1_000L)
        assertTrue(prefs.all.isEmpty())
    }
}
