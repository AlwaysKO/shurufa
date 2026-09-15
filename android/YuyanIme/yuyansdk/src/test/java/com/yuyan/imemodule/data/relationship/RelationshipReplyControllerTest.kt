package com.yuyan.imemodule.data.relationship

import com.yuyan.imemodule.data.capture.ActiveChatContext
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RelationshipReplyControllerTest {
    @Test
    fun aiIsOnlyEligibleFromSecondRefresh() {
        val state = RelationshipReplyRefreshState()
        assertEquals(0, state.start())
        assertEquals(1, state.next())
        assertEquals(2, state.next())
        assertEquals(true, state.aiEligible)
    }

    @Test
    fun rejectsLateResponseAfterConversationChanges() {
        val requested = context("direct-visible-title:阿明", 100)
        assertFalse(sameActiveContext(requested, context("direct-visible-title:小周", 101)))
    }

    private fun context(key: String, at: Long) = ActiveChatContext(
        ChatPlatform.WECHAT, "wechat-local", key, null, ConversationType.DIRECT, "在吗", at,
    )
}
