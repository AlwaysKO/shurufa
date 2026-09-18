package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class QqChatAdapterTest {
    private val pkg = "com.tencent.mobileqq"
    private fun fixture(): UiNodeSnapshot = javaClass.getResourceAsStream("/capture/qq-chat-9.3.60.json")!!
        .bufferedReader().use { Json.decodeFromString(it.readText()) }
    private fun transform(node: UiNodeSnapshot, action: (UiNodeSnapshot) -> UiNodeSnapshot): UiNodeSnapshot =
        action(node.copy(children = node.children.map { transform(it, action) }))
    private fun parse(root: UiNodeSnapshot): ParseResult = requireNotNull(AdapterRegistry.forPackage(pkg)).parse(root)

    @Test fun registeredAdapterReplaysDeviceTreeWithKeyboard() {
        val result = parse(fixture()) as ParseResult.Success
        assertEquals(ChatPlatform.QQ, result.viewport.conversation.platform)
        assertEquals("测试好友", result.viewport.conversation.displayName)
        val message = result.viewport.messages.single()
        assertEquals(IntRect(0, 276, 1200, 1487), message.mediaBounds)
        assertEquals("conversation_screenshot", message.metadata["capture_kind"])
        assertEquals("qq_screenshot", message.metadata["capture_source"])
        assertNull(message.text)
    }

    @Test fun rejectsMissingChatControlsAndAmbiguousTitles() {
        val root = fixture()
        for (id in listOf("input", "19c", "3cb", "dmj", "jo9")) {
            assertTrue(parse(transform(root) { if (it.viewId == "$pkg:id/$id") it.copy(viewId = null) else it }) is ParseResult.Skip)
        }
        val title = UiNodeSnapshot("$pkg:id/3cb", "android.widget.TextView", "另一会话", null,
            IntRect(100, 140, 350, 210), emptyList())
        assertTrue(parse(transform(root) { if (it.viewId == "$pkg:id/jo9") it.copy(children = it.children + title) else it }) is ParseResult.Skip)
    }

    @Test fun hiddenKeyboardUsesInputToolbarBoundaryAndRejectsOverlappingHeader() {
        val hidden = transform(fixture()) {
            when (it.viewId) {
                "$pkg:id/dmj" -> it.copy(bounds = IntRect(0, 2400, 1200, 2580))
                "$pkg:id/input" -> it.copy(bounds = IntRect(48, 2418, 900, 2562))
                else -> it
            }
        }
        assertEquals(2400, (parse(hidden) as ParseResult.Success).viewport.messages.single().mediaBounds!!.bottom)
        assertTrue(parse(transform(fixture()) {
            if (it.viewId == "$pkg:id/dmj") it.copy(bounds = IntRect(0, 200, 1200, 400)) else it
        }) is ParseResult.Skip)
    }

    @Test fun pendingCaptureIdentityAndForegroundBridgeSupportQq() {
        assertTrue(com.yuyan.imemodule.service.capture.isForegroundChatCapturePackage(pkg))
        val before = fixture()
        val typing = transform(before) { if (it.viewId == "$pkg:id/input") it.copy(text = "另一段草稿") else it }
        assertTrue(com.yuyan.imemodule.service.capture.samePendingChat(pkg, before, typing))
        val otherChat = transform(before) { if (it.viewId == "$pkg:id/3cb") it.copy(text = "另一会话") else it }
        assertFalse(com.yuyan.imemodule.service.capture.samePendingChat(pkg, before, otherChat))
        assertNotEquals(
            com.yuyan.imemodule.service.capture.viewportCaptureSignature(pkg, "same", 1),
            com.yuyan.imemodule.service.capture.viewportCaptureSignature(pkg, "same", 2),
        )
    }
}
