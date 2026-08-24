package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.BuildConfig

/**
 * Debug 包默认通过 adb reverse 访问本机，也允许设置页覆盖。
 * Release 包固定使用线上 API，并忽略设置页中的本地地址，避免环境混用。
 */
object ServerConfig {

    private const val KEY_SERVER_URL = "server_url"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
    }

    val baseUrl: String
        get() = resolveServerBaseUrl(
            buildUrl = BuildConfig.COLLECTOR_API_BASE_URL,
            allowOverride = BuildConfig.ALLOW_SERVER_URL_OVERRIDE,
            configuredUrl = prefs?.getString(KEY_SERVER_URL, null),
        )
}

internal fun resolveServerBaseUrl(
    buildUrl: String,
    allowOverride: Boolean,
    configuredUrl: String?,
): String {
    val selected = if (allowOverride) configuredUrl?.takeIf { it.isNotBlank() } ?: buildUrl else buildUrl
    return selected.trim().trimEnd('/')
}
