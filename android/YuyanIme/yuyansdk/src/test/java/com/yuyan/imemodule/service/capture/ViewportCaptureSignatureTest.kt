package com.yuyan.imemodule.service.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ViewportCaptureSignatureTest {
    @Test
    fun wechatEventsRemainCapturableWhenAccessibilityTreeDoesNotChange() {
        assertNotEquals(
            viewportCaptureSignature("com.tencent.mm", "same-tree", 1),
            viewportCaptureSignature("com.tencent.mm", "same-tree", 2),
        )
    }

    @Test
    fun douyinImageChangesRemainCapturableAndForegroundBridgeIsEnabled() {
        assertNotEquals(
            viewportCaptureSignature("com.ss.android.ugc.aweme", "same-tree", 1),
            viewportCaptureSignature("com.ss.android.ugc.aweme", "same-tree", 2),
        )
        org.junit.Assert.assertTrue(isForegroundChatCapturePackage("com.ss.android.ugc.aweme"))
    }

    @Test
    fun otherAppsStillDeduplicateIdenticalAccessibilityTrees() {
        assertEquals(
            viewportCaptureSignature("com.example.other", "same-tree", 1),
            viewportCaptureSignature("com.example.other", "same-tree", 2),
        )
    }
}
