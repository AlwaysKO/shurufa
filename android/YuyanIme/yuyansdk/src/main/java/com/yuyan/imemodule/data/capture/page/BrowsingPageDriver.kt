package com.yuyan.imemodule.data.capture.page

import com.yuyan.imemodule.data.capture.ui.IntRect
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicLong

internal enum class BrowsePageEvent { WINDOW, SCROLL, CONTENT }
internal data class BrowsePageSnapshot(val page: BrowsePageToken, val bounds: IntRect, val labels: List<PageLabel>, val chatVerified: Boolean = false)

/** 主线程只更新代次/取消待办；数据库、窗口树及截图均在传入的后台scope中。 */
internal class BrowsingPageDriver(
    parent: CoroutineScope,
    private val elapsed: () -> Long,
    private val consentEpoch: () -> Long,
    private val authorized: () -> Boolean,
    private val awaitIdle: suspend (() -> Boolean) -> Boolean,
    private val wait: suspend (Long) -> Unit,
    private val hasCapacity: () -> Boolean,
    private val read: suspend (BrowsePageToken) -> BrowsePageSnapshot?,
    private val reserve: (String) -> Boolean,
    private val capture: suspend (BrowsePageSnapshot, () -> Boolean) -> PageWriteResult,
    private val outcome: (String, String) -> Unit,
    private val persistentIntervalRemaining: () -> Long? = { 0L },
) {
    private val lifetime = SupervisorJob(parent.coroutineContext[Job])
    private val scope = CoroutineScope(parent.coroutineContext + lifetime)
    private val sequence = AtomicLong()
    private val schedule = BrowsingCaptureSchedule()
    @Volatile private var closed = false
    private var pending: Job? = null

    @Synchronized fun changed(packageName: String?, windowId: Int, event: BrowsePageEvent, eventEpoch: Long = consentEpoch()): Job? {
        if (closed || event == BrowsePageEvent.CONTENT) return null
        val generation = sequence.incrementAndGet()
        pending?.cancel()
        if (packageName !in setOf("com.tencent.mm", "com.ss.android.ugc.aweme") || windowId < 0) return null
        val token = BrowsePageToken(requireNotNull(packageName), windowId, generation)
        fun report(code: String) = outcome(code, token.packageName)
        val observedAt = elapsed()
        val epoch = eventEpoch
        fun current() = !closed && sequence.get() == generation && consentEpoch() == epoch && authorized()
        return scope.launch {
            try {
                if (!current()) return@launch
                // 只在后台操作schedule：其预算回调会写SQLite，不能让主线程等待这个锁。
                schedule.leave(generation - 1)
                schedule.changed(token, observedAt)
                val remaining = schedule.remainingDelay(elapsed()) ?: return@launch
                if (remaining > 0) wait(remaining)
                if (!current() || !awaitIdle(::current) || !current()) return@launch
                val restoredDelay = persistentIntervalRemaining()
                if (restoredDelay == null || restoredDelay !in 0L..180_000L) {
                    report("budget_invalid_clock"); return@launch
                }
                if (restoredDelay > 0) {
                    report("budget_interval")
                    // 同一事件只保留一次延后机会，不轮询截图；导航/撤权会取消此候选。
                    wait(restoredDelay)
                    if (!current() || !awaitIdle(::current) || !current()) return@launch
                }
                if (!hasCapacity()) { report("queue_full"); return@launch }
                var snapshot = read(token)
                if (!current()) return@launch
                if (snapshot == null) {
                    // 窗口树可能尚未可用；仅同一事件补核验一次，不消费截图预算。
                    wait(800)
                    if (!current() || !awaitIdle(::current) || !current()) return@launch
                    if (!hasCapacity()) { report("queue_full"); return@launch }
                    snapshot = read(token)
                }
                if (snapshot == null) { report("window_unconfirmed"); return@launch }
                if (!current() || snapshot.page != token) return@launch
                val decision = PageCapturePolicy.classify(token.packageName, snapshot.bounds, snapshot.labels, chatVerified = snapshot.chatVerified)
                if (decision.kind == PageKind.CHAT || decision.reason in setOf(
                        "unsupported_package", "secure_window", "sensitive_input", "editable_non_chat", "invalid_bounds")) {
                    report("page_rejected"); return@launch
                }
                val permission = schedule.take(elapsed(), token, current()) { current() && reserve(token.packageName) }
                if (permission == null || !current()) { report("budget_or_scope_rejected"); return@launch }
                report(capture(snapshot, ::current).status.name.lowercase())
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { report("capture_or_storage_failed") }
        }.also { pending = it }
    }

    /** 迟到的窗口元信息回调不能覆盖更新导航；只持有短内存锁，不在此执行数据库工作。 */
    @Synchronized fun changedIfCurrent(generation: Long, epoch: Long, packageName: String, windowId: Int): Job? {
        if (sequence.get() != generation || consentEpoch() != epoch) return null
        return changed(packageName, windowId, BrowsePageEvent.WINDOW, epoch)
    }

    @Synchronized fun invalidate(): Long {
        val generation = sequence.incrementAndGet()
        pending?.cancel()
        pending = null
        return generation
    }
    /** 调用方必须等待返回的Job结束后再关闭预算/图片数据库。 */
    @Synchronized fun close(): Job {
        closed = true
        invalidate()
        lifetime.cancel()
        return lifetime
    }
}
