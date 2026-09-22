package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.collect.CollectionConsent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CaptureTracePrivacyTest {
    @Test fun supportedEventLogsMetadataWithoutItsText() {
        ShadowLog.clear()
        val service = Robolectric.buildService(PassiveChatAccessibilityService::class.java).create().get()
        CollectionConsent.setEnabled(service, true)
        @Suppress("DEPRECATION")
        val event = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED).apply {
            packageName = "com.tencent.mm"
            className = "android.widget.EditText"
            text.add("private-body-sentinel 转文字")
            contentDescription = "private-description-sentinel"
        }
        try {
            service.onAccessibilityEvent(event)
            val lines = ShadowLog.getLogsForTag("ChatCaptureTrace").map { it.msg }
            assertTrue(lines.any { it.contains("stage=EVENT") })
            assertFalse(lines.joinToString().contains("private-"))
            assertFalse(lines.joinToString().contains("转文字"))
        } finally {
            CollectionConsent.setEnabled(service, false)
            service.onDestroy()
            @Suppress("DEPRECATION")
            event.recycle()
        }
    }
}
