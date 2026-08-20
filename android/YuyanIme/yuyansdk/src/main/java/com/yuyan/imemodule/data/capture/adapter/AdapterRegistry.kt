package com.yuyan.imemodule.data.capture.adapter

/**
 * 应用专属规则必须由真实节点夹具驱动。在夹具采集完成前保持空注册表，所有页面保守跳过。
 */
object AdapterRegistry {
    private val adapters: List<ChatAppAdapter> = emptyList()

    fun forPackage(packageName: String): ChatAppAdapter? =
        adapters.firstOrNull { it.packageName == packageName }
}
