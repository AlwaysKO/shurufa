package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent
import org.junit.Assert.*
import org.junit.Test

class WechatScreenshotBoundaryTest {
    @Test fun navigationAndLeavingWechatInvalidatePendingRecognition() {
        assertTrue(shouldResetWechatScreenshotIdentity("com.tencent.mm", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, ""))
        assertTrue(shouldResetWechatScreenshotIdentity("com.tencent.mm", AccessibilityEvent.TYPE_VIEW_CLICKED, "返回"))
        assertTrue(shouldResetWechatScreenshotIdentity("com.tencent.mm", AccessibilityEvent.TYPE_VIEW_CLICKED, "17:20"))
        assertTrue(shouldResetWechatScreenshotIdentity("com.example.other", AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, ""))
    }
    @Test fun sendingAndNormalContentEventsKeepContinuousTitleEvidence() {
        assertFalse(shouldResetWechatScreenshotIdentity("com.tencent.mm", AccessibilityEvent.TYPE_VIEW_CLICKED, "发送"))
        assertFalse(shouldResetWechatScreenshotIdentity("com.tencent.mm", AccessibilityEvent.TYPE_VIEW_CLICKED, "转文字"))
        assertFalse(shouldResetWechatScreenshotIdentity("com.tencent.mm", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, ""))
        assertFalse(shouldResetWechatScreenshotIdentity("com.example.keyboard", AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED, ""))
    }
}
