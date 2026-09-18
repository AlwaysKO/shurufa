package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ChatPlatform

private val TRANSIENT_TITLES = setOf("正在输入", "正在输入中", "对方正在输入", "在线", "离线", "手机在线", "忙碌", "连接中")
internal fun isTransientConversationTitle(raw: String?): Boolean =
    raw?.trim()?.trimEnd('.', '。', '…', ' ') in TRANSIENT_TITLES

/** 只剥离明确状态行/尾部人数；保留昵称中的括号文字，不做繁简/近似名字全局匹配。 */
internal fun normalizeConversationTitle(raw: String?, platform: ChatPlatform): String? {
    val lines = raw?.trim()?.lines()?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    if (lines.isEmpty() || isTransientConversationTitle(lines.first())) return null
    if (lines.drop(1).any { !isTransientConversationTitle(it) }) return null
    val value = lines.first().replace(Regex("[（(]\\s*\\d+\\s*人\\s*[）)]$"), "").trim()
    val appName = when (platform) { ChatPlatform.WECHAT -> "微信"; ChatPlatform.QQ -> "QQ"; ChatPlatform.DOUYIN -> "抖音" }
    return value.takeUnless { it.isBlank() || it in setOf(appName, "返回", "消息", "搜索", "聊天设置", "私信", "评论", "直播", "通知") }
}
