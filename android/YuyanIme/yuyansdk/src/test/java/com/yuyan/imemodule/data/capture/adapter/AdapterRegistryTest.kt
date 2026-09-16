package com.yuyan.imemodule.data.capture.adapter

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdapterRegistryTest {
    @Test
    fun wechatUsesScreenshotAdapterWithoutGuessingIndividualMessageDirection() {
        assertTrue(AdapterRegistry.forPackage("com.tencent.mm") is WeChatChatAdapter)
        assertNull(AdapterRegistry.forPackage("com.tencent.mobileqq"))
        assertNull(AdapterRegistry.forPackage("com.ss.android.ugc.aweme"))
    }
}
