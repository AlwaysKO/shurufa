package com.yuyan.imemodule.data.capture.adapter

/** 页面方向不可可靠识别时保持空注册表；对方新消息由通知监听采集。 */
object AdapterRegistry {
    private val adapters: List<ChatAppAdapter> = emptyList()

    fun forPackage(packageName: String): ChatAppAdapter? =
        adapters.firstOrNull { it.packageName == packageName }
}
