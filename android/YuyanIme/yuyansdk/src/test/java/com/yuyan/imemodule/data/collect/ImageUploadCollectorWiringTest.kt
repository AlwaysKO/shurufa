package com.yuyan.imemodule.data.collect

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接线回归；时间边界由 ImageUploadScheduleTest 验证，不冒充真机性能测试。 */
class ImageUploadCollectorWiringTest {
    private val source = File("src/main/java/com/yuyan/imemodule/data/collect/DataCollector.kt").readText()

    @Test fun sharedPermitAndRealNetworkReachDelivery() {
        assertTrue(source.contains("beginImageRead = { ImageUploadRuntime.beginUploadRead() }"))
        assertTrue(source.contains("ImageUploadRuntime.maxImageBytes(context, target)"))
        assertTrue(source.contains("ImageUploadRuntime.tryStartImage(context, target, bytes)"))
    }

    @Test fun imageWakeDoesNotInheritFiveSecondBackoffOrSpeedUpDictionarySync() {
        assertTrue(source.contains("IMAGE_POLL_INTERVAL_MS = 1_000L"))
        assertTrue(source.contains("flushEvents(syncDictionary = false)"))
        assertTrue(source.contains("val imagesPending = ImageUploadRuntime.canUploadScreenshot(app, ServerConfig.baseUrl) &&"))
        assertTrue(source.contains("val selection=uploadPlan.select"))
        assertTrue(source.contains("suspend fun refreshBackgroundResources"))
        assertTrue(source.contains("selection=selection"))
        assertTrue(source.contains("retryGate.blocks(target,android.os.SystemClock.elapsedRealtime(),false)"))
    }
}
