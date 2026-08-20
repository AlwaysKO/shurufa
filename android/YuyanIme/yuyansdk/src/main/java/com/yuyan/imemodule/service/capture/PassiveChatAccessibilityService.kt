package com.yuyan.imemodule.service.capture

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent

/**
 * 只读被动采集入口。当前阶段仅筛选支持的窗口事件，不解析、不截图、不操作 UI。
 */
class PassiveChatAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in SUPPORTED_PACKAGES) return
        onSupportedWindowChanged(packageName, event.windowId)
    }

    override fun onInterrupt() = Unit

    private fun onSupportedWindowChanged(
        @Suppress("UNUSED_PARAMETER") packageName: String,
        @Suppress("UNUSED_PARAMETER") windowId: Int,
    ) = Unit

    private companion object {
        val SUPPORTED_PACKAGES = setOf(
            "com.tencent.mm",
            "com.tencent.mobileqq",
            "com.ss.android.ugc.aweme",
        )
    }
}
