package com.yuyan.imemodule.data.redpacket

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.concurrent.Executors

/** 所有授权、账号和模式选择均由用户明确操作；页面本身不启动红包会话。 */
internal class SilentPacketSettingsActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var statusView: TextView
    private lateinit var userButton: Button
    private lateinit var groupInput: EditText
    private lateinit var modeInput: RadioGroup
    private var selectedUser = -1
    private var availableUsers = emptyList<Pair<Int, String>>()
    private var closed = false
    private val statusTick = object : Runnable {
        override fun run() {
            if (!closed) {
                statusView.text = currentStatus()
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "静默抢红包设置"
        selectedUser = savedInstanceState?.getInt("user", -1) ?: SilentPacketSettings.userId(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = (16 * resources.displayMetrics.density).toInt()
            setPadding(padding, padding, padding, padding)
        }
        val scroll = ScrollView(this).apply { addView(root) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
        setContentView(scroll)
        fun text(value: String) = TextView(this).apply { text = value; root.addView(this) }
        fun button(value: String, action: () -> Unit) = Button(this).apply {
            text = value
            setOnClickListener { action() }
            root.addView(this)
        }
        text("通过 Shizuku 在副屏操作微信，主屏保持当前应用。需要 Android 15 或以上及系统支持；首次请安装并启动 Shizuku，开启开发者选项和无线调试，完成配对与授权。手机重启后需重新启动 Shizuku。还需在“红包助手权限设置”开启通知使用权与无障碍服务，用于通知触发及输入、游戏避让。")
        text("仅处理已选账号和群名白名单。PROBE 只识别，不点击；AUTO 才会自动领取。界面不支持、主屏使用目标微信账号、游戏或输入时停止，不转为主屏抢红包。画面只在本机内存处理，不保存或上传。")
        statusView = text(currentStatus())
        val supported = Build.VERSION.SDK_INT >= 35
        if (!supported) text("当前系统版本不支持静默副屏；可保留关闭模式。")
        button("连接 Shizuku / 请求授权") {
            SilentPacketRuntime.connect(this, requestPermission = true)
        }.isEnabled = supported
        button("刷新状态与微信账号") {
            SilentPacketRuntime.refreshStatus()
            loadUsers()
        }.isEnabled = supported
        userButton = button("选择微信账号（用户 ID：$selectedUser）") { loadUsers(choose = true) }
        userButton.isEnabled = supported
        button("微信副屏只读自检（不抢红包）") {
            SilentPacketRuntime.diagnose(this, selectedUser)
        }.isEnabled = supported
        modeInput = RadioGroup(this).apply {
            listOf("OFF" to "关闭", "PROBE" to "只识别（PROBE）", "AUTO" to "自动领取（AUTO）").forEach { (mode, label) ->
                addView(RadioButton(this@SilentPacketSettingsActivity).apply {
                    id = View.generateViewId()
                    tag = mode
                    text = label
                    isEnabled = supported || mode == "OFF"
                    isChecked = mode == SilentPacketSettings.mode(this@SilentPacketSettingsActivity)
                })
            }
            root.addView(this)
        }
        text("群名白名单：每行一个完整群名，精确匹配；截断或无法确认群聊时不领取。")
        groupInput = EditText(this).apply {
            minLines = 3
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(SilentPacketSettings.groups(this@SilentPacketSettingsActivity).joinToString("\n"))
            root.addView(this)
        }
        button("保存设置") { save() }
        button("停止当前会话") { SilentPacketRuntime.stop("用户停止") }.isEnabled = supported
        button("断开静默服务") { SilentPacketRuntime.disconnect() }.isEnabled = supported
        button("返回") { finish() }
    }

    private fun currentStatus(): String = if (Build.VERSION.SDK_INT >= 35) SilentPacketRuntime.status()
        else "当前系统不支持静默副屏"

    private fun loadUsers(choose: Boolean = false) {
        userButton.isEnabled = false
        worker.execute {
            val result = runCatching {
                val response = JSONObject(SilentPacketRuntime.users())
                val users = response.optJSONArray("users") ?: error("未取得账号列表")
                (0 until users.length()).mapNotNull { index ->
                    val user = users.getJSONObject(index)
                    val id = user.optInt("id", -1)
                    if (id < 0) null else id to user.optString("name", "用户 $id")
                }.distinctBy { it.first }
            }
            handler.post {
                if (closed || isFinishing || isDestroyed) return@post
                userButton.isEnabled = true
                availableUsers = result.getOrDefault(emptyList())
                if (availableUsers.none { it.first == selectedUser }) selectedUser = -1
                updateUserLabel()
                if (availableUsers.isEmpty()) {
                    Toast.makeText(this, "未取得已安装微信的账号，请先连接并授权 Shizuku 后重试", Toast.LENGTH_LONG).show()
                } else if (choose) {
                    AlertDialog.Builder(this).setTitle("选择微信账号")
                        .setItems(availableUsers.map { "${it.second}（用户 ${it.first}）" }.toTypedArray()) { _, index ->
                            selectedUser = availableUsers[index].first
                            updateUserLabel()
                        }.show()
                }
            }
        }
    }

    private fun updateUserLabel() {
        val name = availableUsers.firstOrNull { it.first == selectedUser }?.second
        userButton.text = if (name == null) "选择微信账号（尚未选择）" else "$name（用户 $selectedUser）"
    }

    private fun save() {
        val mode = modeInput.findViewById<RadioButton>(modeInput.checkedRadioButtonId)?.tag as? String ?: "OFF"
        if (mode != "OFF" && (Build.VERSION.SDK_INT < 35 || availableUsers.none { it.first == selectedUser })) {
            Toast.makeText(this, "请连接 Shizuku，并选择已验证安装微信的账号", Toast.LENGTH_LONG).show()
            return
        }
        if (mode != "OFF" && groupInput.text.toString().lineSequence().all { it.isBlank() }) {
            Toast.makeText(this, "静默识别与领取必须先填写群名白名单", Toast.LENGTH_LONG).show()
            return
        }
        if (Build.VERSION.SDK_INT >= 35) SilentPacketRuntime.stop("配置已变更")
        SilentPacketSettings.setUserId(this, selectedUser)
        SilentPacketSettings.setGroups(this, groupInput.text.toString())
        SilentPacketSettings.setMode(this, mode)
        Toast.makeText(this, "已保存：$mode", Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); handler.post(statusTick) }
    override fun onPause() { handler.removeCallbacks(statusTick); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("user", selectedUser); super.onSaveInstanceState(outState) }
    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }
}
