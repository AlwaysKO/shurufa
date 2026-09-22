package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ChatPlatform

private val TRANSIENT_TITLES = setOf("正在输入", "正在输入中", "对方正在输入", "在线", "离线", "手机在线", "忙碌", "连接中")
internal fun isTransientConversationTitle(raw: String?): Boolean {
    val text = java.text.Normalizer.normalize(raw.orEmpty(), java.text.Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), "").trimEnd('.', '。', '…', '•', '·', ':', '：')
    return text in TRANSIENT_TITLES || text == "对方正在输入中"
}

/** 仅修正已验证的微信固定页面别名，不对联系人做编辑距离/同音模糊匹配。 */
internal fun canonicalWechatPageTitle(raw: String?): String? {
    val text = java.text.Normalizer.normalize(raw.orEmpty().trim(), java.text.Normalizer.Form.NFKC)
        .replace(Regex("^微信\\s*\\(\\d+\\)$"), "微信")
    return when (text) {
        "朋友圈", "朋友屠", "用友殿", "田友殿" -> "朋友圈"
        "微信", "微佳" -> "微信"
        "发现", "发机" -> "发现"
        else -> null
    }
}

/** 只剥离明确状态行/尾部人数；保留昵称中的括号文字，不做繁简/近似名字全局匹配。 */
internal fun normalizeConversationTitle(raw: String?, platform: ChatPlatform): String? {
    val lines = raw?.trim()?.lines()?.map(String::trim)?.filter(String::isNotEmpty).orEmpty()
    if (lines.isEmpty() || isTransientConversationTitle(lines.first())) return null
    if (lines.drop(1).any { !isTransientConversationTitle(it) }) return null
    val value = lines.first().replace(Regex("[（(]\\s*\\d+\\s*人\\s*[）)]$"), "").trim()
    val appName = when (platform) { ChatPlatform.WECHAT -> "微信"; ChatPlatform.QQ -> "QQ"; ChatPlatform.DOUYIN -> "抖音" }
    return value.takeUnless { it.isBlank() || it in setOf(appName, "返回", "消息", "搜索", "聊天设置", "私信", "评论", "直播", "通知") }
}
