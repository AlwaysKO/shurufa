package com.yuyan.imemodule.data.capture.adapter

/** 只注册能够保守确认聊天标题和输入区的页面截图适配器。 */
object AdapterRegistry {
    private val adapters: List<ChatAppAdapter> = listOf(
        WeChatChatAdapter(),
        DouyinChatAdapter(),
    )

    fun forPackage(packageName: String): ChatAppAdapter? =
        adapters.firstOrNull { it.packageName == packageName }
}
