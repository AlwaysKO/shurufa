package com.yuyan.redpacket

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Process
import android.provider.Settings
import android.view.ViewGroup
import android.widget.*
import java.text.DateFormat
import java.util.Date

class PacketActivity : Activity() {
    private lateinit var groups: EditText
    private lateinit var games: EditText
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("packet", 0) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32) }
        fun label(value: String) = TextView(this).also { it.text = value; content.addView(it) }
        label("静默群红包 · 微信 8.0.78\n当前 Android 用户：${Process.myUid() / 100000}")
        label("需要已验证的 Hook 框架。安装本模块不会自动获得 Hook 能力。荣耀系统分身共用主微信安装包，不能直接重签覆盖。")
        label("目标群标识（每行一个，以 @chatroom 结尾；仅这些群可自动领取）")
        groups = EditText(this).apply { minLines = 2; setText(ConfigStore(prefs).load().groups.joinToString("\n")); hint = "已核对的群内部标识@chatroom" }
        content.addView(groups)
        label("补充游戏包名（每行一个，系统未标记的游戏需补充）")
        games = EditText(this).apply { setText(prefs.getStringSet("games", emptySet())!!.joinToString("\n")) }
        content.addView(games)
        fun button(name: String, action: () -> Unit) { content.addView(Button(this).apply { text = name; setOnClickListener { action() } }) }
        button("关闭静默红包") { save(Mode.OFF, false) }
        button("开启只识别模式（不领取）") { save(Mode.PROBE, false) }
        button("设置输入 / 游戏保护") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("开启自动领取…") {
            AlertDialog.Builder(this).setTitle("仅对当前 Android 用户启用")
                .setMessage("请先核验框架只作用于本测试用户、版本及类定位通过、目标群标识正确；关闭旧的界面抢红包助手，开启输入/游戏保护。\n\n当前版本的真实请求和到账尚需受控测试。专属红包跳过，未知结果不重试；无法保证每次三秒内领取。确认这些条件已完成后，才开启自动领取。")
                .setNegativeButton("取消", null).setPositiveButton("确认已核验，开启") { _, _ -> save(Mode.AUTO, true) }.show()
        }
        status = label("")
        button("刷新状态") { refresh() }
        label("无聊天正文、截图或红包凭据上传。结果未知/失败不会自动重放。持久去重上限 2048 项，满后停止新任务。关闭后在途请求仍可能完成，不能撤回已发送请求。")
        setContentView(ScrollView(this).apply { addView(content, ViewGroup.LayoutParams(-1, -2)) })
        refresh()
    }
    private fun save(mode: Mode, validated: Boolean) {
        if (mode == Mode.OFF) {
            ConfigStore(prefs).save(ConfigStore(prefs).load().copy(mode = Mode.OFF), false)
            contentResolver.notifyChange(PacketProvider.URI, null)
            refresh()
            return
        }
        val selected = groups.text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        val extraGames = games.text.toString().lineSequence().map(String::trim).filter(String::isNotEmpty).toSet()
        if (selected.size > 64 || selected.any { !it.endsWith("@chatroom") || it.length !in 10..256 } ||
            extraGames.size > 64 || extraGames.any { !it.matches(Regex("[A-Za-z0-9_.]{3,256}")) } ||
            (mode == Mode.AUTO && (selected.isEmpty() || !ProtectionService.allowed()))) {
            Toast.makeText(this, "请核对群标识，并开启保护服务、停止输入且等待三秒", Toast.LENGTH_LONG).show(); return
        }
        prefs.edit().putStringSet("games", extraGames).commit()
        ConfigStore(prefs).save(PacketConfig(mode, Process.myUid() / 100000, selected), validated)
        contentResolver.notifyChange(PacketProvider.URI, null)
        refresh()
    }
    private fun refresh() {
        val last = prefs.getLong("last_signal", 0)
        val date = if (last == 0L) "尚未收到模块信号" else DateFormat.getDateTimeInstance().format(Date(last))
        val counts = listOf("candidate", "probe", "claimed", "rejected", "failed", "unknown", "protected").joinToString(" · ") { "$it=${prefs.getLong("count_$it", 0)}" }
        status.text = "模式：${ConfigStore(prefs).load().mode}\n最后信号：$date\n状态：${prefs.getString("last_status", "未载入")}\n$counts\n历史信号不代表此刻在线；领取结果仍需微信核验。"
    }
}
