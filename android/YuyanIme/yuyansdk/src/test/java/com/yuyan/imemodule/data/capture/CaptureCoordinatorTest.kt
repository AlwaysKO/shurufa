package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.adapter.ChatAppAdapter
import com.yuyan.imemodule.data.capture.adapter.ParseResult
import com.yuyan.imemodule.data.capture.adapter.ParsedViewport
import com.yuyan.imemodule.data.capture.adapter.SkipReason
import com.yuyan.imemodule.data.capture.db.PendingMessageEntity
import com.yuyan.imemodule.data.capture.db.SeenMessageEntity
import com.yuyan.imemodule.data.capture.model.CapturedConversation
import com.yuyan.imemodule.data.capture.model.CapturedMessage
import com.yuyan.imemodule.data.capture.model.ChatDirection
import com.yuyan.imemodule.data.capture.model.ChatMessageType
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import com.yuyan.imemodule.data.capture.ui.IntRect
import com.yuyan.imemodule.data.capture.ui.UiNodeSnapshot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureCoordinatorTest {
    private val snapshot = UiNodeSnapshot(null, "root", null, null, IntRect(0, 0, 100, 100), emptyList())
    private val conversation = CapturedConversation(
        platform = ChatPlatform.WECHAT,
        accountKey = "account",
        externalKey = "peer",
        displayName = "对方",
        conversationType = ConversationType.DIRECT,
        identityConfidence = 0.95,
    )

    @Test
    fun firstViewportWritesMessagesAndSecondIdenticalViewportDoesNot() = runBlocking {
        val adapter = FakeAdapter(success(message("第一条", "18:30")))
        val store = FakeStore()
        var wakes = 0
        val coordinator = coordinator(adapter, store) { wakes += 1 }

        coordinator.capture("com.tencent.mm", snapshot)
        coordinator.capture("com.tencent.mm", snapshot)

        assertEquals(1, store.pending.size)
        assertEquals(1, store.seen.size)
        assertEquals(1, wakes)
    }

    @Test
    fun overlappingViewportsOnlyWriteNewMessages() = runBlocking {
        val first = message("第一条", "18:30")
        val overlap = message("重叠消息", "18:31")
        val third = message("第三条", "18:32")
        val adapter = FakeAdapter(success(first, overlap))
        val store = FakeStore()
        val coordinator = coordinator(adapter, store)

        coordinator.capture("com.tencent.mm", snapshot)
        adapter.result = success(overlap, third)
        coordinator.capture("com.tencent.mm", snapshot)

        assertEquals(3, store.pending.size)
    }

    @Test
    fun twoRealMessagesWithSameTextAreBothKept() = runBlocking {
        val adapter = FakeAdapter(success(
            message("相同文本", "18:30", ordinal = 0),
            message("相同文本", "18:30", ordinal = 1),
        ))
        val store = FakeStore()

        coordinator(adapter, store).capture("com.tencent.mm", snapshot)

        assertEquals(2, store.pending.size)
        assertEquals(2, store.pending.map { it.fingerprint }.distinct().size)
    }

    @Test
    fun skipResultDoesNotWriteDatabase() = runBlocking {
        val adapter = FakeAdapter(ParseResult.Skip(SkipReason.AMBIGUOUS_CONVERSATION))
        val store = FakeStore()

        coordinator(adapter, store).capture("com.tencent.mm", snapshot)

        assertTrue(store.pending.isEmpty())
        assertTrue(store.seen.isEmpty())
    }

    @Test
    fun parserExceptionIsSwallowedAndCounted() = runBlocking {
        val adapter = object : ChatAppAdapter {
            override val packageName = "com.tencent.mm"
            override fun parse(root: UiNodeSnapshot): ParseResult = error("broken fixture")
        }
        val coordinator = coordinator(adapter, FakeStore())

        coordinator.capture("com.tencent.mm", snapshot)

        assertEquals(1, coordinator.internalFailureCount.get())
    }

    private fun coordinator(
        adapter: ChatAppAdapter,
        store: FakeStore,
        wake: () -> Unit = {},
    ) = CaptureCoordinator(
        adapterForPackage = { adapter },
        store = store,
        deviceId = { "00000000-0000-4000-8000-000000000001" },
        clock = { 1_700_000_000_000L },
        wakeUploader = wake,
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

    private class FakeAdapter(var result: ParseResult) : ChatAppAdapter {
        override val packageName = "com.tencent.mm"
        override fun parse(root: UiNodeSnapshot): ParseResult = result
    }

    private class FakeStore : CaptureOutboxStore {
        val seen = linkedSetOf<String>()
        val pending = mutableListOf<PendingMessageEntity>()

        override suspend fun enqueueIfNew(
            seenMessage: SeenMessageEntity,
            pendingMessage: PendingMessageEntity,
        ): Boolean {
            if (!seen.add(seenMessage.fingerprint)) return false
            pending += pendingMessage
            return true
        }
    }
}
