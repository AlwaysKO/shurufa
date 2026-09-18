package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.*
import org.junit.Test

class ChatConfirmationRetryTest {
    @Test fun `same viewport confirmation probe is not swallowed as a duplicate event`() {
        for (app in listOf("com.tencent.mm", "com.ss.android.ugc.aweme")) {
            val first = viewportCaptureSignature(app, "same-tree", 5, 0)
            val retry = viewportCaptureSignature(app, "same-tree", 5, 1)
            assertNotEquals(first, retry)
            assertEquals(retry, viewportCaptureSignature(app, "same-tree", 5, 1))
        }
    }
    @Test fun `focusing chat input does not clear identity while contact navigation still does`() {
        for (app in listOf("com.tencent.mm", "com.ss.android.ugc.aweme")) {
            assertFalse(shouldResetScreenshotIdentity(app, AccessibilityEvent.TYPE_VIEW_CLICKED, "", "android.widget.EditText"))
            assertTrue(shouldResetScreenshotIdentity(app, AccessibilityEvent.TYPE_VIEW_CLICKED, "联系人", "android.widget.TextView"))
        }
        assertTrue(shouldResetScreenshotIdentity("com.tencent.mobileqq", AccessibilityEvent.TYPE_VIEW_CLICKED, "", "android.widget.EditText"))
    }
}
