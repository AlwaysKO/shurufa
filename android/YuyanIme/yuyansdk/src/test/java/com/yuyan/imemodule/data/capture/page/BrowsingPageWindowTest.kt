package com.yuyan.imemodule.data.capture.page

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class BrowsingPageWindowTest {
    class Service : AccessibilityService() {
        override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
        override fun onInterrupt() = Unit
    }
    private fun window(pkg: String, id: Int = 7, type: Int = AccessibilityWindowInfo.TYPE_APPLICATION,
                       active: Boolean = true): AccessibilityWindowInfo {
        val root = AccessibilityNodeInfo.obtain().apply { packageName = pkg }
        return AccessibilityWindowInfo.obtain().also { w -> shadowOf(w).apply {
            setRoot(root); setId(id); setActive(active); setType(type); setBoundsInScreen(Rect(10, 20, 510, 1020))
        } }
    }
    @Test fun videoOverlayDetectionDoesNotRequireAnEventWindowId() {
        val appWindow = window("com.tencent.mm", active = false)
        val overlay = window("android", 9, AccessibilityWindowInfo.TYPE_SYSTEM)
        assertTrue(hasVideoWindowOverlay(listOf(appWindow, overlay), -1))
        assertTrue(hasVideoWindowOverlay(listOf(appWindow, overlay), 9))
        assertFalse(hasVideoWindowOverlay(listOf(window("com.tencent.mm")), -1))
    }

    @Test fun emptyTreeUsesVerifiedWindowBoundsNotZeroRootBounds() {
        val service = Robolectric.buildService(Service::class.java).create().get()
        shadowOf(service).setWindows(listOf(window("com.tencent.mm")))
        val page = BrowsePageToken("com.tencent.mm", 7, 1)
        val read = readBrowsingPageSnapshot(service, page)!!
        assertEquals(page, read.page)
        assertEquals(com.yuyan.imemodule.data.capture.ui.IntRect(10, 20, 510, 1020), read.bounds)
    }
    @Test fun wrongPackageOrWindowAndNonApplicationEvidenceAreRejected() {
        for ((pkg, id, type) in listOf(
            Triple("com.taobao.taobao", 7, AccessibilityWindowInfo.TYPE_APPLICATION),
            Triple("com.tencent.mm", 8, AccessibilityWindowInfo.TYPE_APPLICATION),
            Triple("com.tencent.mm", 7, AccessibilityWindowInfo.TYPE_SYSTEM),
        )) {
            val service = Robolectric.buildService(Service::class.java).create().get()
            shadowOf(service).setWindows(listOf(window(pkg, id, type)))
            assertNull(readBrowsingPageSnapshot(service, BrowsePageToken("com.tencent.mm", 7, 1)))
        }
    }
    @Test fun foregroundSystemOverlayCannotBeTreatedAsUnderlyingPage() {
        val service = Robolectric.buildService(Service::class.java).create().get()
        shadowOf(service).setWindows(listOf(window("com.tencent.mm", active = false),
            window("android", 9, AccessibilityWindowInfo.TYPE_SYSTEM)))
        assertNull(readBrowsingPageSnapshot(service, BrowsePageToken("com.tencent.mm", 7, 1)))
    }
    @Test fun unknownPackageWindowEventCanResolveOnlyAnActiveSupportedApp() {
        val service = Robolectric.buildService(Service::class.java).create().get()
        shadowOf(service).setWindows(listOf(window("com.tencent.mm")))
        assertEquals("com.tencent.mm" to 7, readActiveBrowsingWindow(service))
        shadowOf(service).setWindows(listOf(window("com.taobao.taobao")))
        assertNull(readActiveBrowsingWindow(service))
    }
    @Test fun knownVoiceChatIsExcludedButLegacyListIsNotAChat() {
        fun node(id: String?, text: String, b: com.yuyan.imemodule.data.capture.ui.IntRect) =
            com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot(id, "android.widget.TextView", text, null, b, emptyList())
        val root = com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot(null, "root", null, null,
            com.yuyan.imemodule.data.capture.ui.IntRect(0, 0, 1080, 1920), listOf(
                node("com.tencent.mm:id/chatting_title", "测试联系人", com.yuyan.imemodule.data.capture.ui.IntRect(180, 50, 850, 130)),
                node(null, "按住 说话", com.yuyan.imemodule.data.capture.ui.IntRect(80, 1650, 850, 1760))))
        assertTrue(isBrowsingChatSnapshot("com.tencent.mm", root))
        assertFalse(isBrowsingChatSnapshot("com.tencent.mm", root.copy(children = listOf(
            node("com.tencent.mm:id/title", "微信", com.yuyan.imemodule.data.capture.ui.IntRect(180, 50, 850, 130))))))
    }
}
