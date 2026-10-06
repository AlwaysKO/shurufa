package com.yuyan.imemodule.data.capture.adapter

/** 只注册能够保守确认聊天标题和输入区的页面截图适配器。 */
object AdapterRegistry {
    private val adapters: List<ChatAppAdapter> = listOf(
        WeChatChatAdapter { ChatCaptureSettings.rule("com.tencent.mm") },
        DouyinChatAdapter(ruleProvider = { ChatCaptureSettings.rule("com.ss.android.ugc.aweme") }),
        QqChatAdapter(),
    )

    fun forPackage(packageName: String): ChatAppAdapter? =
        adapters.firstOrNull { it.packageName == packageName }
}
