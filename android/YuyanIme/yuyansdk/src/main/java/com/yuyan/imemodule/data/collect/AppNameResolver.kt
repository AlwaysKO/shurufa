package com.yuyan.imemodule.data.collect

import android.content.Context
import android.util.LruCache

/** 仅查询发生输入的目标包，不枚举已安装应用；查询失败不影响输入事件采集。 */
internal class AppNameResolver {
    private val names = LruCache<String, String>(128)

    @Suppress("DEPRECATION")
    fun resolve(context: Context, packageName: String?): String? {
        if (packageName.isNullOrBlank()) return null
        return try {
            val locale = context.resources.configuration.locale.toLanguageTag()
            val key = "$locale:$packageName"
            names.get(key) ?: run {
                val pm = context.packageManager
                val info = pm.getApplicationInfo(packageName, 0)
                val name = pm.getApplicationLabel(info).toString()
                    .filterNot { it <= '\u001f' || it in '\u007f'..'\u009f' || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' }
                    .trim()
                    .takeIf { it.isNotEmpty() && it.length <= 120 && it != packageName }
                if (name != null) names.put(key, name)
                name
            }
        } catch (_: Exception) {
            // 未安装、包可见性限制或 OEM 包管理异常时，仍记录事件及原始包名。
            null
        }
    }
}
