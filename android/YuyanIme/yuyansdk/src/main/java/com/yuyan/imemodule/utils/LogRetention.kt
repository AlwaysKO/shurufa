package com.yuyan.imemodule.utils

import android.content.Context
import java.io.File

/** 仅清理确定的崩溃日志目录；调用方在 IO 线程执行，清理失败不影响采集。 */
internal object LogRetention {
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val RETENTION_MS = 30 * DAY_MS

    @Synchronized
    fun prune(context: Context, now: Long = System.currentTimeMillis()) {
        if (now <= 0) return
        runCatching {
            val app = context.applicationContext
            val prefs = app.getSharedPreferences("log_retention", Context.MODE_PRIVATE)
            val last = prefs.getLong("last_scan_at", 0)
            if (last > 0 && now >= last && now - last < DAY_MS) return
            val logs = File(app.filesDir.canonicalFile, "crash_logs")
            // 不沿符号链接扫描目录，也不删除符号链接指向的日志。
            if (logs.canonicalFile == logs) {
                logs.listFiles()?.forEach { file ->
                    if (file.name.startsWith("crash-") && file.name.endsWith(".log") &&
                        file.canonicalFile == file && file.isFile) {
                        val modified = file.lastModified()
                        if (modified > 0 && modified < now - RETENTION_MS) file.delete()
                    }
                }
            }
            prefs.edit().putLong("last_scan_at", now).commit()
        }
    }
}
