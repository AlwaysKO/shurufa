package com.yuyan.imemodule.data.collect

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** 仅检查性能策略接线，非真机耗时测试。 */
class InputPriorityWiringTest {
    private fun source(path: String) = File("src/main/java/com/yuyan/imemodule/$path.kt").readText()
    @Test fun `普通数据按输入状态选择而聊天图片保留独立资格`() {
        val source = source("data/collect/DataCollector")
        assertTrue(source.contains("uploadPlan.select(wifi,idle"))
        assertTrue(source.contains("kind == \"chat_asset\" || ImageUploadRuntime.isInputIdle()"))
        assertTrue(source.contains("beforeBatch=check,beforeRequest=check,selection=selection"))
    }
    @Test fun `共享词库刷新仍在请求与批次之间检查输入`() {
        val source = source("data/collect/DataCollector").substringAfter("suspend fun refreshBackgroundResources(").substringBefore("suspend fun flushNow(")
        assertTrue(source.contains("!ImageUploadRuntime.isBackgroundWorkAllowed()"))
        assertTrue(source.contains("CollectionConsent.enabled(app) && ImageUploadRuntime.isBackgroundWorkAllowed()"))
        assertTrue(source.contains("sync.run()"))
    }
    @Test fun `截图回调在复制整帧像素之前检查输入`() {
        val source = source("data/capture/media/WindowScreenshotter")
        val callback = source.substringAfter("override fun onSuccess(").substringBefore("Bitmap.wrapHardwareBuffer")
        assertTrue(callback.contains("!requestAllowed()"))
        assertTrue(source.contains("attempt?.canTakeFrame() ?: captureAllowed()"))
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
