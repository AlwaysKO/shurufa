package com.yuyan.imemodule.data.capture.page

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

internal enum class VideoWindowKind { APPLICATION, OVERLAY, UNKNOWN }
internal data class VideoForegroundEvidence(
    val kind: VideoWindowKind,
    val packageName: String? = null,
    val windowId: Int? = null,
    val home: Boolean = false,
)

/**
 * 系统事件桥：主线程仅冻结事件元信息，窗口核验和 SQLite 均交给串行后台 Handler。
 * confirmVideo 仅供已确认页面识别结果调用，不能根据 App 包名直接启动视频访问。
 */
internal class VideoVisitMonitor(
    context: Context,
    private val worker: Handler,
    private val readForeground: (String?, Int) -> VideoForegroundEvidence,
    private val allowed: () -> Boolean,
    private val interactive: () -> Boolean,
    private val elapsed: () -> Long,
    private val wall: () -> Long,
    private val onClosed: () -> Unit = {},
    private val onError: () -> Unit = {},
    private val onCompleted: () -> Unit = {},
    private val consentEpoch: () -> Long = { com.yuyan.imemodule.data.collect.CollectionConsent.epoch },
    private val policyEpoch: () -> Long = { 0L },
    private val platformAllowed: (String) -> Boolean = { true },
) : Closeable {
    private val context = context.applicationContext
    private val epoch = AtomicLong()
    @Volatile private var closed = false
    @Volatile private var current: VideoVisit? = null
    @Volatile private var currentConsent = 0L
    @Volatile private var currentPolicy = 0L
    private var currentWindow: Int? = null
    private val store = VideoVisitStore(this.context)
    private var ready = false
    private data class End(val id: String, val elapsed: Long, val wall: Long, val reason: VideoExitReason)
    // 有界单槽：失败的第一个结束边界不得被后来的事件或进入覆盖。
    private var pending: End? = null
    private val retry = Runnable { if (!closed) flushEnd() }
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (closed) return
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                invalidate()
                val id = current?.id ?: return
                val end = boundary(id, elapsed(), wall(), VideoExitReason.LOCKED)
                worker.post { end(end) }
            } else if (intent?.action == Intent.ACTION_SCREEN_ON) invalidate()
        }
    }
    init {
        val filter = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) }
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") this.context.registerReceiver(receiver, filter)
        worker.post { if (!closed) initialize() }
    }

    fun generation(): Long = epoch.get()
    fun invalidate() { epoch.incrementAndGet() }

    private fun initialize(): Boolean {
        if (ready) return true
        return try {
            store.recoverInterrupted()
            current = null
            ready = true
            if (store.completed(1).isNotEmpty()) wakeSync()
            true
        } catch (_: Exception) { onError(); false }
    }

    fun confirmVideo(generation: Long, platform: String, key: String, observedElapsed: Long, observedWall: Long,
        firstImage: String? = null, observationKind: VideoObservationKind = VideoObservationKind.CONFIRMED_VIDEO,
        windowId: Int? = null, expectedConsentEpoch: Long = consentEpoch(), expectedPolicyEpoch: Long = policyEpoch(),
        stillCurrent: () -> Boolean = { true }) {
        if (closed) return
        fun authorized() = consentEpoch() == expectedConsentEpoch && policyEpoch() == expectedPolicyEpoch &&
            allowed() && platformAllowed(platform) && stillCurrent()
        worker.post {
            if (closed || generation != epoch.get() || !authorized() || !interactive()) return@post
            if (!initialize() || !flushEnd()) return@post
            // 新授权帧不能把旧授权期间的观察正常结算，撤权间隔没有可信时长。
            val previous = current
            if (previous != null && (currentConsent != expectedConsentEpoch || currentPolicy != expectedPolicyEpoch)) {
                end(End(previous.id, observedElapsed, observedWall, VideoExitReason.INTERRUPTED))
                if (pending != null) return@post
            }
            try {
                val change = store.enter(platform, key, observedElapsed, observedWall, firstImage, observationKind)
                val visit = change.active
                currentConsent = expectedConsentEpoch
                currentPolicy = expectedPolicyEpoch
                currentWindow = windowId
                current = visit
                if (change.previous != null) wakeSync()
                // SQLite 等待期间主线程也可能收到边界；入口检查不能覆盖在途写入。
                if (closed || generation != epoch.get() || !authorized() || !interactive()) {
                    end(End(visit.id, observedElapsed, observedWall, VideoExitReason.INTERRUPTED))
                }
            }
            catch (_: Exception) { onError() }
        }
    }

    /** 仅用同帧安全分类并成功持久化的新图启动观察，不借去重回执伪造一次新取帧。 */
    fun pageCaptured(generation: Long, packageName: String, kind: PageKind, result: PageWriteResult,
        observedElapsed: Long, observedWall: Long, windowId: Int? = null,
        expectedConsentEpoch: Long = consentEpoch(), expectedPolicyEpoch: Long = policyEpoch(),
        stillCurrent: () -> Boolean = { true }) {
        if (kind != PageKind.MEDIA_FEED || result.status != PageWriteStatus.SAVED) return
        val image = result.id?.takeIf { it.matches(Regex("[a-fA-F0-9]{8}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{4}-[a-fA-F0-9]{12}")) } ?: return
        val platform = when (packageName) { "com.tencent.mm" -> "wechat"; "com.ss.android.ugc.aweme" -> "douyin"; else -> return }
        confirmVideo(generation, platform, "feed-observation:$image", observedElapsed, observedWall,
            image, VideoObservationKind.UNCONFIRMED_FEED, windowId, expectedConsentEpoch, expectedPolicyEpoch, stillCurrent)
    }

    /** 滚动只证明当前观察边界发生变化，不证明已切到另一条视频。 */
    fun contentScrolled(packageName: String?) {
        if (closed) return
        invalidate()
        val visit = current ?: return
        val host = if (visit.platform == "wechat") "com.tencent.mm" else "com.ss.android.ugc.aweme"
        if (packageName != host) return
        val boundary = boundary(visit.id, elapsed(), wall(), VideoExitReason.PAGE_CHANGED)
        worker.post { end(boundary) }
    }

    fun windowChanged(packageName: String?, windowId: Int, topologyOnly: Boolean = false) {
        val generation = epoch.incrementAndGet()
        val visit = current ?: return
        if (closed) return
        val eventElapsed = elapsed()
        val eventWall = wall()
        val eventConsent = consentEpoch()
        val eventPolicy = policyEpoch()
        worker.post {
            if (closed || current?.id != visit.id || !flushEnd()) return@post
            val host = if (visit.platform == "wechat") "com.tencent.mm" else "com.ss.android.ugc.aweme"
            val evidence = try { readForeground(packageName, windowId) }
                catch (_: Exception) { onError(); VideoForegroundEvidence(VideoWindowKind.UNKNOWN) }
            if (evidence.kind == VideoWindowKind.OVERLAY &&
                (evidence.packageName == null || evidence.packageName == host)) return@post
            // 无包名的拓扑事件可能只是系统层开合；只有已知底层同一窗口才保留。
            if (topologyOnly && currentWindow != null && evidence.kind == VideoWindowKind.APPLICATION &&
                evidence.packageName == host && evidence.windowId == currentWindow &&
                eventConsent == currentConsent && eventPolicy == currentPolicy) return@post
            val exact = generation == epoch.get() && evidence.kind == VideoWindowKind.APPLICATION &&
                evidence.packageName == packageName && evidence.windowId == windowId
            if (evidence.kind == VideoWindowKind.APPLICATION && evidence.packageName == host) {
                end(boundary(visit.id, eventElapsed, eventWall,
                    if (exact) VideoExitReason.PAGE_CHANGED else VideoExitReason.INTERRUPTED, eventConsent, eventPolicy))
                return@post
            }
            val reason = if (!exact) VideoExitReason.INTERRUPTED
                else if (evidence.home) VideoExitReason.EXIT else VideoExitReason.BACKGROUND
            end(boundary(visit.id, eventElapsed, eventWall, reason, eventConsent, eventPolicy))
        }
    }

    fun interrupt() {
        if (closed) return
        invalidate()
        val id = current?.id ?: return
        val end = End(id, elapsed(), wall(), VideoExitReason.INTERRUPTED)
        worker.post { end(end) }
    }

    /** 在事件到达时冻结授权边界，后续重试不因撤授而改写此前可信的结束时刻。 */
    private fun boundary(id: String, at: Long, wallAt: Long, reason: VideoExitReason,
        consent: Long = consentEpoch(), policy: Long = policyEpoch()): End =
        End(id, at, wallAt, if (consent == currentConsent && policy == currentPolicy && allowed() &&
            current?.let { platformAllowed(it.platform) } == true) reason else VideoExitReason.INTERRUPTED)

    private fun end(value: End) {
        if (pending == null) pending = value
        flushEnd()
    }

    private fun flushEnd(): Boolean {
        val value = pending ?: return true
        return try {
            val completed = store.finish(value.id, value.elapsed, value.wall, value.reason)
            current = store.active()
            pending = null
            worker.removeCallbacks(retry)
            if (completed != null) wakeSync()
            true
        } catch (_: Exception) {
            onError()
            worker.removeCallbacks(retry)
            if (!closed) worker.postDelayed(retry, 30_000)
            false
        }
    }

    private fun wakeSync() {
        try { onCompleted() } catch (_: Exception) { onError() }
    }

    override fun close() {
        if (closed) return
        closed = true
        invalidate()
        context.unregisterReceiver(receiver)
        worker.post {
            worker.removeCallbacks(retry)
            try {
                current?.id?.let { if (pending == null) pending = End(it, 0, 0, VideoExitReason.INTERRUPTED) }
                flushEnd()
            } finally { store.close(); onClosed() }
        }
    }
}
