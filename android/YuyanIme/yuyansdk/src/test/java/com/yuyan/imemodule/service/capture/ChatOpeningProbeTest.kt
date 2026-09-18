package com.yuyan.imemodule.service.capture
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import com.yuyan.imemodule.data.capture.ui.IntRect
import org.junit.Assert.*
import org.junit.Test
class ChatOpeningProbeTest {
    @Test fun containerWithoutReadableContentMustNotSuppressFallback() {
        val root=UiNodeSnapshot(null,"android.widget.FrameLayout",null,null,IntRect(0,0,1000,2000),emptyList())
        assertFalse(hasReadableChatContent(root))
        assertTrue(hasReadableChatContent(root.copy(children=listOf(root.copy(text="联系人")))))
    }
}
