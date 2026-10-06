package com.yuyan.imemodule.data.capture.adapter

import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import com.yuyan.imemodule.data.capture.ui.stableTreeSignature
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

/** 合成未来布局，不冒充未发布宿主版本的真机结果。 */
class ChatSemanticCompatibilityTest {
    private val json = Json { ignoreUnknownKeys = true }
    private fun tree(inputDescription: String = "消息", password: Boolean = false,
                     hiddenInput: Boolean = false, header: String = "聊天设置", title: String = "测试会话",
                     bodyScrollable: Boolean = true, voice: Boolean = false): UiNodeSnapshot {
        fun node(text: String?, left: Int, top: Int, right: Int, bottom: Int,
                 editable: Boolean = false, scrollable: Boolean = false, secret: Boolean = false,
                 visible: Boolean = true, description: String? = null, children: List<JsonObject> = emptyList()) = buildJsonObject {
            put("viewId", JsonNull); put("className", "custom.vendor.VirtualNode")
            put("text", text?.let(::JsonPrimitive) ?: JsonNull)
            put("contentDescription", description?.let(::JsonPrimitive) ?: JsonNull)
            put("bounds", buildJsonObject { put("left", left); put("top", top); put("right", right); put("bottom", bottom) })
            put("children", JsonArray(children)); put("editable", editable); put("scrollable", scrollable)
            put("password", secret); put("visibleToUser", visible)
        }
        val nodes = listOf(
            node(null, 0, 80, 1000, 180, children = listOf(
                node("返回", 0, 80, 100, 180), node(title, 250, 90, 650, 170), node(header, 850, 80, 1000, 180))),
            node(null, 0, 180, 1000, 1400, scrollable = bodyScrollable),
            node(null, 0, 1420, 1000, 1530, children = listOf(
                node("输入草稿", 140, 1420, 820, 1530, editable = true, secret = password,
                    visible = !hiddenInput, description = inputDescription),
                node(if (voice) "语音" else null, 830, 1420, 970, 1530))),
        )
        return json.decodeFromString(node(null, 0, 0, 1000, 2000, children = nodes).toString())
    }
    private fun adapters() = listOf(WeChatChatAdapter(), DouyinChatAdapter())
    @Test fun bothAppsRecognizeSemanticChatAfterIdsAndAllClassNamesChange() {
        for (adapter in adapters()) {
            val result = adapter.parse(tree())
            assertTrue(adapter.packageName, result is ParseResult.Success)
            val viewport = (result as ParseResult.Success).viewport
            assertEquals("测试会话", viewport.conversation.displayName)
            assertEquals(1420, viewport.messages.single().mediaBounds!!.bottom)
            assertNull(viewport.messages.single().text)
        }
    }
    @Test fun semanticChatSurvivesWrappersAndScreenScaling() {
        fun scale(n: UiNodeSnapshot): UiNodeSnapshot = n.copy(bounds = n.bounds.let {
            com.yuyan.imemodule.data.capture.ui.IntRect(it.left / 2, it.top / 2, it.right / 2, it.bottom / 2)
        }, children = n.children.map(::scale))
        for (adapter in adapters()) {
            val base = scale(tree())
            assertTrue(adapter.parse(base.copy(children = listOf(base))) is ParseResult.Success)
        }
    }
    @Test fun genericMoreStillRequiresVoiceComposerAndSafeSameLayerEvidence() {
        for (adapter in adapters()) {
            assertTrue(adapter.parse(tree(header = "更多", voice = true)) is ParseResult.Success)
            assertTrue(adapter.parse(tree(header = "更多")) is ParseResult.Skip)
            val base = tree()
            val background = base.copy(children = listOf(base.children.first()))
            val foreground = base.copy(children = base.children.drop(1))
            assertTrue(adapter.parse(base.copy(children = listOf(background, foreground))) is ParseResult.Skip)
        }
    }
    @Test fun semanticFieldsCannotTurnCommentsSearchVideosOrPasswordIntoChat() {
        for (adapter in adapters()) {
            for (description in listOf("回复评论", "搜索", "comment", "search", "弹幕"))
                assertTrue(adapter.parse(tree(inputDescription = description)) is ParseResult.Skip)
            for (title in listOf("评论", "搜索", "消息列表", "直播"))
                assertTrue(adapter.parse(tree(title = title)) is ParseResult.Skip)
            assertTrue(adapter.parse(tree(password = true)) is ParseResult.Skip)
            assertTrue(adapter.parse(tree(hiddenInput = true)) is ParseResult.Skip)
            assertTrue(adapter.parse(tree(bodyScrollable = false)) is ParseResult.Skip)
        }
    }
    @Test fun semanticsChangesInvalidateStableSnapshotSignature() {
        assertNotEquals(tree().stableTreeSignature(), tree(password = true).stableTreeSignature())
        assertNotEquals(tree().stableTreeSignature(), tree(bodyScrollable = false).stableTreeSignature())
    }
}
