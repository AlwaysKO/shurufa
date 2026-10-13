package com.yuyan.imemodule.data.redpacket

import android.app.Activity
import android.app.AlertDialog
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.CheckBox
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
    private lateinit var autoUserInput: CheckBox
    private lateinit var autoGroupsInput: CheckBox
    private lateinit var setupSnapshot: SilentPacketSetupSnapshot
    private var showTechnical = false
    private var saveFeedback: String? = null
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
        setupSnapshot = SilentPacketSetup.collect(this)
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
        text("静默处理需要 Android 15 或以上、Shizuku 与系统授权；不是只安装输入法就能运行。Shizuku 首次需无线调试配对，手机重启后需重新启动。画面只在本机内存处理。")
        text("收到微信红包通知后，自动区分主微信和分身，并在副屏核验群聊。微信没有发出通知（例如群免打扰）时当前方案不会触发；这里不能替微信恢复通知。可在其他应用或游戏中通过副屏尝试处理；主屏触摸、滑动和输入不取消任务。锁屏、前台未确认或正在使用接收方微信时停止。已点击或结果未知不重复尝试。")
        statusView = text(currentStatus())
        val supported = Build.VERSION.SDK_INT >= 35
        if (!supported) text("当前系统版本不支持静默副屏；可保留关闭模式。")
        button("检查准备情况 / 下一步") {
            refreshPreparation()
            if (setupSnapshot.nextStep == SilentPacketSetupStep.AUTHORIZE_SHIZUKU) {
                SilentPacketRuntime.connect(this, requestPermission = true)
            } else {
                SilentPacketSetup.open(this, setupSnapshot.nextStep)?.let { message ->
                    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                }
            }
        }.isEnabled = supported
        button("下载 Shizuku（官方）") {
            SilentPacketSetup.open(this, SilentPacketSetupStep.INSTALL_SHIZUKU)?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }
        button("无线调试设置") {
            SilentPacketSetup.openDeveloperSettings(this)?.let { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        }.isEnabled = supported
        button("查看首次配对说明") {
            SilentPacketSetup.openGuide(this)?.let { message -> Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
        }
        button("连接 Shizuku / 请求授权") {
            SilentPacketRuntime.connect(this, requestPermission = true)
        }.isEnabled = supported
        button("刷新状态与微信账号") {
            SilentPacketRuntime.refreshStatus()
            loadUsers()
        }.isEnabled = supported
        userButton = button("选择微信账号（用户 ID：$selectedUser）") { loadUsers(choose = true) }
        autoUserInput = CheckBox(this).apply {
            tag = "auto_user"
            text = "按通知自动区分主微信和分身"
            isChecked = savedInstanceState?.getBoolean("auto_user") ?: SilentPacketSettings.autoUser(this@SilentPacketSettingsActivity)
            isEnabled = supported
            root.addView(this)
        }
        button("微信副屏只读自检（不抢红包）") {
            if (selectedUser < 0) {
                Toast.makeText(this, "只读自检需临时选择一个账号；自动领取无需手选账号", Toast.LENGTH_LONG).show()
                loadUsers(choose = true)
            } else SilentPacketRuntime.diagnose(this, selectedUser)
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
        autoGroupsInput = CheckBox(this).apply {
            tag = "auto_groups"
            text = "自动核验收到通知的群聊，无需填写群名"
            isChecked = savedInstanceState?.getBoolean("auto_groups") ?: SilentPacketSettings.autoGroups(this@SilentPacketSettingsActivity)
            isEnabled = supported
            root.addView(this)
        }
        text("关闭自动群聊后，仅处理下面填写的完整群名，每行一个；无法确认群聊时不领取。")
        groupInput = EditText(this).apply {
            minLines = 3
            gravity = android.view.Gravity.TOP
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(SilentPacketSettings.groups(this@SilentPacketSettingsActivity).joinToString("\n"))
            root.addView(this)
        }
        autoUserInput.setOnCheckedChangeListener { _, _ -> updateScopeControls() }
        autoGroupsInput.setOnCheckedChangeListener { _, _ -> updateScopeControls() }
        updateScopeControls()
        button("保存设置") { save() }
        val technicalButton = button("查看技术诊断") { }
        val noticeCheckButton = if (com.yuyan.imemodule.BuildConfig.DEBUG) {
            button("下一条普通群通知只读自检（不抢红包）") {
                if (selectedUser < 0) Toast.makeText(this, "请先选择本次只读检查的接收账号", Toast.LENGTH_LONG).show()
                else SilentPacketRuntime.diagnoseNextNotice(this, selectedUser)
            }.apply { visibility = View.GONE; isEnabled = supported }
        } else null
        technicalButton.setOnClickListener {
            showTechnical = !showTechnical
            noticeCheckButton?.visibility = if (showTechnical) View.VISIBLE else View.GONE
            technicalButton.text = if (showTechnical) "收起技术诊断" else "查看技术诊断"
            statusView.text = currentStatus()
        }
        button("停止当前会话") { SilentPacketRuntime.stop("用户停止") }.isEnabled = supported
        button("断开静默服务") { SilentPacketRuntime.disconnect() }.isEnabled = supported
        button("返回") { finish() }
    }

    private fun currentStatus(): String = listOfNotNull(saveFeedback, setupSnapshot.render(),
        if (Build.VERSION.SDK_INT >= 35) SilentPacketRuntime.summary(this, setupSnapshot) else null,
        if (Build.VERSION.SDK_INT >= 35 && showTechnical) "技术诊断：\n${SilentPacketRuntime.status()}" else null)
        .joinToString("\n")

    private fun refreshPreparation() {
        setupSnapshot = SilentPacketSetup.collect(this)
        statusView.text = currentStatus()
    }

    private fun updateScopeControls() {
        userButton.isEnabled = Build.VERSION.SDK_INT >= 35 && !autoUserInput.isChecked
        groupInput.visibility = if (autoGroupsInput.isChecked) View.GONE else View.VISIBLE
        groupInput.isEnabled = Build.VERSION.SDK_INT >= 35 && !autoGroupsInput.isChecked
    }

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
                userButton.isEnabled = !autoUserInput.isChecked
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
        setupSnapshot = SilentPacketSetup.collect(this)
        fun rejected(reason: String) {
            saveFeedback = silentPacketSaveRejected(SilentPacketSettings.mode(this), reason)
            statusView.text = currentStatus()
            Toast.makeText(this, saveFeedback, Toast.LENGTH_LONG).show()
        }
        if (mode != "OFF" && setupSnapshot.nextStep != SilentPacketSetupStep.READY) {
            rejected(setupSnapshot.render())
            return
        }
        if (mode != "OFF" && (Build.VERSION.SDK_INT < 35 || !autoUserInput.isChecked && availableUsers.none { it.first == selectedUser })) {
            rejected("请连接 Shizuku，并选择已验证安装微信的账号")
            return
        }
        if (mode != "OFF" && !autoGroupsInput.isChecked && groupInput.text.toString().lineSequence().all { it.isBlank() }) {
            rejected("请选择自动处理全部群聊，或填写群名白名单")
            return
        }
        if (Build.VERSION.SDK_INT >= 35) SilentPacketRuntime.stop("配置已变更")
        SilentPacketSettings.setAutoUser(this, autoUserInput.isChecked)
        SilentPacketSettings.setAutoGroups(this, autoGroupsInput.isChecked)
        SilentPacketSettings.setUserId(this, selectedUser)
        SilentPacketSettings.setGroups(this, groupInput.text.toString())
        SilentPacketSettings.setMode(this, mode)
        saveFeedback = "已保存配置；是否正在运行请看下方实际状态"
        statusView.text = currentStatus()
        Toast.makeText(this, saveFeedback, Toast.LENGTH_SHORT).show()
    }

    override fun onResume() { super.onResume(); refreshPreparation(); handler.post(statusTick) }
    override fun onPause() { handler.removeCallbacks(statusTick); super.onPause() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("user", selectedUser)
        outState.putBoolean("auto_user", autoUserInput.isChecked)
        outState.putBoolean("auto_groups", autoGroupsInput.isChecked)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }
}
