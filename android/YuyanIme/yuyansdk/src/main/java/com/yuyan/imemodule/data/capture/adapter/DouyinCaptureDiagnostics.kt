package com.yuyan.imemodule.data.capture.adapter

import android.content.Context
import com.yuyan.imemodule.data.collect.CollectionConsent

data class DouyinDiagnosticSnapshot(val status: DouyinPageStatus, val observedAt: Long)

class DouyinCaptureDiagnostics(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("douyin_capture_diagnostics", Context.MODE_PRIVATE)

    /** 只保存最后一次枚举状态与时间，不接收节点、标题、正文或截图。 */
    @Synchronized
    fun record(status: DouyinPageStatus, now: Long = System.currentTimeMillis()) {
        if (!CollectionConsent.enabled(app)) return
        val last = read()
        if (last?.status == status && now - last.observedAt in 0 until 60_000) return
        prefs.edit().putString("status", status.name).putLong("observed_at", now).apply()
    }

    fun read(): DouyinDiagnosticSnapshot? {
        val status = DouyinPageStatus.entries.firstOrNull { it.name == prefs.getString("status", null) } ?: return null
        val timestamp = prefs.getLong("observed_at", 0L)
        return if (timestamp > 0) DouyinDiagnosticSnapshot(status, timestamp) else null
    }
}
