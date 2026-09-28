package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.media.ScreenshotContentInput
import com.yuyan.imemodule.data.capture.media.ScreenshotConversationIdentity
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.stableKeyOrNull

/** 只使用当前截图的已确认标题；后帧归属回填不能为旧图提供内容去重依据。 */
internal fun ScreenshotConversationIdentity.confirmedContent(input: ScreenshotContentInput?): Map<Int, ScreenshotContentEvidence> {
    if (!isChatPage || isWechatConversationList() || status != "confirmed" || confidence < .8 ||
        !previousKey.isNullOrBlank() || exactTitleHash.isNullOrBlank()) return emptyMap()
    val blocks = input?.blocks ?: return emptyMap()
    val key = CapturedConversation(ChatPlatform.WECHAT, "wechat-empty-tree", externalKey, displayName,
        conversationType, confidence).stableKeyOrNull() ?: return emptyMap()
    return mapOf(0 to ScreenshotContentEvidence(key, exactTitleHash, blocks))
}
