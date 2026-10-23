package com.yuyan.imemodule.ui.setup

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.yuyan.imemodule.data.redpacket.PacketServiceState

/** 仅在前台引导系统设置；打开页面或点击返回均不视为系统已授权。 */
class BackgroundSetupActivity : AppCompatActivity() {
    private lateinit var batteryStatus: TextView
    private lateinit var accessibilityStatus: TextView
    private lateinit var startupStatus: TextView
    private lateinit var batteryButton: Button
    private lateinit var accessibilityButton: Button
    private lateinit var startupButton: Button
    private lateinit var reviewedButton: Button
    private lateinit var doneButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val padding = (20 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        fun text(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setPadding(0, padding / 2, 0, padding / 2); box.addView(this)
        }
        fun button(label: String, action: () -> Unit) = Button(this).apply {
            text = label; setOnClickListener { action() }
            box.addView(this, ViewGroup.LayoutParams(-1, -2))
        }
        text("后台运行设置", 24f)
        text("完成后，覆盖安装会保留这些选择；检测到设置齐全时不再弹出。可稍后到「其他 → 后台运行设置」继续。本页不会开启采集或同步功能。")
        batteryStatus = text("")
        batteryButton = button("允许后台运行") {
            open(listOf(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")),
            ))
        }
        text("电池优化豁免可减少后台中断，也可能增加耗电。")
        accessibilityStatus = text("")
        accessibilityButton = button("打开无障碍设置") {
            open(listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)))
        }
        text("进入后选择「妙言输入法」。仅在需要已选择的辅助功能时开启；普通打字不要求此权限。系统会显示具体能力，由你确认。")
        startupStatus = text("")
        val honor = Build.MANUFACTURER.equals("HONOR", true)
        text(if (honor) "进入本应用的「耗电详情 → 启动管理」，关闭自动管理，开启自启动、关联启动和后台活动。最近任务中也可将本应用卡片加锁。"
            else "在本应用的启动管理或电池设置中，允许自启动与后台运行。具体名称由手机系统决定；没有这些选项时可以直接确认已检查。")
        startupButton = button("打开启动与后台设置") {
            open(BackgroundSetup.startupIntents(Build.MANUFACTURER, packageName))
        }
        reviewedButton = button("我已检查启动管理") {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("确认启动管理设置")
                .setMessage("系统没有提供读取这些开关的通用接口。请确认已允许本应用自启动和后台运行，或这台手机没有相关限制。以后更新会保留本次确认。")
                .setNegativeButton("继续检查", null)
                .setPositiveButton("已确认") { _, _ -> BackgroundSetup.state(this).markStartupReviewed(); refresh() }.show()
        }
        doneButton = button("稍后设置") { finish() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun open(intents: List<Intent>) {
        if (!BackgroundSetup.openFirst(intents, ::startActivity))
            Toast.makeText(this, "系统暂不支持直接打开，请在系统设置中搜索本应用", Toast.LENGTH_LONG).show()
    }

    private fun refresh() {
        val exempt = BackgroundSetup.batteryExempt(this)
        val enabled = BackgroundSetup.accessibilityEnabled(this)
        val connected = PacketServiceState.nativeAccessibilityService != null
        val reviewed = BackgroundSetup.state(this).startupReviewed()
        batteryStatus.text = "电池优化：${if (exempt) "已允许后台运行" else "尚未豁免"}"
        accessibilityStatus.text = "无障碍：${if (!enabled) "未开启" else if (connected) "已开启，服务已连接" else "已开启，等待系统连接"}"
        startupStatus.text = "启动管理：${if (reviewed) "已手动确认（系统无法自动核验）" else "待检查"}"
        batteryButton.isEnabled = !exempt
        accessibilityButton.text = if (enabled) "查看无障碍设置" else "打开无障碍设置"
        startupButton.text = if (reviewed) "重新检查启动管理" else "打开启动与后台设置"
        reviewedButton.isEnabled = !reviewed
        doneButton.text = if (exempt && enabled && reviewed) "完成" else "稍后设置"
    }
}
