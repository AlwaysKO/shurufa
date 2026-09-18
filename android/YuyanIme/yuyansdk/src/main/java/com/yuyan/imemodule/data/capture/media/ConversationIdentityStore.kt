package com.yuyan.imemodule.data.capture.media

import android.content.SharedPreferences
import com.yuyan.imemodule.data.capture.sha256
import org.json.JSONObject

/** 只存已确认的精确字形证据，不按名字做全局关联。 */
data class StoredConversationIdentity(val key: String, val name: String, val type: String)
interface ConversationIdentityStore {
    fun find(scope: String, visual: String): StoredConversationIdentity?
    fun save(scope: String, visual: String, identity: StoredConversationIdentity)
}
class MemoryConversationIdentityStore : ConversationIdentityStore {
    private val entries = mutableMapOf<Pair<String, String>, StoredConversationIdentity>()
    @Synchronized override fun find(scope: String, visual: String) = entries[scope to visual]
    @Synchronized override fun save(scope: String, visual: String, identity: StoredConversationIdentity) { entries[scope to visual] = identity }
}
class PreferenceConversationIdentityStore(private val preferences: SharedPreferences) : ConversationIdentityStore {
    private fun key(scope: String, visual: String) = sha256("$scope|$visual".toByteArray(Charsets.UTF_8))
    override fun find(scope: String, visual: String): StoredConversationIdentity? = runCatching {
        val raw = preferences.getString(key(scope, visual), null) ?: return null
        val json = JSONObject(raw)
        StoredConversationIdentity(json.getString("key"), json.getString("name"), json.getString("type"))
            .takeIf { (it.key.startsWith("screenshot-v2:") || it.key.startsWith("capture-v3:")) && it.name.isNotBlank() }
    }.getOrNull()
    override fun save(scope: String, visual: String, identity: StoredConversationIdentity) {
        if (find(scope, visual) == identity) return
        val json = JSONObject().put("key", identity.key).put("name", identity.name).put("type", identity.type)
        preferences.edit().putString(key(scope, visual), json.toString()).apply()
    }
}
