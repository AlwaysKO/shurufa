package com.yuyan.imemodule.data.collect

import android.util.Log
import com.yuyan.imemodule.BuildConfig

internal enum class ReportingStage {
    GATE_ONLINE, USB_STATE, USB_POWER, GATE_USB, GATE_HEALTH, HEALTH_HTTP, HEALTH_ERROR,
    BUSY, BACKOFF, FLUSH_START, REGISTER, READ_EVENTS, READ_REPORTS, REPORTS_READY,
    POST_DEVICE, POST_EVENTS, POST_ASSET, POST_MESSAGES, POST_OTHER, HTTP_RESULT,
    ACK_RESULT, STORE_ACK, DELIVERY_ERROR, FLUSH_END
}
internal fun reportingTraceLine(enabled: Boolean, stage: ReportingStage, online: Boolean, value: Int, flag: Boolean): String? =
    if (enabled) "stage=$stage online=$online value=$value flag=$flag" else null

/** 不接受内容字符串，禁止记录正文、标题、地址、凭据及响应正文。 */
internal object ReportingTrace {
    fun record(stage: ReportingStage, online: Boolean, value: Int = -1, flag: Boolean = false) {
        val line = reportingTraceLine(BuildConfig.DEBUG, stage, online, value, flag) ?: return
        try { Log.d("ReportDeliveryTrace", line) } catch (_: RuntimeException) { /* 诊断不得影响发送。 */ }
    }
}
