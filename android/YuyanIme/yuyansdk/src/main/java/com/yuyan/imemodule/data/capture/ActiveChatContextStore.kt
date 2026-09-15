package com.yuyan.imemodule.data.capture

import android.content.Context
import com.yuyan.imemodule.data.capture.model.ChatPlatform
import com.yuyan.imemodule.data.capture.model.ConversationType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class ActiveChatContext(
    val platform: ChatPlatform,
    val accountKey: String,
    val externalKey: String,
    val displayName: String?,
    val conversationType: ConversationType,
    val latestIncomingText: String,
    val observedAt: Long,
)

interface ActiveChatStorage {
    fun read(): String?
    fun write(value: String)
    fun clear()
}

class MemoryActiveChatStorage : ActiveChatStorage {
    private var value: String? = null
    override fun read(): String? = value
    override fun write(value: String) { this.value = value }
    override fun clear() { value = null }
}

private class PreferencesActiveChatStorage(context: Context) : ActiveChatStorage {
    private val prefs = context.getSharedPreferences("active_chat_context", Context.MODE_PRIVATE)
    override fun read(): String? = prefs.getString("value", null)
    override fun write(value: String) { prefs.edit().putString("value", value).apply() }
    override fun clear() { prefs.edit().remove("value").apply() }
}

class ActiveChatContextStore(
    private val storage: ActiveChatStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = DEFAULT_TTL_MILLIS,
) {
    constructor(context: Context) : this(PreferencesActiveChatStorage(context.applicationContext))

    private val json = Json { ignoreUnknownKeys = true }

    fun save(context: ActiveChatContext) {
        storage.write(json.encodeToString(Stored.serializer(), Stored.from(context)))
    }

    fun clear() = storage.clear()

    fun current(packageName: String): ActiveChatContext? {
        if (packageName != "com.tencent.mm") return null
        val stored = runCatching { json.decodeFromString(Stored.serializer(), storage.read() ?: return null) }.getOrNull()
            ?: return null
        if (clock() - stored.observedAt > ttlMillis || stored.observedAt > clock() + 5_000) {
            storage.clear()
            return null
        }
        return runCatching { stored.toContext() }.getOrElse {
            storage.clear()
            null
        }
    }

    @Serializable
    private data class Stored(
        val platform: String,
        val accountKey: String,
        val externalKey: String,
        val displayName: String?,
        val conversationType: String,
        val latestIncomingText: String,
        val observedAt: Long,
    ) {
        fun toContext() = ActiveChatContext(
            ChatPlatform.entries.first { it.wireName == platform }, accountKey, externalKey, displayName,
            ConversationType.entries.first { it.wireName == conversationType }, latestIncomingText, observedAt,
        )
        companion object {
            fun from(value: ActiveChatContext) = Stored(
                value.platform.wireName, value.accountKey, value.externalKey, value.displayName,
                value.conversationType.wireName, value.latestIncomingText, value.observedAt,
            )
        }
    }

    companion object { const val DEFAULT_TTL_MILLIS = 2 * 60 * 1000L }
}
