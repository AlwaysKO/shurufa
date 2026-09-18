package com.yuyan.imemodule.service.capture

import android.view.accessibility.AccessibilityEvent

/** 输入、发送和内容更新不是切换联系人；返回、点会话行、窗口切换须丢弃连续性假设。 */
internal fun shouldResetScreenshotIdentity(packageName: String?, eventType: Int, text: String, className: String? = null): Boolean =
    eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        (packageName in ACCESSIBILITY_CHAT_EVENT_PACKAGES && eventType == AccessibilityEvent.TYPE_VIEW_CLICKED &&
            !text.contains("发送") && !text.contains("转文字") &&
            !(packageName in setOf("com.tencent.mm", "com.ss.android.ugc.aweme") && className.orEmpty().endsWith("EditText")))

// 保留旧入口供微信分支与已有测试使用，同一套导航边界规则服务所有已支持 App。
internal fun shouldResetWechatScreenshotIdentity(packageName: String?, eventType: Int, text: String): Boolean =
    shouldResetScreenshotIdentity(packageName, eventType, text)
