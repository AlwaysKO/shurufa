package com.yuyan.imemodule.data.capture.adapter

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterRegistryTest {
    @Test
    fun wechatUsesScreenshotAdapterWithoutGuessingIndividualMessageDirection() {
        assertTrue(AdapterRegistry.forPackage("com.tencent.mm") is WeChatChatAdapter)
        assertTrue(AdapterRegistry.forPackage("com.tencent.mobileqq") is QqChatAdapter)
        assertTrue(AdapterRegistry.forPackage("com.ss.android.ugc.aweme") is DouyinChatAdapter)
        assertNull(AdapterRegistry.forPackage("com.example.unrelated"))
    }
}
