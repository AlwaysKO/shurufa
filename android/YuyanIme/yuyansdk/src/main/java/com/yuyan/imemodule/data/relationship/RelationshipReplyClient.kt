package com.yuyan.imemodule.data.relationship

import com.yuyan.imemodule.data.capture.ActiveChatContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

@Serializable
data class RelationshipReplyCandidate(val text: String, val source: String)

@Serializable
data class RelationshipReplyResponse(
    @SerialName("conversation_id") val conversationId: Long? = null,
    val candidates: List<RelationshipReplyCandidate> = emptyList(),
    @SerialName("ai_used") val aiUsed: Boolean = false,
    @SerialName("profile_version") val profileVersion: Int? = null,
    @SerialName("fallback_reason") val fallbackReason: String? = null,
)

@Serializable
private data class RelationshipReplyRequest(
    val platform: String,
    @SerialName("account_key") val accountKey: String,
    @SerialName("external_key") val externalKey: String,
    @SerialName("context_text") val contextText: String,
    @SerialName("refresh_count") val refreshCount: Int,
    @SerialName("reply_session_id") val replySessionId: String,
    val limit: Int,
)

interface RelationshipReplySource {
    suspend fun fetch(context: ActiveChatContext, refreshCount: Int, replySessionId: String, limit: Int = 5): RelationshipReplyResponse
}

class RelationshipReplyClient(
    baseUrl: String,
    private val deviceId: String,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS).readTimeout(15, TimeUnit.SECONDS).build(),
) : RelationshipReplySource {
    private val baseUrl = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun fetch(context: ActiveChatContext, refreshCount: Int, replySessionId: String, limit: Int): RelationshipReplyResponse =
        withContext(Dispatchers.IO) {
            val body = RelationshipReplyRequest(
                context.platform.wireName, context.accountKey, context.externalKey,
                context.latestIncomingText, refreshCount, replySessionId, limit,
            )
            val request = Request.Builder()
                .url("$baseUrl/api/v1/mobile/relationships/ai-replies")
                .header("X-Device-Id", deviceId)
                .post(json.encodeToString(body).toRequestBody(JSON))
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("relationship reply failed (${response.code})")
                json.decodeFromString(RelationshipReplyResponse.serializer(), response.body?.string() ?: error("empty response"))
            }
        }

    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
