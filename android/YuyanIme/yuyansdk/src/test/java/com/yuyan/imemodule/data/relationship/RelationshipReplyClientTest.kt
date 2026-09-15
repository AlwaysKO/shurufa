package com.yuyan.imemodule.data.relationship

import com.yuyan.imemodule.data.capture.ActiveChatContext
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class RelationshipReplyClientTest {
    @Test
    fun postsCompleteIdentityContextAndRefreshCount() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"conversation_id":1,"candidates":[{"text":"咋啦","source":"ai"}],"ai_used":true,"profile_version":2}"""))
        server.start()
        try {
            val client = RelationshipReplyClient(server.url("/").toString(), "00000000-0000-4000-8000-000000000001", OkHttpClient())
            val result = client.fetch(context(), refreshCount = 2, replySessionId = "10000000-0000-4000-8000-000000000001")
            val request = server.takeRequest()
            assertEquals("/api/v1/mobile/relationships/ai-replies", request.path)
            assertEquals("00000000-0000-4000-8000-000000000001", request.getHeader("X-Device-Id"))
            val body = request.body.readUtf8()
            assert(body.contains("\"external_key\":\"visible-title:阿明\""))
            assert(body.contains("\"refresh_count\":2"))
            assert(body.contains("\"reply_session_id\":\"10000000-0000-4000-8000-000000000001\""))
            assertEquals("咋啦", result.candidates.single().text)
        } finally { server.shutdown() }
    }

    private fun context() = ActiveChatContext(
        ChatPlatform.WECHAT, "wechat-local", "visible-title:阿明", "阿明",
        ConversationType.DIRECT, "在吗", 1_000L,
    )
}
