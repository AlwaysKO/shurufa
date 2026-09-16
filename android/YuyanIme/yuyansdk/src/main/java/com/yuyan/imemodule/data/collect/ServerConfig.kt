package com.yuyan.imemodule.data.collect

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.BuildConfig
import java.net.URI

/** 查询主地址使用线上；设置项仅决定电脑镜像上报地址，连接状态不改变本地学习。 */
object ServerConfig {

    private const val KEY_SERVER_URL = "server_url"
    private const val KEY_ONLINE_SERVER_URL = "online_server_url"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs == null) {
            prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        }
    }

    /** 上报独立双传，查询使用线上，不受电脑连接影响。 */
    val eventTargets: List<String>
        get() = collectorTargets(prefs?.getString(KEY_SERVER_URL, null), baseUrl)

    /** 词库数据双传；未明确切换前，仍只接收既有线上主后台决策。 */
    val dictionaryAuthorityUrl: String get() = baseUrl

    val baseUrl: String
        get() = normalizeOnlineServerUrl(prefs?.getString(KEY_ONLINE_SERVER_URL, null))
            ?: BuildConfig.COLLECTOR_API_BASE_URL.trim().trimEnd('/')

    @Synchronized fun updateOnlineServerUrl(candidate: String): Pair<String, String>? {
        val normalized = normalizeOnlineServerUrl(candidate) ?: return null
        val old = baseUrl
        if (old == normalized) return null
        val saved = checkNotNull(prefs) { "ServerConfig is not initialized" }
            .edit().putString(KEY_ONLINE_SERVER_URL, normalized).commit()
        if (!saved) return null
        return old to normalized
    }

}

internal fun resolveServerBaseUrl(
    buildUrl: String,
    allowOverride: Boolean,
    configuredUrl: String?,
): String {
    val selected = if (allowOverride) configuredUrl?.takeIf { it.isNotBlank() } ?: buildUrl else buildUrl
    return selected.trim().trimEnd('/')
}

internal fun normalizeOnlineServerUrl(value: String?): String? = runCatching {
    val uri = URI(value?.trim()?.trimEnd('/') ?: return null)
    if (uri.scheme != "https" || uri.host.isNullOrBlank() || uri.userInfo != null ||
        uri.query != null || uri.fragment != null || (uri.path.isNotEmpty() && uri.path != "/")) return null
    URI("https", null, uri.host, uri.port, null, null, null).toString().trimEnd('/')
}.getOrNull()

internal fun collectorTargets(
    configuredLocalUrl: String?,
    onlineUrl: String = "https://my.dog8ball.com",
): List<String> = listOf(
    configuredLocalUrl?.trim()?.trimEnd('/')?.takeIf { it.isNotEmpty() } ?: "http://127.0.0.1:3000",
    onlineUrl.trim().trimEnd('/'),
).distinct()
