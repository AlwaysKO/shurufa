package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.preference.PreferenceManager

/**
 * 服务端地址配置：设置页自定义（key=server_url）优先，否则按运行环境自动选择。
 * 模拟器（AVD）：10.0.2.2 直通宿主机 Windows → WSL2 localhost 转发 → 后端，零配置。
 * 真机：需在设置页填写 WSL 局域网 IP（hostname -I 查询）。
 */
object ServerConfig {

    private const val DEFAULT_PORT = 3000
    private const val KEY_SERVER_URL = "server_url"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
    }

    val baseUrl: String
        get() = prefs?.getString(KEY_SERVER_URL, null)?.takeIf { it.isNotBlank() } ?: autoBaseUrl()

    private fun autoBaseUrl(): String {
        val isEmulator = Build.FINGERPRINT.contains("generic")
            || Build.MODEL.contains("Emulator")
            || Build.MODEL.contains("Android SDK built for")
        val host = if (isEmulator) "10.0.2.2" else "192.168.1.100" // 真机默认占位，请在设置页配置
        return "http://$host:$DEFAULT_PORT"
    }
}
