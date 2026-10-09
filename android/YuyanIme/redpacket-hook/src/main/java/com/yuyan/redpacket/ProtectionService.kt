package com.yuyan.redpacket

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/** 只读取窗口类型/所属包，不读取页面文本、截图或执行任何点击。 */
class ProtectionService : AccessibilityService() {
    private val gate = ForegroundProtection()
    private val handler = Handler(Looper.getMainLooper())
    private var reportedAllowed = false
    private val cooldown = Runnable { publish() }
    override fun onServiceConnected() { instance = this; publish() }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { publish() }
    override fun onInterrupt() { disconnect() }
    override fun onDestroy() { disconnect(); super.onDestroy() }
    private fun disconnect() {
        instance = null
        handler.removeCallbacks(cooldown)
        contentResolver.notifyChange(PacketProvider.PROTECTION_URI, null)
    }
    private fun publish() {
        val allowed = sample()
        if (allowed != reportedAllowed) {
            reportedAllowed = allowed
            contentResolver.notifyChange(PacketProvider.PROTECTION_URI, null)
        }
        handler.removeCallbacks(cooldown)
        // 仅在有可信解除证据后安排一次冷却恢复；游戏/未知状态无循环定时器。
        if (!allowed && gate.canResume) handler.postDelayed(cooldown, 3100)
    }
    private fun sample(): Boolean = runCatching {
        val power = getSystemService(PowerManager::class.java)
        val visible = windows.orEmpty()
        val typing = visible.any { it.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD }
        val foreground = visible.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && (it.isActive || it.isFocused) }
            ?.root?.let { node -> try { node.packageName?.toString() } finally { node.recycle() } }
        val state = if (foreground == null) Foreground.UNKNOWN else classify(foreground)
        gate.allow(SystemClock.elapsedRealtime(), power.isInteractive, state, typing)
    }.getOrElse {
        gate.allow(SystemClock.elapsedRealtime(), true, Foreground.UNKNOWN, false)
        false
    }
    private fun classify(pkg: String): Foreground {
        val ime = Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')
        if (pkg == packageName || pkg == ime || pkg == "android") return Foreground.OVERLAY
        val info = runCatching { packageManager.getApplicationInfo(pkg, 0) }.getOrNull() ?: return Foreground.UNKNOWN
        val extras = getSharedPreferences("packet", 0).getStringSet("games", emptySet()).orEmpty()
        if (pkg in extras || info.category == ApplicationInfo.CATEGORY_GAME) return Foreground.GAME
        val home = packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        if (info.flags and ApplicationInfo.FLAG_SYSTEM != 0 && pkg != home) return Foreground.OVERLAY
        return Foreground.APP
    }
    companion object {
        @Volatile private var instance: ProtectionService? = null
        fun allowed(): Boolean = instance?.sample() ?: false
    }
}
