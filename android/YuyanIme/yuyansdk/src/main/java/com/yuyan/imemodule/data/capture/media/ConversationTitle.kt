package com.yuyan.imemodule.data.capture.media

import com.yuyan.imemodule.data.capture.model.ChatPlatform

private val TRANSIENT_TITLES = setOf("正在输入", "正在输入中", "对方正在输入", "在线", "离线", "手机在线", "忙碌", "连接中")
internal fun isPeerTypingConversationTitle(raw: String?): Boolean =
    java.text.Normalizer.normalize(raw.orEmpty(), java.text.Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), "").startsWith("对方正在输入")

internal fun isTransientConversationTitle(raw: String?): Boolean {
    val text = java.text.Normalizer.normalize(raw.orEmpty(), java.text.Normalizer.Form.NFKC)
        .replace(Regex("\\s+"), "").trimEnd('.', '。', '…', '•', '·', ':', '：')
    return isPeerTypingConversationTitle(text) || text in TRANSIENT_TITLES
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
    val first = if (platform == ChatPlatform.WECHAT) stripWechatTitleDecoration(lines.first()) else lines.first()
    val value = first.replace(Regex("[（(]\\s*\\d+\\s*人\\s*[）)]$"), "").trim()
    val appName = when (platform) { ChatPlatform.WECHAT -> "微信"; ChatPlatform.QQ -> "QQ"; ChatPlatform.DOUYIN -> "抖音" }
    return value.takeUnless { it.isBlank() || it in setOf(appName, "返回", "消息", "搜索", "聊天设置", "私信", "评论", "直播", "通知") }
}

/** 只清理姓名末尾装饰；正常中英数字、内部标点及截断省略号仍保留。 */
internal fun stripWechatTitleDecoration(raw: String): String {
    var end = raw.trimEnd().length
    while (end > 0) {
        val cp = Character.codePointBefore(raw, end)
        if (cp == 0x2026 || raw.substring(0, end).endsWith("...")) break
        if (cp == 0x20E3) { // 键帽的数字也是 Emoji 的一部分，不能残留成姓名数字。
            end -= Character.charCount(cp)
            if (end > 0 && Character.codePointBefore(raw, end) == 0xFE0F) end--
            if (end > 0 && raw[end - 1] in "0123456789#*") end--
            continue
        }
        if (cp == 0xFE0F && end > 1 && Character.codePointBefore(raw, end - 1) == 0x2139) {
            end -= 2 // ℹ️ 的底字在 Unicode 中是字母，须随表情呈现选择符一起移除。
            continue
        }
        val variation = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
        val type = Character.getType(cp)
        if (!variation && (Character.isLetterOrDigit(cp) ||
                type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt())) break
        // 有内容的括号属于姓名，例如“张三（项目A）”，不把闭括号单独剪掉。
        val opening = when (cp) { ')'.code -> '('; '）'.code -> '（'; ']'.code -> '['; '】'.code -> '【'; else -> null }
        if (opening != null) {
            val start = raw.lastIndexOf(opening, end - 1)
            var offset = start + 1
            var hasText = false
            while (start >= 0 && offset < end - 1) {
                val inside = Character.codePointAt(raw, offset)
                if (Character.isLetterOrDigit(inside)) { hasText = true; break }
                offset += Character.charCount(inside)
            }
            if (hasText) break
        }
        end -= Character.charCount(cp)
    }
    return raw.substring(0, end).trimEnd()
}
