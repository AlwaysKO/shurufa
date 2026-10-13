package com.yuyan.imemodule.data.redpacket

internal data class SilentPacketOperationalState(
    val mode: String, val setup: SilentPacketSetupSnapshot,
    val notificationConnected: Boolean, val accessibilityConnected: Boolean,
    val serviceConnected: Boolean, val foregroundVerified: Boolean,
    val interactive: Boolean, val locked: Boolean,
    val connecting: Boolean = false, val taskRunning: Boolean = false,
    val backendState: String? = null,
)

private fun silentPacketModeLabel(mode: String): String = when (mode) {
    "AUTO" -> "自动领取（AUTO）"
    "PROBE" -> "只识别（PROBE）"
    else -> "关闭（OFF）"
}

internal fun renderSilentPacketOperationalState(state: SilentPacketOperationalState): String {
    val status = when {
        state.mode !in setOf("AUTO", "PROBE") -> "已关闭"
        state.setup.nextStep != SilentPacketSetupStep.READY -> "未运行；${state.setup.render()}"
        !state.notificationConnected -> "未运行；通知服务未连接，请检查通知使用权后返回"
        !state.accessibilityConnected -> "未运行；无障碍服务未连接，请检查无障碍服务后返回"
        !state.serviceConnected && state.connecting -> "未运行；正在连接 Shizuku 静默服务"
        !state.serviceConnected -> "未运行；请连接 Shizuku 静默服务"
        !state.interactive -> "保护暂停：手机已熄屏"
        state.locked -> "保护暂停：手机已锁屏"
        state.backendState == "MAIN_WECHAT_IN_USE" -> "保护暂停：正在使用接收方微信"
        !state.foregroundVerified -> "保护暂停：前台尚未确认"
        state.backendState == "SECONDARY_UNVERIFIED" -> "未运行；副屏兼容性尚未确认，请查看技术诊断"
        state.taskRunning && state.mode == "PROBE" -> "正在副屏识别红包（不领取）"
        state.taskRunning -> "正在副屏处理红包"
        state.backendState == "RUNNING" -> "副屏会话进行中；当前没有自动领取任务"
        state.mode == "PROBE" -> "正在监听红包通知（只识别，不领取）"
        else -> "正在监听红包通知"
    }
    return "当前已保存模式：${silentPacketModeLabel(state.mode)}\n实际状态：$status"
}

internal fun silentPacketSaveRejected(persistedMode: String, reason: String): String =
    "未保存：$reason\n当前已保存模式：${silentPacketModeLabel(persistedMode)}"
