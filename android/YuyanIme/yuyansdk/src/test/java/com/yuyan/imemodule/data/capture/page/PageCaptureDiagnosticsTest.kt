package com.yuyan.imemodule.data.capture.page

import org.junit.Assert.*
import org.junit.Test

class PageCaptureDiagnosticsTest {
    @Test fun onlyKnownReasonEnumsAreReported() {
        assertEquals("interval_limited", PageCaptureDiagnostics.captureStatus("budget_interval"))
        assertEquals("budget_limited", PageCaptureDiagnostics.captureStatus("budget_day_limit"))
        assertEquals("duplicate", PageCaptureDiagnostics.captureStatus("duplicate"))
        assertEquals("page_uncovered", PageCaptureDiagnostics.captureStatus("window_unconfirmed"))
        assertEquals("failed", PageCaptureDiagnostics.captureStatus("capture_or_storage_failed"))
        assertNull(PageCaptureDiagnostics.captureStatus("budget_allowed"))
        assertNull(PageCaptureDiagnostics.captureStatus("budget_or_scope_rejected"))
        assertNull(PageCaptureDiagnostics.captureStatus("private message"))
    }
}
