package com.yuyan.imemodule.data.capture.page

import android.content.Context
import com.yuyan.imemodule.data.capture.adapter.ChatCaptureDiagnostics
import com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings
import com.yuyan.imemodule.data.collect.CollectionConsent

/** 只记固定枚举的最近原因；复用既有有界、空闲上传，不含标题、正文或图片。 */
internal object PageCaptureDiagnostics {
    internal fun captureStatus(code: String): String? = when (code) {
        "saved" -> "saved"
        "duplicate" -> "duplicate"
        "budget_interval" -> "interval_limited"
        "budget_hour_limit", "budget_day_limit" -> "budget_limited"
        "queue_full", "full" -> "queue_full"
        "window_unconfirmed", "page_rejected", "no_frame" -> "page_uncovered"
        "invalid", "discarded" -> "cancelled"
        "capture_or_storage_failed", "budget_invalid_clock" -> "failed"
        else -> null // 不让泛化结束原因覆盖具体间隔/预算；未知文字不上报。
    }
    fun capture(context: Context, packageName: String, code: String) {
        val status = captureStatus(code) ?: return
        record(context, packageName, "browse_capture", status, null)
    }
    fun upload(context: Context, packageName: String, status: String, error: Int? = null) {
        if (status !in setOf("acknowledged", "failed", "waiting_wifi", "discarded")) return
        record(context, packageName, "browse_upload", status, error)
    }
    private fun record(context: Context, pkg: String, stage: String, status: String, error: Int?) {
        val platform = when (pkg) {
            "com.tencent.mm" -> "wechat"
            "com.ss.android.ugc.aweme" -> "douyin"
            else -> return
        }
        if (!CollectionConsent.enabled(context) || !ChatCaptureSettings.rule(pkg).enabled) return
        ChatCaptureDiagnostics.record(context, platform, stage, status, error)
    }
}
