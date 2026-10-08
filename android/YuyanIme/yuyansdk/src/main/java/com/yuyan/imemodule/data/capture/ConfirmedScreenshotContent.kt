package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.media.ScreenshotContentInput
import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentity
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.stableKeyOrNull

/** 只使用当前截图的已确认标题；后帧归属回填不能为旧图提供内容去重依据。 */
internal fun ScreenshotConversationIdentity.confirmedContent(input: ScreenshotContentInput?,
    onReason: (ScreenshotContentReason) -> Unit = {
        CaptureTrace.record(CaptureStage.CONTENT_DECISION, layer = CaptureLayer.COORDINATOR, reason = it)
    }): Map<Int, ScreenshotContentEvidence> {
    fun reject(reason: ScreenshotContentReason): Map<Int, ScreenshotContentEvidence> {
        onReason(reason)
        return emptyMap()
    }
    if (isWechatConversationList()) return reject(ScreenshotContentReason.CONVERSATION_LIST)
    if (!isChatPage || status != "confirmed" || confidence < .8 || exactTitleHash.isNullOrBlank())
        return reject(ScreenshotContentReason.IDENTITY_UNVERIFIED)
    if (!previousKey.isNullOrBlank()) return reject(ScreenshotContentReason.CONFIRMATION_REPLAY)
    val blocks = input?.blocks ?: return reject(input?.reason ?: ScreenshotContentReason.NOT_REQUESTED)
    val key = CapturedConversation(ChatPlatform.WECHAT, "wechat-empty-tree", externalKey, displayName,
        conversationType, confidence).stableKeyOrNull() ?: return reject(ScreenshotContentReason.IDENTITY_UNVERIFIED)
    onReason(ScreenshotContentReason.READY)
    return mapOf(0 to ScreenshotContentEvidence(key, exactTitleHash, blocks))
}
