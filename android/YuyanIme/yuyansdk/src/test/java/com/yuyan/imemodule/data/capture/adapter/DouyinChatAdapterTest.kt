package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.decodeFromString

class DouyinChatAdapterTest {
    private fun node(id: String, text: String? = null, bounds: IntRect = IntRect(0, 0, 1200, 2664), children: List<UiNodeSnapshot> = emptyList(), clazz: String = "android.widget.TextView") =
        UiNodeSnapshot("com.ss.android.ugc.aweme:id/$id", clazz, text, null, bounds, children)

    private fun chat(title: String = "测试好友", inputTop: Int = 1620) = node("d_-", children = listOf(
        node("vw3", title, IntRect(295, 139, 471, 197)),
        node("lqf", "在线", IntRect(295, 197, 435, 243)),
        node("jta", bounds = IntRect(0, 258, 1200, 2664), children = listOf(
            node("message", "不得复制正文", IntRect(100, 400, 800, 500)),
            node("msg_et", "谢谢", IntRect(155, inputTop, 860, inputTop + 131), clazz = "android.widget.EditText"),
        )),
    ))

    @Test fun replaysSanitizedDeviceChatAndInboxTrees() {
        fun fixture(name: String) = javaClass.getResourceAsStream("/capture/$name")!!.bufferedReader().use {
            kotlinx.serialization.json.Json.decodeFromString<UiNodeSnapshot>(it.readText())
        }
        val result = DouyinChatAdapter().parse(fixture("douyin-chat-40.5.0.json")) as ParseResult.Success
        assertEquals("测试文本", result.viewport.conversation.displayName)
        assertEquals(IntRect(0, 258, 1200, 1620), result.viewport.messages.single().mediaBounds)
        assertTrue(DouyinChatAdapter().parse(fixture("douyin-inbox-40.5.0.json")) is ParseResult.Skip)
    }

    @Test fun capturesForegroundChatNotBackgroundInboxTitle() {
        val root = node("root", children = listOf(node("tv_title", "消息列表"), chat()))
        val result = requireNotNull(AdapterRegistry.forPackage("com.ss.android.ugc.aweme")).parse(root) as ParseResult.Success
        assertEquals(ChatPlatform.DOUYIN, result.viewport.conversation.platform)
        assertEquals("测试好友", result.viewport.conversation.displayName)
        val screenshot = result.viewport.messages.single()
        assertNull(screenshot.text)
        assertEquals(IntRect(0, 258, 1200, 1620), screenshot.mediaBounds)
        assertEquals("conversation_screenshot", screenshot.metadata["capture_kind"])
        assertEquals("douyin_screenshot", screenshot.metadata["capture_source"])
    }

    @Test fun registeredAdapterPersistsScreenshotAndDeduplicatesThroughSharedCoordinator() = kotlinx.coroutines.runBlocking {
        val queued = mutableListOf<com.yuyan.imemodule.data.capture.db.PendingMessageEntity>()
        val hashes = mutableSetOf<String>()
        var wakes = 0
        val store = object : com.yuyan.imemodule.data.capture.CaptureOutboxStore {
            override suspend fun enqueueIfNew(
                seenMessage: com.yuyan.imemodule.data.capture.db.SeenMessageEntity,
                pendingMessage: com.yuyan.imemodule.data.capture.db.PendingMessageEntity,
                pendingAssets: List<com.yuyan.imemodule.data.capture.db.PendingAssetEntity>,
            ): Boolean {
                if (!hashes.add(seenMessage.fingerprint)) return false
                assertEquals(1, pendingAssets.size)
                queued += pendingMessage
                return true
            }
        }
        val coordinator = com.yuyan.imemodule.data.capture.CaptureCoordinator(
            store = store,
            deviceId = { "00000000-0000-4000-8000-000000000001" },
            wakeUploader = { wakes++ },
            mediaCapturer = com.yuyan.imemodule.data.capture.media.MediaAssetCapturer { _, _, requests ->
                assertEquals(IntRect(0, 258, 1200, 1620), requests.single().bounds)
                mapOf(0 to com.yuyan.imemodule.data.capture.db.PendingAssetEntity(
                    sha256 = "a".repeat(64), localPath = "/test/douyin.webp", mimeType = "image/webp",
                    perceptualHash = null, width = 1200, height = 1362,
                ))
            },
        )
        repeat(2) { coordinator.capture("com.ss.android.ugc.aweme", chat(), 1) }
        assertEquals(1, queued.size)
        assertEquals(1, wakes)
        assertTrue(queued.single().payloadJson.contains("douyin"))
        assertFalse(queued.single().payloadJson.contains("不得复制正文"))
    }

    @Test fun keyboardHiddenUsesActualInputTopWithoutCuttingLastMessage() {
        val result = DouyinChatAdapter().parse(chat(inputTop = 2450)) as ParseResult.Success
        assertEquals(2450, result.viewport.messages.single().mediaBounds!!.bottom)
    }

    @Test fun rejectsInboxCommentsMissingTitleAndAmbiguousChatContainers() {
        val adapter = DouyinChatAdapter()
        assertTrue(adapter.parse(node("root", children = listOf(node("tv_title", "消息列表")))) is ParseResult.Skip)
        assertTrue(adapter.parse(node("root", children = listOf(node("msg_et", "评论")))) is ParseResult.Skip)
        assertTrue(adapter.parse(chat(title = "")) is ParseResult.Skip)
        assertTrue(adapter.parse(node("root", children = listOf(chat(), chat("另一人")))) is ParseResult.Skip)
        assertNull(AdapterRegistry.forPackage("com.tencent.mobileqq"))
    }
}
