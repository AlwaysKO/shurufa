package com.yuyan.imemodule.data.callrecording
import org.junit.Assert.*
import org.junit.Test
class CallPlaybackPolicyTest {
    @Test fun `迟到预览或离开页面及正在录音都不播放`() {
        assertTrue(callPreviewAllowed(1,1,true,false))
        assertFalse(callPreviewAllowed(1,2,true,false))
        assertFalse(callPreviewAllowed(1,1,false,false))
        assertFalse(callPreviewAllowed(1,1,true,true))
    }
}
