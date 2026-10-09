package com.yuyan.imemodule.data.capture.page

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.view.accessibility.AccessibilityWindowInfo
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.GameForegroundWindow
import com.yuyan.imemodule.data.collect.inspectGameForegroundWindow

/** 进程级串行队列：旧服务收尾先于新服务恢复，不随聊天截图协程被 cancel。无轮询。 */
private object VideoVisitWorker {
    val handler: Handler by lazy {
        val thread = HandlerThread("video-visit-journal", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        Handler(thread.looper)
    }
}

internal fun createVideoVisitMonitor(service: AccessibilityService): VideoVisitMonitor {
    val context = service.applicationContext
    return VideoVisitMonitor(
        context, VideoVisitWorker.handler,
        readForeground = { eventPackage, eventWindow ->
            val windows = service.windows
            @Suppress("DEPRECATION")
            try {
                val overlay = hasVideoWindowOverlay(windows, eventWindow)
                // 单独核验底层应用，不能因为事件来自输入法而忽略已发生的应用切换。
                val actual = inspectGameForegroundWindow(eventPackage.orEmpty(), -1, windows)
                val kind = if (overlay) VideoWindowKind.OVERLAY
                    else if (actual.window == GameForegroundWindow.Application) VideoWindowKind.APPLICATION
                    else VideoWindowKind.UNKNOWN
                val homePackage = if (kind == VideoWindowKind.APPLICATION) runCatching {
                    context.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                        ?.activityInfo?.packageName
                }.getOrNull() else null
                VideoForegroundEvidence(kind, actual.verifiedPackage, actual.resolvedWindowId,
                    home = actual.verifiedPackage != null && actual.verifiedPackage == homePackage,
                    feedVerified = CollectionConsent.enabled(context) &&
                        actual.verifiedPackage?.let { com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings.rule(it).enabled } == true &&
                        kind == VideoWindowKind.APPLICATION && actual.verifiedPackage in setOf("com.tencent.mm", "com.ss.android.ugc.aweme") &&
                        actual.resolvedWindowId != null && readBrowsingPageSnapshot(service,
                            BrowsePageToken(requireNotNull(actual.verifiedPackage), requireNotNull(actual.resolvedWindowId), 0))?.let {
                            PageCapturePolicy.classify(it.page.packageName, it.bounds, it.labels, chatVerified = it.chatVerified).kind == PageKind.MEDIA_FEED
                        } == true)
            } finally { windows.forEach { it.recycle() } }
        },
        allowed = { CollectionConsent.enabled(context) },
        policyEpoch = { com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings.revision() },
        platformAllowed = { platform ->
            val pkg = if (platform == "wechat") "com.tencent.mm" else "com.ss.android.ugc.aweme"
            com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings.rule(pkg).enabled
        },
        interactive = {
            (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive &&
                !(context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        },
        elapsed = SystemClock::elapsedRealtime,
        wall = System::currentTimeMillis,
        onCompleted = { DataCollector.requestSync() },
        onError = { android.util.Log.w("VideoVisit", "window_or_journal_failure") },
    )
}

internal fun hasVideoWindowOverlay(windows: List<AccessibilityWindowInfo>, eventWindow: Int): Boolean =
    windows.any { it.type != AccessibilityWindowInfo.TYPE_APPLICATION && (it.id == eventWindow || it.isActive) }
