package com.yuyan.imemodule.data.capture

internal enum class CaptureLayer { SERVICE, MEDIA, COORDINATOR }

internal enum class CaptureStage {
    CONNECTED, EVENT, CLASSIFIED, RESET, SUPERSEDED, TREE, PROBE_SCHEDULED, PROBE_RUN,
    TRANSCRIPT_SCHEDULED, TRANSCRIPT_RUN, DELAY_CANCELLED, PAGE_REJECTED, VIEWPORT_READY,
    EMPTY_REQUEST, ASSET_READY, ASSET_FAILED, DUPLICATE, IDENTITY_REJECTED, IDENTITY_READY,
    PERSIST_RESULT, PIPELINE_FAILED, SYSTEM_REQUEST, SYSTEM_READY, REQUEST_CANCELLED, CONTENT_SCHEDULED, CONTENT_PENDING, CONTENT_DUPLICATE, SCROLL_PENDING, SCROLL_STOPPED
}

internal fun captureTraceLine(enabled: Boolean, stage: CaptureStage, window: Int = -1,
    generation: Long = -1, value: Int = -1, flag: Boolean = false, layer: CaptureLayer = CaptureLayer.SERVICE): String? = if (!enabled) null else
    "layer=${layer.name} stage=${stage.name} window=$window generation=$generation value=$value flag=$flag"

/** 临时真机诊断：只接收枚举/数字/布尔值，禁止传入标题、正文、路径或异常文本。 */
internal object CaptureTrace {
    fun record(stage: CaptureStage, window: Int = -1, generation: Long = -1,
        value: Int = -1, flag: Boolean = false, layer: CaptureLayer = CaptureLayer.SERVICE) {
        val line = captureTraceLine(com.yuyan.imemodule.BuildConfig.DEBUG, stage, window, generation, value, flag, layer) ?: return
        // 诊断不可打断采集；不写文件，不启动额外任务，release不输出。
        try { android.util.Log.d("ChatCaptureTrace", line) } catch (_: RuntimeException) { }
    }
}
