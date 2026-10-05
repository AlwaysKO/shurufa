package com.yuyan.imemodule.data.collect

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅检查性能策略接线，非真机耗时测试。 */
class InputPriorityWiringTest {
    private fun source(path: String) = File("src/main/java/com/yuyan/imemodule/$path.kt").readText()
    @Test fun `每批上报与词库同步开始前重新检查输入空闲`() {
        val source = source("data/collect/DataCollector").substringAfter("suspend fun flushNow(")
        assertTrue(source.contains("if (!ImageUploadRuntime.isBackgroundWorkAllowed()) return@coroutineScope"))
        assertTrue(source.contains("beforeBatch = { taskContext.ensureActive(); ImageUploadRuntime.requireBackgroundWorkAllowed() }"))
        assertTrue(source.contains("beforeRequest = { taskContext.ensureActive(); ImageUploadRuntime.requireBackgroundWorkAllowed() }"))
        assertTrue(source.contains("if (regularSync && plan != null && ImageUploadRuntime.isBackgroundWorkAllowed()"))
    }
    @Test fun `因输入暂停词库同步不冒充常规同步完成`() {
        val source = source("data/collect/DataCollector").substringAfter("suspend fun flushNow(")
        assertTrue(source.contains("regularCompleted = dictionaryCompleted"))
        assertTrue(source.contains("dictionaryCompleted = sync.run()"))
        assertTrue(source.contains("plan.url, { CollectionConsent.enabled(app) && ImageUploadRuntime.isBackgroundWorkAllowed() }"))
    }
    @Test fun `截图回调在复制整帧像素之前检查输入`() {
        val source = source("data/capture/media/WindowScreenshotter")
        assertTrue(source.substringBefore("Bitmap.wrapHardwareBuffer").contains("!captureAllowed()"))
        assertTrue(source.contains("captureAllowed: () -> Boolean = ImageUploadRuntime::isBackgroundWorkAllowed"))
    }
    @Test fun `页面树工作与OCR执行前等待空闲且沿用原有身份检查`() {
        val capture = source("service/capture/PassiveChatAccessibilityService")
        assertTrue(capture.contains("LatestIdleWork(backgroundScope, ImageUploadRuntime::awaitBackgroundWorkAllowed)"))
        for (entry in listOf("eventReads", "stableReads", "foregroundReads", "screenshotReads", "fallbackReads")) {
            assertTrue(capture.contains("$entry.submit("))
            assertTrue(capture.contains("$entry.cancel()"))
        }
        val ocr = source("data/capture/media/WechatScreenshotIdentity")
        assertTrue(ocr.substringBefore("titleInput.takeOrDecode").contains("ImageUploadRuntime.requireBackgroundWorkAllowed()"))
    }
    @Test fun `通知截图补偿不把页面树读取放回主线程`() {
        val method = source("service/capture/PassiveChatAccessibilityService")
            .substringAfter("private suspend fun currentFallbackTarget(").substringBefore("private fun isoTimestamp")
        assertTrue(method.contains("withContext(backgroundDispatcher)"))
        assertTrue(!method.contains("mainHandler.post"))
    }
    @Test fun `热路径只向后台请求网络取消`() {
        val source = source("data/collect/ImageUploadRuntime")
        assertTrue(source.contains("cancellations.request()"))
        assertTrue(!source.contains("calls.forEach { it.cancel() }"))
    }
}
