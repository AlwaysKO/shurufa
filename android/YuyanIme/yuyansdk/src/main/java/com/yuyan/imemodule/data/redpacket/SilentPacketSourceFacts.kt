package com.yuyan.imemodule.data.redpacket

import com.yuyan.imemodule.data.capture.ui.IntRect

internal enum class SilentPacketFixedKeyword(val phrase: String) {
    UNSUPPORTED("不支持"), UNABLE("无法"), LOGIN("登录"), DEVICE("设备"), ENVIRONMENT("环境"),
    SPLIT_SCREEN("分屏"), MULTI_INSTANCE("多开"), EMULATOR("模拟器"), SECURITY("安全"), EXIT("退出"),
    RETRY("重新"), PHONE("手机"), COMPUTER("电脑"), NETWORK("网络"), VERIFICATION("验证"),
    RISK("风险"), PRIMARY_DISPLAY("主屏"), SECONDARY_DISPLAY("副屏");
    val bit: Long get() = 1L shl ordinal
}

/** 诊断仅保留几何候选计数及固定标签是否出现，不保留任何OCR文本或坐标。 */
internal data class SilentPacketSourceFacts(val titleCandidateCount: Int, val menuDotCount: Int,
    val groupSuffixCount: Int, val bottomWechatTabsPresent: Boolean, val orangeRegionCount: Int,
    val loginPromptPresent: Boolean = false, val loadingPromptPresent: Boolean = false,
    val networkErrorPresent: Boolean = false, val permissionPromptPresent: Boolean = false,
    val chatComposerPresent: Boolean = false, val recognizedLineCount: Int = 0,
    val contentLineCount: Int = 0, val wechatTitleLabelPresent: Boolean = false,
    val fixedKeywordMask: Long = 0) {
    fun fixedKeywordNames(): List<String> = SilentPacketFixedKeyword.entries
        .filter { fixedKeywordMask and it.bit != 0L }.map { it.name }
}

internal fun silentPacketSourceFacts(frame: PacketVisualFrame): SilentPacketSourceFacts {
    if (frame.width <= 0 || frame.height <= 0) return SilentPacketSourceFacts(0, 0, 0, false, 0)
    val width = frame.width.toDouble()
    val height = frame.height.toDouble()
    fun valid(rect: IntRect) = rect.left >= 0 && rect.top >= 0 && rect.right <= frame.width &&
        rect.bottom <= frame.height && rect.right > rect.left && rect.bottom > rect.top
    val lines = frame.lines.filter { valid(it.bounds) && it.text.isNotBlank() }
    // 与parsePacketVisualFrame现有topTitle条件一致；此处只解释诊断，不影响解析或领取资格。
    val titles = lines.filter { line -> line.bounds.let {
        (it.top + (it.bottom - it.top) / 2.0) in height * .025..height * .14 &&
            it.left >= width * .10 && it.right <= width * .90 &&
            it.bottom - it.top <= height * .065 && it.right - it.left >= width * .055
    } }
    val suffix = Regex("^(.*?)\\s*[（(]\\s*([0-9]{1,5})\\s*[）)]\\s*$")
    val groups = titles.count { line ->
        suffix.matchEntire(line.text.trim())?.let {
            it.groupValues[1].isNotBlank() && (it.groupValues[2].toIntOrNull() ?: 0) >= 2
        } == true
    }
    val words = lines.map { it.text.replace(Regex("\\s+"), "") }.toSet()
    val login = words.any { it in setOf("登录", "注册", "用手机号登录", "使用其他方式登录") } ||
        ("微信安全中心" in words && "当前设备" in words)
    val loading = words.any { it in setOf("正在加载", "请稍候", "加载中") }
    val network = words.any { it in setOf("网络连接不可用", "网络连接失败", "无法连接到服务器") }
    val permission = words.any { it in setOf("仅在使用该应用时允许", "隐私保护指引", "微信隐私保护指引") } ||
        ("允许" in words && "不允许" in words)
    val composer = lines.any { line ->
        val rect = line.bounds
        val word = line.text.replace(Regex("\\s+"), "")
        val bottomControl = rect.top >= height * .80 && rect.bottom - rect.top <= height * .10
        bottomControl && when (word) {
            "发送" -> rect.left >= width * .70 && rect.right <= width * .98 && rect.right - rect.left <= width * .25
            "按住说话", "切换到按住说话" -> rect.left >= width * .10 && rect.right <= width * .90 &&
                rect.right - rect.left >= width * .20
            else -> false
        }
    }
    val bottomTabs = lines.filter { (it.bounds.top + (it.bounds.bottom - it.bounds.top) / 2.0) >= height * .80 }
        .map { it.text.replace(Regex("\\s+"), "") }
        .filter { it in setOf("微信", "通讯录", "发现", "我") }.toSet().size >= 3
    val contentLines = lines.count { line ->
        (line.bounds.top + (line.bounds.bottom - line.bounds.top) / 2.0) in height * .16..height * .80
    }
    val wechatTitle = titles.any { Regex("^微信(?:[（(][0-9]{1,5}[）)])?$")
        .matches(it.text.replace(Regex("\\s+"), "")) }
    // 固定词出现只供诊断，不代表登录页、错误页、聊天页或任何操作资格。
    val keywordMask = SilentPacketFixedKeyword.entries.fold(0L) { mask, keyword ->
        if (words.any { keyword.phrase in it }) mask or keyword.bit else mask
    }
    return SilentPacketSourceFacts(titles.size, frame.menuDots.count(::valid), groups,
        bottomTabs, frame.orangeRegions.count(::valid), login, loading, network, permission, composer,
        lines.size, contentLines, wechatTitle, keywordMask)
}
