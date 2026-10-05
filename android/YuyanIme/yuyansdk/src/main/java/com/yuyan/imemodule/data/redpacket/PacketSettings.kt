package com.yuyan.imemodule.data.redpacket

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService

internal object PacketServiceState {
    @Volatile var accessibilityConnected = false
    @Volatile var notificationConnected = false
}

internal object PacketSettings {
    const val KEY = "wechat_group_red_packets_enabled"
    fun enabled(context: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)
    fun setEnabled(context: Context, enabled: Boolean) {
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, enabled).apply()
        if (!enabled) GroupRedPacketAssistant.cancel("已关闭", restoreScreen = false)
    }
    fun hasNotifications(context: Context): Boolean =
        context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    fun hasAccessibility(context: Context): Boolean {
        val expected = ComponentName(context, PassiveChatAccessibilityService::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
    }
    fun status(context: Context): String = when {
        Build.VERSION.SDK_INT < 28 -> "红包助手需要 Android 9 或以上系统"
        !enabled(context) -> "未开启"
        Build.VERSION.SDK_INT <= 29 && context.getSystemService(ActivityManager::class.java).isLowRamDevice ->
            "当前低内存设备系统不支持通知监听，无法通过通知触发"
        !hasNotifications(context) -> "已开启，等待通知使用权"
        !hasAccessibility(context) -> "已开启，等待无障碍服务"
        !PacketServiceState.notificationConnected -> "已开启，等待通知服务连接"
        !PacketServiceState.accessibilityConnected -> "已开启，等待无障碍服务连接"
        else -> "已开启；${GroupRedPacketAssistant.status}"
    }
}
