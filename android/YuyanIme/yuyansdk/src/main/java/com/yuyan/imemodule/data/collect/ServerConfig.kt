package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.BuildConfig

/** 查询主地址使用线上；设置项仅决定电脑镜像上报地址，连接状态不改变本地学习。 */
object ServerConfig {

    private const val KEY_SERVER_URL = "server_url"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
    }

    /** 上报独立双传，查询使用线上，不受电脑连接影响。 */
    val eventTargets: List<String>
        get() = collectorTargets(prefs?.getString(KEY_SERVER_URL, null))

    /** 词库数据双传；未明确切换前，仍只接收既有线上主后台决策。 */
    val dictionaryAuthorityUrl: String get() = baseUrl

    val baseUrl: String
        get() = BuildConfig.COLLECTOR_API_BASE_URL.trim().trimEnd('/')

}

internal fun resolveServerBaseUrl(
    buildUrl: String,
    allowOverride: Boolean,
    configuredUrl: String?,
): String {
    val selected = if (allowOverride) configuredUrl?.takeIf { it.isNotBlank() } ?: buildUrl else buildUrl
    return selected.trim().trimEnd('/')
}

internal fun collectorTargets(configuredLocalUrl: String?): List<String> = listOf(
    configuredLocalUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "http://127.0.0.1:3000",
    "https://my.dog8ball.com",
).distinct()
