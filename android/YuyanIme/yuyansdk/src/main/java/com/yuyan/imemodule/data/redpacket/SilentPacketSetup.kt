package com.yuyan.imemodule.data.redpacket

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import rikka.shizuku.Shizuku

internal enum class SilentPacketSetupStep { SYSTEM_UNSUPPORTED, INSTALL_SHIZUKU, START_SHIZUKU, AUTHORIZE_SHIZUKU, NOTIFICATION, ACCESSIBILITY, READY }
internal data class SilentPacketSetupSnapshot(val supported: Boolean, val shizukuInstalled: Boolean,
    val shizukuRunning: Boolean, val shizukuAuthorized: Boolean,
    val notificationAuthorized: Boolean, val accessibilityAuthorized: Boolean) {
    val nextStep: SilentPacketSetupStep get() = when {
        !supported -> SilentPacketSetupStep.SYSTEM_UNSUPPORTED
        !shizukuInstalled -> SilentPacketSetupStep.INSTALL_SHIZUKU
        !shizukuRunning -> SilentPacketSetupStep.START_SHIZUKU
        !shizukuAuthorized -> SilentPacketSetupStep.AUTHORIZE_SHIZUKU
        !notificationAuthorized -> SilentPacketSetupStep.NOTIFICATION
        !accessibilityAuthorized -> SilentPacketSetupStep.ACCESSIBILITY
        else -> SilentPacketSetupStep.READY
    }
    fun render(): String = when (nextStep) {
        SilentPacketSetupStep.SYSTEM_UNSUPPORTED -> "静默副屏需要 Android 15 或以上，当前系统不支持。"
        SilentPacketSetupStep.INSTALL_SHIZUKU -> "请先从 Shizuku 官方页面安装 Shizuku，再返回检查。输入法不会自动下载或安装。"
        SilentPacketSetupStep.START_SHIZUKU -> "请打开 Shizuku，通过无线调试完成首次配对并启动；手机重启后需要重新启动 Shizuku。当前功能需要无线调试的 shell 模式。"
        SilentPacketSetupStep.AUTHORIZE_SHIZUKU -> "Shizuku 已启动，请点击连接并在弹窗中允许输入法使用 Shizuku。"
        SilentPacketSetupStep.NOTIFICATION -> "请开启输入法的通知使用权，用于接收微信红包通知。没有通知时不会触发。"
        SilentPacketSetupStep.ACCESSIBILITY -> "请开启输入法的无障碍服务，用于输入和游戏避让，再返回检查。"
        SilentPacketSetupStep.READY -> "准备项已齐全，可以连接静默服务并保存模式。是否运行以服务状态为准；不同手机仍需实际验证。"
    }
}

/** 只检查准备条件、构造或打开用户选择的入口，不申请权限、不下载、不安装。 */
internal object SilentPacketSetup {
    const val DOWNLOAD_URL = "https://shizuku.rikka.app/zh-hans/download/"
    const val GUIDE_URL = "https://shizuku.rikka.app/zh-hans/guide/setup/"
    private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    fun collect(context: Context): SilentPacketSetupSnapshot {
        // Shizuku API最低版本高于输入法；旧系统不进入或初始化其访问类。
        if (Build.VERSION.SDK_INT < 35) return SilentPacketSetupSnapshot(false, false, false, false, false, false)
        val installed = runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getApplicationInfo(SHIZUKU_PACKAGE, 0)
            true
        }.getOrDefault(false)
        val running = installed && runCatching { ShizukuAccess.running() }.getOrDefault(false)
        val authorized = running && runCatching { ShizukuAccess.authorized() }.getOrDefault(false)
        return SilentPacketSetupSnapshot(true, installed, running, authorized,
            runCatching { PacketSettings.hasNotifications(context) }.getOrDefault(false),
            runCatching { PacketSettings.hasAccessibility(context) }.getOrDefault(false))
    }

    private object ShizukuAccess {
        fun running(): Boolean = Shizuku.pingBinder() && Shizuku.getUid() == 2000
        fun authorized(): Boolean = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }

    fun downloadIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(DOWNLOAD_URL))
    fun guideIntent(): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(GUIDE_URL))
    fun developerSettingsIntent(): Intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
    fun notificationSettingsIntent(): Intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
    fun accessibilitySettingsIntent(): Intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun shizukuIntent(context: Context): Intent? = runCatching {
        context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)
    }.getOrNull()

    /** null表示已打开；否则返回可直接展示的手工操作说明。 */
    fun open(context: Context, step: SilentPacketSetupStep): String? {
        val intent: Intent?
        val fallback: String
        when (step) {
            SilentPacketSetupStep.INSTALL_SHIZUKU -> {
                intent = downloadIntent()
                fallback = "未找到可打开官方页面的浏览器，请手动访问 $DOWNLOAD_URL 安装 Shizuku。"
            }
            SilentPacketSetupStep.START_SHIZUKU, SilentPacketSetupStep.AUTHORIZE_SHIZUKU -> {
                intent = shizukuIntent(context)
                fallback = "未找到 Shizuku 启动入口，请先安装并从桌面手动打开 Shizuku；无线调试启动指南：$GUIDE_URL"
            }
            SilentPacketSetupStep.NOTIFICATION -> {
                intent = notificationSettingsIntent()
                fallback = "未找到通知使用权入口，请在系统设置中搜索“通知使用权”，开启输入法。"
            }
            SilentPacketSetupStep.ACCESSIBILITY -> {
                intent = accessibilitySettingsIntent()
                fallback = "未找到无障碍入口，请在系统设置中搜索“无障碍”，开启输入法服务。"
            }
            SilentPacketSetupStep.SYSTEM_UNSUPPORTED -> return "静默副屏需要 Android 15 或以上，当前系统不支持。"
            SilentPacketSetupStep.READY -> return "准备项已齐全，请连接静默服务并保存模式。"
        }
        return openIntent(context, intent, fallback)
    }

    fun openGuide(context: Context): String? = openIntent(context, guideIntent(),
        "未找到浏览器，请手动访问 Shizuku 官方无线调试指南：$GUIDE_URL")

    fun openDeveloperSettings(context: Context): String? = openIntent(context, developerSettingsIntent(),
        "未找到开发者选项入口，请在系统设置中手动开启开发者选项，再进入无线调试完成 Shizuku 配对。")

    private fun openIntent(context: Context, intent: Intent?, fallback: String): String? {
        if (intent == null) return fallback
        return runCatching {
            if (intent.resolveActivity(context.packageManager) == null) return fallback
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            null
        }.getOrElse { fallback }
    }
}
