package com.yuyan.imemodule.data.navigation

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.yuyan.imemodule.data.capture.media.WindowScreenshotResult
import com.yuyan.imemodule.data.capture.media.WindowScreenshotter
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.util.concurrent.atomic.AtomicLong

/** 地图单独识别，聊天采集器的支持范围保持不变。所有树读取、截图和编码都在IO执行。 */
internal class NavigationCapture(private val service: AccessibilityService) : Closeable {
    private data class Event(val value: AccessibilityEvent?, val epoch: Long, val consent: Long, val setting: Long, val at: Long)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val epoch = AtomicLong()
    private val events = Channel<Event>(24, BufferOverflow.DROP_OLDEST, onUndeliveredElement = { recycle(it.value) })
    private val session = NavigationSession()
    private var packageName: String? = null
    private var lastOverview: String? = null
    private var lastRead = 0L

    init {
        scope.launch {
            while (isActive) {
                val event = withTimeoutOrNull(30_000) { events.receive() }
                if (event == null) { session.expire(System.currentTimeMillis()); if (!session.hasPreview()) lastOverview = null; continue }
                try {
                    if (event.value == null || !current(event)) { abandon(); continue }
                    if (!ImageUploadRuntime.awaitInputIdle { current(event) }) { abandon(); continue }
                    val gap = 500 - (SystemClock.elapsedRealtime() - lastRead)
                    if (gap > 0) delay(gap)
                    if (current(event)) process(event)
                } catch (error: CancellationException) { throw error }
                catch (_: Exception) { /* 窗口不可读取时不将猜测结果入队；后续页面事件可重试。 */ }
                finally { recycle(event.value) }
            }
        }
    }

    fun onEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString().orEmpty()
        if (!NavigationSettings.enabled(service)) { if (packageName != null) reset(); return }
        if (NavigationPage.platform(pkg) == null) {
            if (packageName != null && event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && pkg != service.packageName) reset()
            return
        }
        if (packageName != pkg) { reset(); packageName = pkg }
        @Suppress("DEPRECATION") val copy = AccessibilityEvent.obtain(event)
        val item = Event(copy, epoch.get(), CollectionConsent.epoch, NavigationSettings.generation.get(), System.currentTimeMillis())
        if (events.trySend(item).isFailure) recycle(copy)
    }

    fun reset() {
        packageName = null
        val version = epoch.incrementAndGet()
        events.trySend(Event(null, version, CollectionConsent.epoch, NavigationSettings.generation.get(), System.currentTimeMillis()))
    }

    private fun current(event: Event) = event.epoch == epoch.get() && event.consent == CollectionConsent.epoch &&
        event.setting == NavigationSettings.generation.get() && NavigationSettings.enabled(service) &&
        !(service.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    @Suppress("DEPRECATION")
    private suspend fun process(event: Event) {
        val original = event.value ?: return
        val pkg = original.packageName?.toString() ?: return
        val platform = NavigationPage.platform(pkg) ?: return
        var startClickAt: Long? = null
        if (original.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val texts = original.text.map(CharSequence::toString) + listOfNotNull(original.contentDescription?.toString())
            val source = if (texts.any(NavigationPage::isStart)) null else original.source
            val start = try { texts.any(NavigationPage::isStart) || listOfNotNull(source?.text?.toString(), source?.contentDescription?.toString()).any(NavigationPage::isStart) }
            finally { source?.recycle() }
            if (start) { startClickAt = event.at; session.startClicked(platform, event.at) }
        }
        val before = readPage(pkg) ?: return
        lastRead = SystemClock.elapsedRealtime()
        val now = System.currentTimeMillis()
        session.expire(now)
        if (!session.hasPreview()) lastOverview = null
        when (val page = NavigationPage.parse(pkg, before.labels)) {
            is NavigationPage.Overview -> {
                val signature = before.labels.toString()
                if (signature == lastOverview && session.hasPreview()) return
                val previousClick = session.overviewChanged(page.route)
                val refreshClickAt = startClickAt ?: previousClick
                lastOverview = null
                val permit = ImageUploadRuntime.beginPreparation() ?: return
                try {
                    val image = withTimeoutOrNull(5_000) { WindowScreenshotter(service) { it == pkg }.capture(before.window, before.bounds) }
                    if (image !is WindowScreenshotResult.Success) return
                    try {
                        if (!current(event) || !ImageUploadRuntime.isInputIdle()) return
                        val after = readPage(pkg) ?: return
                        if (after.window != before.window || after.labels != before.labels) return
                        val stream = ByteArrayOutputStream()
                        val map = cropNavigationWindow(image, before.bounds) ?: return
                        try { if (!map.compress(Bitmap.CompressFormat.WEBP, 85, stream)) return }
                        finally { if (map !== image.bitmap) map.recycle() }
                        if (!current(event) || !ImageUploadRuntime.isInputIdle() || stream.size() > 3 * 1024 * 1024) return
                        session.preview(page.route, stream.toByteArray(), now, refreshClickAt)
                        lastOverview = signature
                    } finally { image.bitmap.recycle() }
                } finally { permit.close() }
            }
            is NavigationPage.Active -> {
                val record = session.confirm(page.platform, page.destination, now) ?: return
                val permit = ImageUploadRuntime.beginPreparation() ?: return
                try {
                    if (!current(event) || !ImageUploadRuntime.isInputIdle()) return
                    if (NavigationSync.outbox(service).enqueue(record) { current(event) && ImageUploadRuntime.isInputIdle() }) {
                        session.persisted(record.id); lastOverview = null
                        DataCollector.init(service.applicationContext)
                        DataCollector.requestSync()
                    }
                } finally { permit.close() }
            }
            NavigationPage.Other -> { session.transition(now); if (!session.hasPreview()) lastOverview = null }
        }
    }

    private data class Page(val window: Int, val bounds: IntRect, val labels: List<NavigationLabel>)
    @Suppress("DEPRECATION")
    private fun readPage(pkg: String): Page? {
        val root = service.rootInActiveWindow ?: return null
        try {
            if (root.packageName?.toString() != pkg) return null
            val bounds = Rect(); root.getBoundsInScreen(bounds)
            if (bounds.isEmpty) return null
            val labels = mutableListOf<NavigationLabel>()
            var visited = 0
            fun visit(node: AccessibilityNodeInfo, depth: Int) {
                if (depth > 30 || ++visited > 600 || !node.isVisibleToUser) return
                val text = node.text?.toString()?.take(600)
                val description = node.contentDescription?.toString()?.take(600)
                if (!text.isNullOrBlank() || !description.isNullOrBlank()) labels += NavigationLabel(node.viewIdResourceName, text, description)
                for (i in 0 until node.childCount) {
                    if (visited >= 600) break
                    val child = node.getChild(i) ?: continue
                    try { visit(child, depth + 1) } finally { child.recycle() }
                }
            }
            visit(root, 0)
            return Page(root.windowId, IntRect(bounds.left, bounds.top, bounds.right, bounds.bottom), labels)
        } finally { root.recycle() }
    }

    private fun abandon() { session.abandon(); lastOverview = null }
    override fun close() { epoch.incrementAndGet(); events.cancel(); scope.cancel() }
    companion object {
        @Suppress("DEPRECATION") private fun recycle(event: AccessibilityEvent?) { event?.recycle() }
    }
}

/** Android 11–13 返回整屏，必须裁到地图窗口，避免带入分屏中的其他应用。 */
internal fun cropNavigationWindow(image: WindowScreenshotResult.Success, bounds: IntRect): Bitmap? {
    val left = bounds.left - image.originX
    val top = bounds.top - image.originY
    val width = bounds.right - bounds.left
    val height = bounds.bottom - bounds.top
    if (left < 0 || top < 0 || width <= 0 || height <= 0 || left + width > image.bitmap.width || top + height > image.bitmap.height) return null
    return Bitmap.createBitmap(image.bitmap, left, top, width, height)
}
