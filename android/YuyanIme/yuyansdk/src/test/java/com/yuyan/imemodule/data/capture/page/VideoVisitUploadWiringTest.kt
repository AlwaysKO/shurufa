package com.yuyan.imemodule.data.capture.page

import java.io.File
import org.junit.Assert.*
import org.junit.Test

class VideoVisitUploadWiringTest {
    private fun source(path: String) = File("src/main/java/com/yuyan/imemodule/$path").readText()
    @Test fun existingCollectorOwnsWakeFlushAndCancellation() {
        val source = source("data/collect/DataCollector.kt")
        for (call in listOf("VideoVisitSync.nextUploadDelay(app, includePaused = true)", "VideoVisitSync.flush(app)", "VideoVisitSync.cancel()"))
            assertTrue(call, source.contains(call))
        val imageFastPath=source.substringAfter("val imagesPending =").substringBefore("// 视频结束元数据")
        assertFalse("Video metadata must never drive the one-second image path",imageFastPath.contains("VideoVisitSync"))
        assertTrue(source("data/capture/page/VideoVisitServiceBridge.kt").contains("DataCollector.requestSync()"))
    }
    @Test fun syncUsesWifiScopeAndExistingSmallRequestTransportWithoutTimer() {
        val source = source("data/capture/page/VideoVisitSync.kt")
        for (guard in listOf("canUploadChat", "onlineEpoch", "CollectionConsent.epoch", "ChatCaptureSettings.revision()", "mutex.tryLock()", "prepareChatCall", "readPageReceipt"))
            assertTrue(guard, source.contains(guard))
        assertFalse(source.contains("Timer(")); assertFalse(source.contains("postDelayed("))
        assertFalse(source.contains("Base64")); assertFalse(source.contains("toBitmap"))
    }
}
