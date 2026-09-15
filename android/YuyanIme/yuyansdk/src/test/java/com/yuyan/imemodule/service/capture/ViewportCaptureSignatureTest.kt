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
    fun otherAppsStillDeduplicateIdenticalAccessibilityTrees() {
        assertEquals(
            viewportCaptureSignature("com.tencent.mobileqq", "same-tree", 1),
            viewportCaptureSignature("com.tencent.mobileqq", "same-tree", 2),
        )
    }
}
