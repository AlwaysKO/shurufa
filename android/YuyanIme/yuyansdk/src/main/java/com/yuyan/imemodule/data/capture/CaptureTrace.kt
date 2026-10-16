package com.yuyan.imemodule.data.capture

import android.content.Context
import com.yuyan.imemodule.data.collect.CaptureHistory
import com.yuyan.imemodule.data.collect.CaptureHistoryEvent

internal enum class CaptureLayer { SERVICE, MEDIA, COORDINATOR }

internal enum class CaptureStage {
    CONNECTED, EVENT, CLASSIFIED, RESET, SUPERSEDED, TREE, PROBE_SCHEDULED, PROBE_RUN,
    TRANSCRIPT_SCHEDULED, TRANSCRIPT_RUN, DELAY_CANCELLED, PAGE_REJECTED, VIEWPORT_READY,
    EMPTY_REQUEST, ASSET_READY, ASSET_FAILED, DUPLICATE, IDENTITY_REJECTED, IDENTITY_READY,
    PERSIST_RESULT, PIPELINE_FAILED, SYSTEM_REQUEST, SYSTEM_READY, REQUEST_CANCELLED, CONTENT_SCHEDULED, CONTENT_PENDING, CONTENT_DUPLICATE, CONTENT_DECISION, SCROLL_PENDING, SCROLL_STOPPED, SCROLL_POSITION, SCROLL_COUNT, SEND_RENDER, PROMPT_STEP, SEND_REQUESTED, PROMPT_CANCELLED
}

internal fun captureTraceLine(enabled: Boolean, stage: CaptureStage, window: Int = -1,
    generation: Long = -1, value: Int = -1, flag: Boolean = false, layer: CaptureLayer = CaptureLayer.SERVICE, reason: ScreenshotContentReason? = null): String? = if (!enabled) null else
    "layer=${layer.name} stage=${stage.name} window=$window generation=$generation value=$value flag=$flag" + (reason?.let { " reason=${it.wireName}" } ?: "")

/** 只保留稀疏关键阶段；内容/树事件的高频日志不进入持久历史。 */
internal fun captureTraceHistoryEvent(stage: CaptureStage, value: Int = -1, flag: Boolean = false): CaptureHistoryEvent? = when (stage) {
    CaptureStage.SEND_REQUESTED -> CaptureHistoryEvent.CHAT_SEND_REQUESTED
    CaptureStage.PROMPT_CANCELLED -> CaptureHistoryEvent.CHAT_PROMPT_CANCELLED
    CaptureStage.PROMPT_STEP -> when (value) {
        0 -> if (flag) CaptureHistoryEvent.CHAT_PROMPT_STARTED else null
        1 -> if (flag) CaptureHistoryEvent.CHAT_PROMPT_RENDER_READY else CaptureHistoryEvent.CHAT_PROMPT_RENDER_UNSETTLED
        2 -> if (flag) CaptureHistoryEvent.CHAT_PROMPT_FRAME_ALLOWED else CaptureHistoryEvent.CHAT_PROMPT_FRAME_BLOCKED
        else -> null
    }
    CaptureStage.IDENTITY_REJECTED -> CaptureHistoryEvent.CHAT_IDENTITY_REJECTED
    CaptureStage.CONTENT_DUPLICATE -> CaptureHistoryEvent.CHAT_CONTENT_DUPLICATE
    else -> null
}

/** 固定阶段历史在release同样保留；详细数字日志仅DEBUG输出。 */
internal object CaptureTrace {
    @Volatile private var appContext: Context? = null
    fun bind(context: Context) {
        appContext = context.applicationContext
        com.yuyan.imemodule.data.collect.CollectionDiagnostics.bind(context)
    }

    fun record(stage: CaptureStage, window: Int = -1, generation: Long = -1,
        value: Int = -1, flag: Boolean = false, layer: CaptureLayer = CaptureLayer.SERVICE, reason: ScreenshotContentReason? = null, historyEnabled: Boolean = true) {
        if (!com.yuyan.imemodule.data.collect.CollectionDiagnostics.enabled()) return
        if (historyEnabled) appContext?.let { context ->
            captureTraceHistoryEvent(stage, value, flag)?.let { event ->
                try { CaptureHistory.record(context, event) } catch (_: RuntimeException) { }
            }
        }
        val line = captureTraceLine(com.yuyan.imemodule.BuildConfig.DEBUG, stage, window, generation, value, flag, layer, reason) ?: return
        // 诊断不可打断采集；详细日志不写文件，release不输出。
        try { android.util.Log.d("ChatCaptureTrace", line) } catch (_: RuntimeException) { }
    }
}
