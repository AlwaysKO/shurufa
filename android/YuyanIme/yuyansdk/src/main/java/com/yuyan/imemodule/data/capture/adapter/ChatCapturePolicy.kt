package com.yuyan.imemodule.data.capture.adapter

import kotlinx.serialization.json.*
import java.math.BigDecimal

/** 配置只提供候选锚点，不提供取消页面、安全窗口或内容边界检查的开关。 */
data class ChatCaptureRule(val id: String, val packageName: String, val minVersionCode: Long,
    val maxVersionCode: Long?, val enabled: Boolean, val titleIds: List<String>, val inputIds: List<String>,
    val bodyIds: List<String>, val backLabels: List<String>, val settingsLabels: List<String>,
    val voiceLabels: List<String>, val voicePosition: String) {
    fun matchesId(viewId: String?, ids: List<String>): Boolean = ids.any {
        viewId == if (it.startsWith("$packageName:id/")) it else "$packageName:id/$it"
    }
}

data class ChatCapturePolicy(val revision: Long, val rules: List<ChatCaptureRule>) {
    fun rule(packageName: String, versionCode: Long): ChatCaptureRule? = rules.firstOrNull {
        it.enabled && it.packageName == packageName && versionCode >= it.minVersionCode &&
            (it.maxVersionCode == null || versionCode <= it.maxVersionCode)
    }
    companion object {
        const val MAX_BYTES = 32768
        private const val SAFE_INTEGER = 9007199254740991L
        private val packages = setOf("com.tencent.mm", "com.ss.android.ugc.aweme")
        private val fields = setOf("id", "packageName", "minVersionCode", "maxVersionCode", "enabled", "titleIds", "inputIds", "bodyIds", "backLabels", "settingsLabels", "voiceLabels", "voicePosition")
        private val shortId = Regex("^[A-Za-z0-9_.=-]{1,128}$")
        private val forbiddenLabel = Regex("[\\*\\[\\]{}\\\\^$|]")
        private fun jsTrim(value: String) = value.trim { ch ->
            ch in "\u0009\u000a\u000b\u000c\u000d\u0020\u00a0\u1680\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008\u2009\u200a\u2028\u2029\u202f\u205f\u3000\ufeff"
        }
        private fun integer(v: JsonElement?): Long? {
            val p = v as? JsonPrimitive ?: return null
            if (p.isString) return null
            return runCatching { BigDecimal(p.content).longValueExact() }.getOrNull()?.takeIf { it in 0..SAFE_INTEGER }
        }
        private fun string(v: JsonElement?): String? = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
        fun parse(raw: String): ChatCapturePolicy? = runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = Json.parseToJsonElement(raw) as JsonObject
            require(root.keys == setOf("schemaVersion", "revision", "rules") && integer(root["schemaVersion"]) == 1L)
            val revision = requireNotNull(integer(root["revision"]))
            val array = root["rules"] as JsonArray
            require(array.size <= 20)
            val ids = mutableSetOf<String>()
            val rules = array.map { element ->
                val r = element as JsonObject
                require(r.keys == fields)
                val id = requireNotNull(string(r["id"]))
                require(Regex("^[a-z][a-z0-9_-]{0,63}$").matches(id) && ids.add(id))
                val pkg = requireNotNull(string(r["packageName"]))
                require(pkg in packages)
                val min = requireNotNull(integer(r["minVersionCode"]))
                val max = if (r["maxVersionCode"] == JsonNull) null else requireNotNull(integer(r["maxVersionCode"]))
                require(max == null || max >= min)
                val enabled = (r["enabled"] as JsonPrimitive).let { require(!it.isString); requireNotNull(it.booleanOrNull) }
                val position = requireNotNull(string(r["voicePosition"]))
                require(position in setOf("left", "right", "either"))
                fun list(field: String): List<String> {
                    val values = (r[field] as JsonArray).map { requireNotNull(string(it)) }
                    require(values.size <= 16 && values.distinct().size == values.size)
                    values.forEach { value ->
                        require(jsTrim(value).isNotEmpty() && jsTrim(value) == value && value.none { it.code < 32 || it.code == 127 })
                        if (field.endsWith("Ids")) require(shortId.matches(value) ||
                            (value.startsWith("$pkg:id/") && shortId.matches(value.removePrefix("$pkg:id/"))))
                        else require(value.codePointCount(0, value.length) <= 64 && !forbiddenLabel.containsMatchIn(value))
                    }
                    return values
                }
                ChatCaptureRule(id, pkg, min, max, enabled, list("titleIds"), list("inputIds"), list("bodyIds"),
                    list("backLabels"), list("settingsLabels"), list("voiceLabels"), position)
            }
            ChatCapturePolicy(revision, rules)
        }.getOrNull()

        fun builtIn(): ChatCapturePolicy {
            fun rule(pkg: String, id: String, titles: List<String>, inputs: List<String>, bodies: List<String>) = ChatCaptureRule(
                id, pkg, 0, null, true, titles, inputs, bodies, listOf("返回", "返回上一页", "Back"),
                listOf("聊天设置", "聊天详情", "会话设置", "群聊设置", "群聊详情", "更多"),
                listOf("切换到语音输入", "切换到语音", "按住说话", "语音"), "either")
            return ChatCapturePolicy(0, listOf(
                rule("com.tencent.mm", "wechat-chat", listOf("chatting_title"), listOf("chatting_content_et", "chat_input"), emptyList()),
                rule("com.ss.android.ugc.aweme", "douyin-chat", listOf("vw3", "vww"), listOf("msg_et"), listOf("jta", "v6q"))))
        }
    }
}

/** 最多保留四个来源；切换 epoch 拒绝 A→B→A 后 A 的旧响应。 */
class ChatCapturePolicyCache {
    private val sources = linkedMapOf<String, ChatCapturePolicy>()
    private var source = ""
    private var epoch = 0L
    @Synchronized fun switchSource(value: String): Long {
        if (source != value) { source = value; epoch++ }
        return epoch
    }
    @Synchronized fun accept(source: String, raw: String, expectedEpoch: Long = epoch): Boolean {
        if (source != this.source || epoch != expectedEpoch) return false
        val next = ChatCapturePolicy.parse(raw) ?: return false
        if (next.revision <= (sources[source]?.revision ?: -1L)) return false
        sources.remove(source)
        sources[source] = next
        while (sources.size > 4) sources.remove(sources.keys.first())
        return true
    }
    @Synchronized fun currentForSource(value: String): ChatCapturePolicy {
        switchSource(value)
        return current()
    }
    @Synchronized fun current(): ChatCapturePolicy = sources[source] ?: ChatCapturePolicy.builtIn()
}
