package com.yuyan.imemodule.data.capture.adapter

import org.junit.Assert.assertNull
import org.junit.Test

class AdapterRegistryTest {
    @Test
    fun wechatPageCaptureIsDisabledWhenDirectionCannotBeObservedReliably() {
        assertNull(AdapterRegistry.forPackage("com.tencent.mm"))
    }
}
