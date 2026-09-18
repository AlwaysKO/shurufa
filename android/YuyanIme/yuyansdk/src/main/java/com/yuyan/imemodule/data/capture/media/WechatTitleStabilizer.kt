package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.CapturePersistResult
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import java.util.UUID

internal class WechatTitleStabilizer : ConversationTitleStabilizer(
    ChatPlatform.WECHAT, "wechat-empty-tree", "on_device_title_ocr", legacyWechat = true,
)

internal fun unresolvedWechatScreenshotIdentity(title: String? = null): ScreenshotConversationIdentity {
    val key = "screenshot-pending:${UUID.randomUUID()}"
    return ScreenshotConversationIdentity(
        externalKey = key,
        displayName = "待确认会话 ${key.takeLast(8)}",
        conversationType = ConversationType.UNKNOWN,
        confidence = 0.0,
        source = "unresolved_title",
        status = "pending",
        observedTitle = title,
    )
}

/** 至多补看两帧；页面/会话改变时保留第一张自己的待确认身份，不能借用第二个联系人。 */
internal suspend fun confirmWechatScreenshotIdentity(
    first: ScreenshotConversationIdentity,
    observeNext: suspend () -> ScreenshotConversationIdentity?,
): ScreenshotConversationIdentity {
    var identity = first
    repeat(2) {
        if (identity.status == "confirmed") return identity
        val next = observeNext() ?: return identity
        if (next.externalKey != identity.externalKey) return identity
        identity = next
    }
    return identity
}

/** 首张先落盘；退出页面最多停止后续确认，不撤销/丢弃已获得的截图。 */
internal suspend fun persistScreenshotBeforeConfirmation(
    first: ScreenshotConversationIdentity,
    persist: suspend (ScreenshotConversationIdentity) -> CapturePersistResult,
    observeNext: suspend () -> ScreenshotConversationIdentity?,
): ScreenshotConversationIdentity {
    if (persist(first) == CapturePersistResult.FAILED) return first
    val confirmed = confirmWechatScreenshotIdentity(first, observeNext)
    if (confirmed.status == "confirmed" && confirmed != first) persist(confirmed)
    return confirmed
}
