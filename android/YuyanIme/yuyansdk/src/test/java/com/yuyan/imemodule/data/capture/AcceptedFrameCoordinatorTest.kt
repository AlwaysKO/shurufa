package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.adapter.ChatAppAdapter
import com.yuyan.imemodule.data.capture.adapter.ParseResult
import com.yuyan.imemodule.data.capture.adapter.ParsedViewport
import com.yuyan.imemodule.data.capture.adapter.SkipReason
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.PendingAssetEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.media.MediaAssetCapturer
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.model.stableKeyOrNull
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [30])
class AcceptedFrameCoordinatorTest {
    @org.junit.Before @org.junit.After fun reset() {
        com.yuyan.imemodule.data.collect.resetImageInputForTest()
        com.yuyan.imemodule.data.collect.resetGameWorkRuntimeForTest()
    }
    @Test fun acceptedTitleUsesFrozenOldConversationAfterResetAndKeepsCaptureTime() = runBlocking {
        var generation = 1L
        var now = 1_000L
        var frame = 0
        var attempt: com.yuyan.imemodule.data.capture.media.ChatCaptureAttempt? = null
        val store = FakeStore()
        val adapter = FakeAdapter(ParseResult.Success(ParsedViewport(
            conversation.copy(platform = ChatPlatform.DOUYIN, accountKey = "douyin-local", displayName = "原会话"),
            listOf(mediaMessage(IntRect(0, 20, 100, 80)).copy(direction = ChatDirection.SYSTEM,
                metadata = mapOf("capture_source" to "douyin_screenshot", "capture_kind" to "conversation_screenshot"))),
            titleBounds = IntRect(0, 0, 100, 20))))
        lateinit var worker: CaptureCoordinator
        worker = CaptureCoordinator(adapterForPackage = { adapter }, store = store, deviceId = { "device" },
            wakeUploader = {}, clock = { now }, captureGeneration = { generation }, titleSignature = { it.sha256 },
            mediaCapturer = MediaAssetCapturer { _, _, _ ->
                attempt?.let {
                    assertTrue(it.acceptFrame())
                    generation++
                    worker.resetConversationIdentity()
                    now += 5_000
                }
                mapOf(0 to pendingAsset("body-${frame++}"), -1 to pendingAsset("a".repeat(64)))
            })
        worker.capture(adapter.packageName, snapshot, 7)
        now += 800
        worker.capture(adapter.packageName, snapshot, 7)
        now += 800
        attempt = com.yuyan.imemodule.data.capture.media.ChatCaptureAttempt({ true }, { generation == 1L }, { true }, { now })
        kotlinx.coroutines.withContext(requireNotNull(attempt)) { worker.capture(adapter.packageName, snapshot, 7) }
        assertEquals(3, store.pending.size)
        val saved = store.pending.last().payloadJson
        assertTrue(saved.contains("原会话"))
        assertTrue(saved.contains("confirmed"))
        assertTrue("记录实际取帧时刻，不使用延迟处理时刻", saved.contains("1970-01-01T00:00:02.600Z"))
    }

    @Test fun acceptedFramePersistsAfterNavigationWithoutUpdatingNewPageContext() = runBlocking {
        var generation = 1L
        var authorized = true
        var contexts = 0
        val store = FakeStore()
        val adapter = FakeAdapter(success(mediaMessage(IntRect(0, 0, 80, 80))))
        val attempt = com.yuyan.imemodule.data.capture.media.ChatCaptureAttempt({ true }, { generation == 1L }, { authorized })
        lateinit var worker: CaptureCoordinator
        worker = CaptureCoordinator(adapterForPackage = { adapter }, store = store, deviceId = { "device" },
            wakeUploader = {}, captureGeneration = { generation }, onViewportParsed = { contexts++ },
            mediaCapturer = MediaAssetCapturer { _, _, _ ->
                assertTrue(attempt.acceptFrame())
                generation++
                worker.resetConversationIdentity()
                mapOf(0 to pendingAsset("accepted-before-leaving"))
            })
        kotlinx.coroutines.withContext(attempt) { worker.capture(adapter.packageName, snapshot, 7) }
        assertEquals(1, store.pending.size)
        assertEquals(0, contexts)
        authorized = false
        kotlinx.coroutines.withContext(attempt) {
            assertEquals(CapturePersistResult.FAILED, worker.captureParsed(conversation,
                listOf(mediaMessage(IntRect(0, 0, 80, 80))), mapOf(0 to pendingAsset("revoked")), captureToken = 1L))
        }
        assertEquals(1, store.pending.size)
    }

    private val snapshot = UiNodeSnapshot(null, "root", null, null, IntRect(0, 0, 100, 100), emptyList())
    private val conversation = CapturedConversation(
        platform = ChatPlatform.WECHAT,
        accountKey = "account",
        externalKey = "peer",
        displayName = "对方",
        conversationType = ConversationType.DIRECT,
        identityConfidence = 0.95,
    )

    private fun success(vararg messages: CapturedMessage) = ParseResult.Success(
        ParsedViewport(conversation, messages.toList()),
    )

    private fun message(text: String, time: String, ordinal: Int = 0) = CapturedMessage(
        conversationKey = null,
        senderKey = "peer",
        senderName = "对方",
        direction = ChatDirection.INCOMING,
        messageType = ChatMessageType.TEXT,
        text = text,
        displayedTime = time,
        sameContentOrdinal = ordinal,
    )

    private fun mediaMessage(bounds: IntRect, ordinal: Int = 0) = CapturedMessage(
        conversationKey = null,
        senderKey = "peer",
        direction = ChatDirection.INCOMING,
        messageType = ChatMessageType.IMAGE,
        sameContentOrdinal = ordinal,
        mediaBounds = bounds,
    )

    private fun pendingAsset(hash: String) = PendingAssetEntity(
        sha256 = hash,
        localPath = "/tmp/$hash",
        mimeType = "image/png",
        perceptualHash = null,
        width = 60,
        height = 60,
    )

    private class FakeAdapter(var result: ParseResult) : ChatAppAdapter {
        override val packageName = "com.tencent.mm"
        override fun parse(root: UiNodeSnapshot): ParseResult = result
    }

    private class FakeStore : CaptureOutboxStore {
        val seen = linkedSetOf<String>()
        val pending = mutableListOf<PendingMessageEntity>()
        val assets = linkedMapOf<String, PendingAssetEntity>()

        override suspend fun enqueueIfNew(
            seenMessage: SeenMessageEntity,
            pendingMessage: PendingMessageEntity,
            pendingAssets: List<PendingAssetEntity>,
        ): Boolean {
            if (!seen.add(seenMessage.fingerprint)) return false
            pendingAssets.forEach { assets.putIfAbsent(it.sha256, it) }
            pending += pendingMessage
            return true
        }
    }
}
