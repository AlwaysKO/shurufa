package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WeChatChatAdapterTest {
    private val adapter = WeChatChatAdapter()

    @Test
    fun emitsOneScreenshotForChatWithoutAccessibleMessageText() {
        val result = adapter.parse(chatTree("阿明", emptyList())) as ParseResult.Success

        val screenshot = result.viewport.messages.single()
        assertEquals(ChatDirection.SYSTEM, screenshot.direction)
        assertEquals(ChatMessageType.IMAGE, screenshot.messageType)
        assertEquals(IntRect(0, 130, 1080, 1650), screenshot.mediaBounds)
        assertEquals("conversation_screenshot", screenshot.metadata["capture_kind"])
    }

    @Test
    fun recognizesDirectChatWithoutReadingMessageText() {
        val result = adapter.parse(chatTree("阿明", listOf(
            bubble("在吗", 60, 420, 360, 500),
            bubble("在的", 720, 540, 1010, 620),
        ))) as ParseResult.Success

        assertEquals("阿明", result.viewport.conversation.displayName)
        assertEquals(ConversationType.DIRECT, result.viewport.conversation.conversationType)
        assertEquals(1, result.viewport.messages.size)
        assertEquals(null, result.viewport.messages.single().text)
    }

    @Test
    fun recognizesGroupTitleAndCapturesOneViewport() {
        val sender = node("com.tencent.mm:id/chatting_user_name", "小周", 60, 390, 220, 425)
        val result = adapter.parse(chatTree("项目群(8)", listOf(
            group(sender, bubble("今晚开会吗", 60, 430, 430, 510)),
            bubble("八点开", 730, 550, 1010, 630),
        ))) as ParseResult.Success

        assertEquals(ConversationType.GROUP, result.viewport.conversation.conversationType)
        assertEquals("项目群", result.viewport.conversation.displayName)
        assertEquals(ChatDirection.SYSTEM, result.viewport.messages.single().direction)
    }

    @Test
    fun skipsPagesWithoutAnUnambiguousTitleAndInput() {
        val root = group(node(null, "微信", 20, 40, 200, 120))
        assertTrue(adapter.parse(root) is ParseResult.Skip)
    }

    @Test fun voiceModeIsAChatWithoutAnEditText() {
        val tree = chatTree("阿明", emptyList())
        val voice = tree.copy(children = tree.children.map {
            if (it.className == "android.widget.EditText") it.copy(viewId = null, className = "android.widget.Button", text = "按住 说话") else it
        })
        val result = adapter.parse(voice) as ParseResult.Success
        assertEquals("阿明", result.viewport.conversation.displayName)
        assertEquals(IntRect(0, 130, 1080, 1650), result.viewport.messages.single().mediaBounds)
    }

    private fun chatTree(title: String, messages: List<UiNodeSnapshot>) = UiNodeSnapshot(
        null, "root", null, null, IntRect(0, 0, 1080, 1920), listOf(
            node("com.tencent.mm:id/chatting_title", title, 180, 50, 850, 130),
            group(*messages.toTypedArray()),
            node("com.tencent.mm:id/chatting_content_et", null, 80, 1650, 850, 1760, "android.widget.EditText"),
            node(null, "发送", 880, 1650, 1060, 1760),
        ),
    )

    private fun bubble(text: String, l: Int, t: Int, r: Int, b: Int) =
        node("com.tencent.mm:id/chatting_content_itv", text, l, t, r, b)
    private fun group(vararg children: UiNodeSnapshot) =
        UiNodeSnapshot(null, "android.view.ViewGroup", null, null, IntRect(0, 0, 1080, 1920), children.toList())
    private fun node(id: String?, text: String?, l: Int, t: Int, r: Int, b: Int, clazz: String = "android.widget.TextView") =
        UiNodeSnapshot(id, clazz, text, null, IntRect(l, t, r, b), emptyList())
}
