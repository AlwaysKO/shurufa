package com.yuyan.imemodule.data.capture.page

import java.io.File
import org.junit.Assert.*
import org.junit.Test

/** 只证明生产接线，网络/限速时序由共享守卫测试验证，不冒充真实上传。 */
class PageCaptureUploadWiringTest {
    private fun source(path: String) = File("src/main/java/com/yuyan/imemodule/$path").readText()
    @Test fun pagesJoinExistingWakeFlushPendingAndCancellation() {
        val s = source("data/collect/DataCollector.kt")
        assertTrue(s.contains("PageCaptureSync.hasPending(app)"))
        assertTrue(s.contains("PageCaptureSync.hasDue(app)"))
        assertTrue(s.contains("PageCaptureSync.flush(app)"))
        assertTrue(s.contains("PageCaptureSync.cancel()"))
    }
    @Test fun syncUsesWifiSharedPermitsBoundNetworkAndDisallowsRedirect() {
        val s = source("data/capture/page/PageCaptureSync.kt")
        assertTrue(s.contains("ImageUploadRuntime.canUploadScreenshot"))
        assertTrue(s.contains("ImageUploadRuntime.beginPreparation()"))
        assertTrue(s.contains("ImageUploadRuntime.tryStartImage"))
        assertTrue(s.contains("ImageUploadRuntime.prepareChatCall"))
        assertTrue(s.contains("ImageUploadRuntime.finishChatCall"))
        assertTrue(s.contains("followRedirects(false)"))
        assertTrue(s.contains("CollectionConsent.epoch"))
        assertTrue(s.contains("target == ServerConfig.baseUrl"))
        assertFalse(s.contains("tryStartNavigationImage"))
        assertTrue(source("data/collect/ImageUploadRuntime.kt").contains("\"/api/v1/mobile/page-captures\""))
    }
    @Test fun browsingDiagnosticsAreWiredToTheCapturedTokenNotTheLatestHost() {
        val bridge = source("data/capture/page/BrowsingPageServiceBridge.kt")
        val driver = source("data/capture/page/BrowsingPageDriver.kt")
        assertTrue(bridge.contains("PageCaptureDiagnostics.capture(context, packageName, code)"))
        assertTrue(driver.contains("reserve(token.packageName)"))
        assertTrue(driver.contains("outcome(code, token.packageName)"))
    }
    @Test fun accessibilityManifestSubscribesToWindowTopologyEventsUsedByPageBridges() {
        val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val xml = factory.newDocumentBuilder().parse(File("src/main/res/xml/passive_chat_accessibility_service.xml"))
        val events = xml.documentElement.getAttributeNS("http://schemas.android.com/apk/res/android", "accessibilityEventTypes").split('|')
        assertTrue("window topology handler must be subscribed in service XML", "typeWindowsChanged" in events)
        assertTrue("typeWindowStateChanged" in events)
        assertTrue("typeViewScrolled" in events)
        assertTrue(source("service/capture/PassiveChatAccessibilityService.kt").contains("AccessibilityEvent.TYPE_WINDOWS_CHANGED"))
    }
    @Test fun readerDistinguishesPhysicalSlotAndPreparationDenial() {
        val reader = source("data/capture/page/LocalPageFrameReader.kt")
        assertTrue(reader.contains("PageProbeStatus.SLOT_BUSY"))
        assertTrue(reader.contains("PageProbeStatus.PREPARATION_BLOCKED"))
        assertTrue(reader.contains("PageProbeStatus.AUTHORIZATION_LOST"))
    }
    @Test fun savedPageOnlyWakesExistingSync() {
        val s = source("data/capture/page/PageCaptureOutbox.kt")
        assertTrue(s.contains("DataCollector.requestSync()"))
    }
}
