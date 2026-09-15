package com.yuyan.imemodule.data.capture

import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActiveChatContextStoreTest {
    @Test
    fun returnsFreshWechatContextAndExpiresOldValues() {
        val storage = MemoryActiveChatStorage()
        var now = 1_000L
        val store = ActiveChatContextStore(storage, clock = { now }, ttlMillis = 500)
        store.save(ActiveChatContext(ChatPlatform.WECHAT, "local", "peer", "阿明", ConversationType.DIRECT, "在吗", now))
        assertEquals("在吗", store.current("com.tencent.mm")?.latestIncomingText)
        now = 1_501L
        assertNull(store.current("com.tencent.mm"))
        assertNull(store.current("com.example.other"))
    }

    @Test
    fun clearRemovesPreviouslyStoredRealtimeReplyContext() {
        val store = ActiveChatContextStore(MemoryActiveChatStorage(), clock = { 1_000L })
        store.save(ActiveChatContext(ChatPlatform.WECHAT, "local", "peer", "阿明", ConversationType.DIRECT, "在吗", 1_000L))

        store.clear()

        assertNull(store.current("com.tencent.mm"))
    }
}
