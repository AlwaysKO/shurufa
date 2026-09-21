package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.decodeFromString

class DouyinChatAdapterTest {
    // 合成跨版本树：不冒充其他抖音版本的真机取证。
    private fun structuralChat(title: String = "兼容测试好友", inputTop: Int = 1620,
                               settings: String = "聊天设置", bodyClass: String = "androidx.recyclerview.widget.RecyclerView") =
        node("changed_layer", children = listOf(
            node("changed_toolbar", bounds = IntRect(0, 126, 1200, 258), children = listOf(
                node("changed_back", bounds = IntRect(0, 126, 130, 258), clazz = "android.widget.Button").copy(contentDescription = "返回"),
                node("changed_title", title, IntRect(295, 139, 600, 197)),
                node("changed_online", "在线", IntRect(295, 197, 435, 243)),
                node("changed_settings", bounds = IntRect(1050, 126, 1200, 258), clazz = "android.widget.Button").copy(contentDescription = settings),
            )),
            node("changed_body", bounds = IntRect(0, 258, 1200, inputTop - 30), clazz = bodyClass, children = listOf(
                node("changed_message", "正文里提到评论、直播、搜索", IntRect(100, 400, 800, 500)),
            )),
            node("changed_composer", bounds = IntRect(0, inputTop, 1200, inputTop + 131), children = listOf(
                node("changed_edit", "正在输入", IntRect(155, inputTop, 860, inputTop + 131), clazz = "android.widget.EditText"),
            )),
        ))

    private fun transform(tree: UiNodeSnapshot, change: (UiNodeSnapshot) -> UiNodeSnapshot): UiNodeSnapshot =
        change(tree.copy(children = tree.children.map { transform(it, change) }))

    @Test fun recognizesChangedIdsWithoutCopyingMessageText() {
        val result = DouyinChatAdapter().parse(structuralChat()) as ParseResult.Success
        assertEquals("兼容测试好友", result.viewport.conversation.displayName)
        assertEquals(IntRect(0, 258, 1200, 1620), result.viewport.messages.single().mediaBounds)
        assertNull(result.viewport.messages.single().text)
    }

    @Test fun acceptsMissingIdsDifferentResolutionAndExtraContainers() {
        val scaled = transform(structuralChat()) {
            val b = it.bounds
            it.copy(viewId = null, bounds = IntRect(b.left / 2, b.top / 2, b.right / 2, b.bottom / 2))
        }
        val wrapped = scaled.copy(children = listOf(scaled.copy(children = scaled.children)))
        val result = DouyinChatAdapter().parse(wrapped) as ParseResult.Success
        assertEquals(IntRect(0, 129, 600, 810), result.viewport.messages.single().mediaBounds)
    }

    @Test fun structuralPathHandlesKeyboardHiddenAndListView() {
        val result = DouyinChatAdapter().parse(structuralChat(inputTop = 2450, bodyClass = "android.widget.ListView")) as ParseResult.Success
        assertEquals(2450, result.viewport.messages.single().mediaBounds!!.bottom)
    }

    @Test fun structuralPathDoesNotUseBackgroundInboxHeader() {
        val result = DouyinChatAdapter().parse(node("root", children = listOf(
            node("inbox_title", "消息", IntRect(300, 130, 800, 250)), structuralChat(),
        ))) as ParseResult.Success
        assertEquals("兼容测试好友", result.viewport.conversation.displayName)
    }

    @Test fun structuralPendingIdentityRejectsConversationChanges() {
        val pkg = "com.ss.android.ugc.aweme"
        val before = structuralChat()
        val typed = transform(before) { if (it.className == "android.widget.EditText") it.copy(text = "新输入") else it }
        assertTrue(com.yuyan.imemodule.service.capture.samePendingChat(pkg, before, typed))
        assertFalse(com.yuyan.imemodule.service.capture.samePendingChat(pkg, before, structuralChat("另一好友")))
    }

    @Test fun rejectsStructuralCommentsLiveSearchInboxAndGenericMore() {
        for (title in listOf("评论", "评论(12)", "直播", "搜索", "消息", "消息列表", "私信")) {
            assertTrue(title, DouyinChatAdapter().parse(structuralChat(title)) is ParseResult.Skip)
        }
        assertTrue(DouyinChatAdapter().parse(structuralChat(settings = "更多")) is ParseResult.Skip)
        assertTrue(DouyinChatAdapter().parse(structuralChat(settings = "直播设置")) is ParseResult.Skip)
        val comment = transform(structuralChat()) {
            if (it.className == "android.widget.EditText") it.copy(contentDescription = "回复评论") else it
        }
        assertTrue(DouyinChatAdapter().parse(comment) is ParseResult.Skip)
    }

    @Test fun rejectsAmbiguousTitlesInputsAndMissingMessageList() {
        val ambiguous = transform(structuralChat()) {
            if (it.viewId?.endsWith("changed_toolbar") == true) it.copy(children = it.children +
                node("other_title", "另一标题", IntRect(650, 139, 850, 197))) else it
        }
        assertTrue(DouyinChatAdapter().parse(ambiguous) is ParseResult.Skip)
        assertTrue(DouyinChatAdapter().parse(node("root", children = listOf(structuralChat(), structuralChat("第二人")))) is ParseResult.Skip)
        assertTrue(DouyinChatAdapter().parse(structuralChat(bodyClass = "android.widget.FrameLayout")) is ParseResult.Skip)
        val noBack = transform(structuralChat()) { if (it.contentDescription == "返回") it.copy(contentDescription = null) else it }
        assertTrue(DouyinChatAdapter().parse(noBack) is ParseResult.Skip)
    }

    @Test fun acceptsGenericMoreOnlyWithExplicitVoiceComposerEvidence() {
        val tree = transform(structuralChat(settings = "更多")) {
            if (it.viewId?.endsWith("changed_composer") == true) it.copy(children = it.children +
                node("voice", bounds = IntRect(10, 1620, 140, 1751), clazz = "android.widget.Button")
                    .copy(contentDescription = "切换到语音输入")) else it
        }
        assertTrue(DouyinChatAdapter().parse(tree) is ParseResult.Success)
        val misplaced = transform(tree) {
            if (it.contentDescription == "切换到语音输入") it.copy(bounds = IntRect(10, 400, 140, 531)) else it
        }
        assertTrue(DouyinChatAdapter().parse(misplaced) is ParseResult.Skip)
    }

    @Test fun neverBorrowsChatHeaderAndListFromBackgroundSiblingPage() {
        val full = structuralChat()
        val background = full.copy(children = full.children.dropLast(1))
        val foreground = node("comment_layer", children = listOf(full.children.last()))
        assertTrue(DouyinChatAdapter().parse(node("root", children = listOf(background, foreground))) is ParseResult.Skip)
    }

    @Test fun neverBorrowsOnlyHeaderFromAnotherFullScreenPage() {
        val chat = structuralChat()
        val root = node("root", children = listOf(
            node("background_page", children = listOf(chat.children[0])),
            node("foreground_page", children = chat.children.drop(1)),
        ))
        assertTrue(DouyinChatAdapter().parse(root) is ParseResult.Skip)
    }

    @Test fun neverBorrowsIndividualEvidenceFromBackgroundPage() {
        val full = structuralChat()
        val toolbar = full.children[0]
        val title = toolbar.children[1]
        val foregroundToolbar = toolbar.copy(children = toolbar.children.filterIndexed { i, _ -> i != 1 && i != 2 })
        val borrowedTitle = full.copy(children = listOf(foregroundToolbar, full.children[1], full.children[2],
            node("background_page", children = listOf(title))))
        assertTrue("后台标题", DouyinChatAdapter().parse(borrowedTitle) is ParseResult.Skip)
        val borrowedList = full.copy(children = listOf(toolbar, full.children[2],
            node("background_page", children = listOf(full.children[1]))))
        assertTrue("后台消息列表", DouyinChatAdapter().parse(borrowedList) is ParseResult.Skip)
        val borrowedSetting = full.copy(children = listOf(toolbar.copy(children = toolbar.children.dropLast(1)),
            full.children[1], full.children[2], node("background_page", children = listOf(toolbar.children.last()))))
        assertTrue("后台聊天设置", DouyinChatAdapter().parse(borrowedSetting) is ParseResult.Skip)
    }

    @Test fun genericMoreCannotBorrowVoiceEvidenceFromBackgroundPage() {
        val full = structuralChat(settings = "更多")
        val voice = node("voice", bounds = IntRect(10, 1620, 140, 1751), clazz = "android.widget.Button")
            .copy(contentDescription = "切换到语音输入")
        val root = full.copy(children = full.children + node("background_page", children = listOf(voice)))
        assertTrue(DouyinChatAdapter().parse(root) is ParseResult.Skip)
    }

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

    @Test fun pendingCaptureAllowsTypingButRejectsAnotherConversationOrNonChat() {
        val before = chat()
        fun typing(node: UiNodeSnapshot): UiNodeSnapshot = node.copy(
            text = if (node.viewId?.endsWith(":id/msg_et") == true) "正在回复的新文字" else node.text,
            children = node.children.map(::typing),
        )
        val packageName = "com.ss.android.ugc.aweme"
        assertTrue(com.yuyan.imemodule.service.capture.samePendingChat(packageName, before, typing(before)))
        assertFalse(com.yuyan.imemodule.service.capture.samePendingChat(packageName, before, chat("另一会话")))
        assertFalse(com.yuyan.imemodule.service.capture.samePendingChat(packageName, before, node("root")))
    }

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
            clock = { 1000L },
            titleSignature = { it.sha256 },
            mediaCapturer = com.yuyan.imemodule.data.capture.media.MediaAssetCapturer { _, _, requests ->
                assertEquals(IntRect(0, 258, 1200, 1620), requests.single { it.messageIndex == 0 }.bounds)
                mapOf(0 to com.yuyan.imemodule.data.capture.db.PendingAssetEntity(
                    sha256 = "a".repeat(64), localPath = "/test/douyin.webp", mimeType = "image/webp",
                    perceptualHash = null, width = 1200, height = 1362,
                ), -1 to com.yuyan.imemodule.data.capture.db.PendingAssetEntity(
                    sha256 = "b".repeat(64), localPath = "/test/title.png", mimeType = "image/png",
                    perceptualHash = null, width = 176, height = 58,
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
        assertNotNull(AdapterRegistry.forPackage("com.tencent.mobileqq"))
    }
}
