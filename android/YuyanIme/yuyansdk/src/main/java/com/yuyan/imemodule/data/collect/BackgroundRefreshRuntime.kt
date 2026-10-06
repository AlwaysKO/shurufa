package com.yuyan.imemodule.data.collect

import android.content.Context
import com.yuyan.imemodule.data.capture.adapter.ChatCaptureSettings
import com.yuyan.imemodule.data.completion.CompletionSync
import com.yuyan.imemodule.data.phrase.PhraseSync
import com.yuyan.imemodule.expression.ExpressionSyncJobService
import com.yuyan.imemodule.expression.send.ExpressionDeliverySettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** 复用表情检查 Job 和恢复 Handler；本类不创建额外轮询或网络监听。 */
object BackgroundRefreshRuntime {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    internal fun usbDue(context: Context, userInitiated: Boolean = false): Boolean {
        if (ImageUploadRuntime.hasValidatedNetwork(context) || !isUsbDataLink(context)) return false
        val saved = state(context)
        val last = if (saved.contains("usb_last_attempt_at")) saved.getLong("usb_last_attempt_at", 0) else null
        return BackgroundRefreshPolicy.due(System.currentTimeMillis(), last, connected = true, wifi = true, userInitiated = userInitiated)
    }
    private val mutex = Mutex()
    private fun state(context: Context) = context.getSharedPreferences("background_resource_refresh_v1", Context.MODE_PRIVATE)

    fun start(context: Context) = ExpressionSyncJobService.startRecovery(context.applicationContext)
    fun networkChanged(context: Context) = ExpressionSyncJobService.recover(context.applicationContext)
    fun request(context: Context, userInitiated: Boolean = false) {
        start(context)
        if (due(context, userInitiated)) ExpressionSyncJobService.scheduleImmediateCheck(context, userInitiated)
        else if (usbDue(context, userInitiated)) scope.launch { refreshUsbResources(context.applicationContext, userInitiated) }
    }

    /** 离线 USB 仅恢复本地个人词库；独立持久资格避免新事件触发频繁健康探测。 */
    internal suspend fun refreshUsbResources(context: Context, userInitiated: Boolean = false) = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (!CollectionConsent.enabled(context) || !ImageUploadRuntime.isBackgroundWorkAllowed() || !usbDue(context, userInitiated)) return@withLock
            val saved = state(context)
            check(saved.edit().putLong("usb_last_attempt_at", System.currentTimeMillis()).commit())
            try { DataCollector.refreshBackgroundResources(context, localOnly = true) }
            catch (cancelled: CancellationException) {
                saved.edit().remove("usb_last_attempt_at").commit()
                throw cancelled
            } catch (_: Exception) { /* 保留半小时兜底，不让离线故障引起频繁探测。 */ }
            finally {
                // 输入或游戏也可能让词库同步正常返回，暂停的批次恢复后仍应有资格补查。
                if (!ImageUploadRuntime.isBackgroundWorkAllowed()) saved.edit().remove("usb_last_attempt_at").commit()
            }
        }
    }

    internal fun due(context: Context, userInitiated: Boolean = false): Boolean {
        val saved = state(context)
        val source = saved.getString("source", null)
        val last = if (source == ServerConfig.baseUrl && saved.contains("last_attempt_at")) saved.getLong("last_attempt_at", 0)
        else if (source == null) {
            // 升级继承已有资源检查资格，避免仅重启进程就重复联网。
            val previous = context.getSharedPreferences("expression_background_sync", Context.MODE_PRIVATE)
            if (previous.contains("last_attempt_at")) previous.getLong("last_attempt_at", 0) else null
        } else null
        return BackgroundRefreshPolicy.due(System.currentTimeMillis(), last,
            ImageUploadRuntime.hasValidatedNetwork(context), ImageUploadRuntime.hasValidatedWifi(context), userInitiated)
    }

    internal suspend fun refreshNow(context: Context, userInitiated: Boolean = false, versionCheck: suspend () -> Unit): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val app = context.applicationContext
                ServerConfig.init(app)
                val allowed = { ImageUploadRuntime.hasValidatedNetwork(app) && ImageUploadRuntime.isBackgroundWorkAllowed() }
                if (!allowed() || !due(app, userInitiated)) return@withLock false
                val saved = state(app)
                check(saved.edit().putString("source", ServerConfig.baseUrl).putLong("last_attempt_at", System.currentTimeMillis()).commit())
                var finished = false
                try {
                    finished = runBackgroundRefreshSteps(allowed, listOf(
                        { DataCollector.refreshBackgroundResources(app) },
                        { CompletionSync.refreshInBatch(app) },
                        { PhraseSync.refreshInBatch(app) },
                        { ChatCaptureSettings.refreshInBatch(app) },
                        { ExpressionDeliverySettings.refreshInBatch(app) },
                        versionCheck,
                    ))
                    finished
                } finally {
                    // 输入、游戏、断网或系统取消不算完整批次，恢复后可以继续补查。
                    if (!finished) saved.edit().remove("last_attempt_at").commit()
                }
            }
        }
}

internal suspend fun runBackgroundRefreshSteps(allowed: () -> Boolean, steps: List<suspend () -> Unit>): Boolean {
    for (step in steps) {
        if (!allowed()) return false
        try { step() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* 单个模块失败不阻断其他模块；下个批次保留重试。 */ }
    }
    return allowed()
}
