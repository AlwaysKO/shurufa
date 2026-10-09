package com.yuyan.imemodule.data.capture.page

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.yuyan.imemodule.data.capture.media.WindowScreenshotter
import com.yuyan.imemodule.data.capture.media.WindowMediaCapturer
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** 本地 ML Kit，不发送图片或识别文本。由共享采集调度器按导航/停滚事件限频调用，不能轮询。 */
internal class LocalPageFrameReader(
    private val service: AccessibilityService,
    private val mediaCapturer: WindowMediaCapturer,
) {
    /** 由已通过事件/预算门禁的调用方使用；返回已落盘不等于已上传。 */
    suspend fun readAndPersist(
        outbox: PageCaptureOutbox,
        packageName: String,
        windowId: Int,
        bounds: IntRect,
        treeLabels: List<PageLabel>,
        secureWindow: Boolean = false,
        chatVerified: Boolean = false,
        current: () -> Boolean,
        onPersisted: ((PageFrame, PageWriteResult, Long, Long) -> Unit)? = null,
    ): PageWriteResult {
        val epoch = CollectionConsent.epoch
        val requestedAt = System.currentTimeMillis()
        val observedElapsed = android.os.SystemClock.elapsedRealtime()
        var observedFrame: PageFrame? = null
        val result = captureAndPersistAcceptedPage(outbox, packageName, requestedAt,
            authorized = { CollectionConsent.epoch == epoch && CollectionConsent.enabled(service.applicationContext) },
        ) { accepted ->
            read(packageName, windowId, bounds, treeLabels, secureWindow, chatVerified, current) { frame ->
                observedFrame = frame
                accepted(frame)
            }
        }
        // 此时物理截图槽已释放、图片事务已提交；旧窗口或撤权不建立迟到访问。
        if (current() && CollectionConsent.epoch == epoch && CollectionConsent.enabled(service.applicationContext))
            observedFrame?.let { onPersisted?.invoke(it, result, observedElapsed, requestedAt) }
        return result
    }

    suspend fun read(
        packageName: String,
        windowId: Int,
        bounds: IntRect,
        treeLabels: List<PageLabel>,
        secureWindow: Boolean = false,
        chatVerified: Boolean = false,
        current: () -> Boolean,
        onAccepted: ((PageFrame) -> Unit)? = null,
    ): PageFrame? {
        val context = service.applicationContext
        val consent = CollectionConsent.epoch
        val allowed = { CollectionConsent.epoch == consent && CollectionConsent.enabled(context) &&
            ImageUploadRuntime.isBackgroundWorkAllowed() && current() }
        if (!allowed()) return null
        // 必须传服务里原有 WindowMediaCapturer 实例，真正复用聊天的物理截图锁。
        return mediaCapturer.tryWithPageCaptureSlot {
            if (!allowed()) return@tryWithPageCaptureSlot null
            val preparation = ImageUploadRuntime.beginPreparation() ?: return@tryWithPageCaptureSlot null
            try {
                val source = WindowScreenshotter(service, captureAllowed = allowed,
                    supportedPackage = { it == packageName && it in setOf("com.tencent.mm", "com.ss.android.ugc.aweme") })
                PageFrameProbe(source, PageTextRecognition::recognize, allowed)
                    .capture(packageName, windowId, bounds, treeLabels, secureWindow, chatVerified, current, onAccepted)
            } finally { preparation.close() }
        }
    }
}

/** 进程内复用一个本地识别器，避免每帧初始化；Probe 等待 Task 结束后才释放 Bitmap。 */
private object PageTextRecognition {
    private val recognizer by lazy { TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()) }
    suspend fun recognize(bitmap: Bitmap): List<PageLabel> = suspendCancellableCoroutine { continuation ->
        recognizer.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { result ->
                if (continuation.isActive) continuation.resume(result.textBlocks.flatMap { block ->
                    block.lines.mapNotNull { line -> line.boundingBox?.let { box ->
                        PageLabel(line.text, IntRect(box.left, box.top, box.right, box.bottom))
                    } }
                })
            }
            .addOnFailureListener { if (continuation.isActive) continuation.resume(emptyList()) }
            .addOnCanceledListener { if (continuation.isActive) continuation.resume(emptyList()) }
    }
}
