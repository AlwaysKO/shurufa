package com.yuyan.imemodule.data.redpacket

import org.junit.Assert.*
import org.junit.Test

class SilentPacketOperationalStatusTest {
    private val ready = SilentPacketSetupSnapshot(true, true, true, true, true, true)
    private val running = SilentPacketOperationalState("AUTO", ready, true, true, true, true, true, false)
    private fun render(state: SilentPacketOperationalState) = renderSilentPacketOperationalState(state)

    @Test fun persistedOffOverridesConnectedOrHistoricalRunningBackend() {
        val status = render(running.copy(mode = "OFF", backendState = "RUNNING", taskRunning = true))
        assertTrue(status.contains("已关闭")); assertTrue(status.contains("OFF"))
        assertFalse(status.contains("正在监听")); assertFalse(status.contains("正在副屏处理"))
    }

    @Test fun missingAuthorizationCannotBePresentedAsListeningDespiteConnectedBinder() {
        for ((setup, expected) in listOf(ready.copy(notificationAuthorized = false) to "通知使用权",
            ready.copy(accessibilityAuthorized = false) to "无障碍", ready.copy(shizukuRunning = false) to "Shizuku")) {
            val status = render(running.copy(setup = setup, backendState = "IDLE"))
            assertTrue(status.contains(expected)); assertFalse(status.contains("正在监听"))
        }
    }

    @Test fun authorizedButUnboundListenersAreExplicitlyNotRunning() {
        val notification = render(running.copy(notificationConnected = false))
        assertTrue(notification.contains("通知服务未连接")); assertFalse(notification.contains("正在监听"))
        val accessibility = render(running.copy(accessibilityConnected = false))
        assertTrue(accessibility.contains("无障碍服务未连接")); assertFalse(accessibility.contains("正在监听"))
    }

    @Test fun readySetupDoesNotMeanApplicationBinderIsConnected() {
        val stopped = render(running.copy(serviceConnected = false))
        assertTrue(stopped.contains("未运行")); assertTrue(stopped.contains("连接"))
        assertFalse(stopped.contains("正在监听"))
        val connecting = render(running.copy(serviceConnected = false, connecting = true))
        assertTrue(connecting.contains("正在连接")); assertFalse(connecting.contains("正在监听"))
    }

    @Test fun protectionOverridesRunningBackendAndExplainsActualPause() {
        for ((state, expected) in listOf(running.copy(locked = true) to "锁屏",
            running.copy(interactive = false) to "熄屏", running.copy(foregroundVerified = false) to "前台")) {
            val status = render(state.copy(backendState = "RUNNING", taskRunning = true))
            assertTrue(status.contains("暂停")); assertTrue(status.contains(expected))
            assertFalse(status.contains("正在监听")); assertFalse(status.contains("正在副屏处理红包"))
        }
    }

    @Test fun onlyLiveTaskClaimsProcessingAndFullyReadyIdleClaimsListening() {
        assertTrue(render(running.copy(backendState = "STOPPED")).contains("正在监听红包通知"))
        assertTrue(render(running.copy(taskRunning = true)).contains("正在副屏处理红包"))
        val diagnostic = render(running.copy(backendState = "RUNNING"))
        assertFalse(diagnostic.contains("正在副屏处理红包")); assertFalse(diagnostic.contains("正在监听"))
        assertTrue(render(running.copy(backendState = "SECONDARY_UNVERIFIED")).contains("副屏"))
        assertFalse(render(running.copy(backendState = "SECONDARY_UNVERIFIED")).contains("正在监听"))
        val probe = render(running.copy(mode = "PROBE", taskRunning = true))
        assertTrue(probe.contains("识别红包（不领取）")); assertFalse(probe.contains("正在副屏处理红包"))
    }

    @Test fun rejectedSaveReportsActualPersistedModeAndNeverClaimsSaved() {
        val status = silentPacketSaveRejected("OFF", "请开启无障碍服务")
        assertTrue(status.contains("未保存")); assertTrue(status.contains("当前已保存模式：关闭（OFF）"))
        assertTrue(status.contains("请开启无障碍服务"))
        assertTrue(silentPacketSaveRejected("PROBE", "连接已断开").contains("只识别（PROBE）"))
    }
}
