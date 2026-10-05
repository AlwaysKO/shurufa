package com.yuyan.imemodule.data.redpacket

import android.app.Activity
import android.app.KeyguardManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.TextView

/** 仅当前任务能够唤醒；不导出、不保存密码、不尝试绕过身份验证。 */
class PacketWakeActivity : Activity() {
    private var token: String? = null
    private var requested = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        GroupRedPacketAssistant.wakeFailed(token)
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        token = intent.getStringExtra("packet_token")
        if (!GroupRedPacketAssistant.hasWakeRequest(token)) { finish(); return }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isDeviceLocked) { GroupRedPacketAssistant.wakeFailed(token); finish(); return }
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(TextView(this).apply { text = "正在处理微信群红包…\n轻触取消"; gravity = Gravity.CENTER })
        handler.postDelayed(timeout, 4_000)
    }

    override fun onResume() {
        super.onResume()
        if (requested || !GroupRedPacketAssistant.hasWakeRequest(token)) { if (!requested) finish(); return }
        requested = true
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (!keyguard.isKeyguardLocked) ready()
        else keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = ready()
            override fun onDismissCancelled() { GroupRedPacketAssistant.wakeFailed(token); finish() }
            override fun onDismissError() { GroupRedPacketAssistant.wakeFailed(token); finish() }
        })
    }

    private fun ready() {
        handler.removeCallbacks(timeout)
        GroupRedPacketAssistant.wakeReady(token)
        finish()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            GroupRedPacketAssistant.cancel("用户取消", false)
            finish()
        }
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacks(timeout)
        super.onDestroy()
    }
}
