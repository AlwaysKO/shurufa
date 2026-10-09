package com.yuyan.imemodule.data.redpacket

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.SwitchPreferenceCompat

internal class PacketPreferences(private val context: Context, screen: PreferenceScreen) {
    private val toggle = SwitchPreferenceCompat(context).apply {
        key = PacketSettings.KEY
        title = "自动抢微信群红包（实验）"
        isPersistent = false
        isEnabled = Build.VERSION.SDK_INT >= 28
        setOnPreferenceChangeListener { _, value ->
            PacketSettings.setEnabled(context, value == true)
            refresh()
            false
        }
    }
    private val silent = Preference(context).apply {
        title = "静默抢红包（Shizuku）"
        setOnPreferenceClickListener {
            context.startActivity(Intent(context, SilentPacketSettingsActivity::class.java))
            true
        }
    }
    init {
        screen.addPreference(silent)
        screen.addPreference(toggle)
        screen.addPreference(Preference(context).apply {
            title = "红包助手权限设置"
            summary = "通知使用权与无障碍均需开启；在系统应用、电池或启动管理中允许后台运行，具体名称因手机而异"
            setOnPreferenceClickListener {
                androidx.appcompat.app.AlertDialog.Builder(context)
                    .setTitle("红包助手权限")
                    .setItems(arrayOf("通知使用权", "无障碍服务", "应用与后台运行设置")) { _, index ->
                        val intent = when (index) {
                            0 -> Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                            1 -> Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            else -> Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.parse("package:${context.packageName}"))
                        }
                        runCatching { context.startActivity(intent) }.onFailure {
                            Toast.makeText(context, "请在系统设置中手动开启对应权限", Toast.LENGTH_LONG).show()
                        }
                    }.show()
                true
            }
        })
        refresh()
    }
    fun refresh() {
        val mode = SilentPacketSettings.mode(context)
        silent.summary = if (Build.VERSION.SDK_INT < 35) "静默副屏需要 Android 15 或以上系统" else if (mode == "OFF") "已关闭；在副屏处理，不切换主屏。首次需要 Shizuku 授权" else "当前模式：$mode；${SilentPacketRuntime.status()}"
        toggle.isEnabled = Build.VERSION.SDK_INT >= 28 && mode == "OFF"
        toggle.isChecked = PacketSettings.enabled(context)
        toggle.summary = if (mode != "OFF") "已选择静默副屏模式，主屏助手已停用；关闭静默模式后可手动开启" else "${PacketSettings.status(context)}。仅群聊，不抢私聊；息屏可尝试短暂亮屏，需无需身份验证解锁。隐藏通知、免打扰无通知或界面不可识别时可能漏抢。检测到打字、滚动或切换应用时停止。仅本机处理，不依赖个人数据同步。"
    }
}
