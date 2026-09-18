package com.yuyan.imemodule.expression.send

import org.json.JSONObject

/** 仅描述已内置的发送机制，不允许脚本、外部URI或组件名。 */
data class ExpressionDeliveryRule(
    val id: String,
    val packageName: String,
    val mimeTypes: List<String>,
    val minVersionCode: Long = 0,
    val maxVersionCode: Long? = null,
    val versionName: String? = null,
    val minSdk: Int = 23,
    val enabled: Boolean = true,
    val method: String = "commit_content",
    val requireCompatIme: Boolean = false,
    val requiredEditorExtras: Map<String, Int> = emptyMap(),
    val action: String? = null,
    val uriKey: String? = null,
) {
    fun matches(pkg: String, mime: String, version: String?, code: Long?, sdk: Int): Boolean =
        packageName == pkg && mime in mimeTypes && sdk >= minSdk &&
            (versionName == null || versionName == version) &&
            (if (code == null) minVersionCode == 0L && maxVersionCode == null
             else code >= minVersionCode && (maxVersionCode == null || code <= maxVersionCode))
}

data class ExpressionDeliveryPolicy(val revision: Long, val rules: List<ExpressionDeliveryRule>) {
    fun match(pkg: String, mime: String, version: String?, code: Long?, sdk: Int): ExpressionDeliveryRule? =
        rules.firstOrNull { it.matches(pkg, mime, version, code, sdk) }

    companion object {
        val packages = setOf("com.tencent.mm", "com.tencent.mobileqq", "com.ss.android.ugc.aweme")
        val mimes = listOf("image/gif", "image/webp", "image/png", "image/jpeg")
        const val MAX_BYTES = 65_536
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
        private val identifier = Regex("^[A-Za-z_][A-Za-z0-9_.]*$")
        private val ruleKeys = setOf("id", "packageName", "mimeTypes", "minVersionCode", "maxVersionCode",
            "versionName", "minSdk", "enabled", "method", "requireCompatIme", "requiredEditorExtras", "action", "uriKey")
        fun defaults() = ExpressionDeliveryPolicy(0, listOf(
            ExpressionDeliveryRule("wechat-gif", "com.tencent.mm", listOf("image/gif"),
                versionName = "8.0.78", minSdk = 26, method = "private_command", requireCompatIme = true,
                requiredEditorExtras = mapOf("SUPPORT_SOGOU_EXPRESSION" to 1),
                action = "com.sogou.inputmethod.exp.commit", uriKey = "EXP_PATH_URI"),
            ExpressionDeliveryRule("wechat-static", "com.tencent.mm", mimes.drop(1)),
            ExpressionDeliveryRule("qq-content", "com.tencent.mobileqq", mimes),
            ExpressionDeliveryRule("douyin-content", "com.ss.android.ugc.aweme", mimes),
        ))

        fun parse(raw: String): ExpressionDeliveryPolicy? = runCatching {
            require(raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES)
            val root = JSONObject(raw)
            require(root.keys().asSequence().toSet() == setOf("schemaVersion", "revision", "rules"))
            require(integer(root.get("schemaVersion")) == 1L)
            val revision = integer(root.get("revision"))
            val array = root.getJSONArray("rules")
            require(array.length() <= 40)
            val ids = mutableSetOf<String>()
            val rules = (0 until array.length()).map { index ->
                val r = array.getJSONObject(index)
                require(r.keys().asSequence().toSet() == ruleKeys)
                val id = r.get("id") as String
                require(Regex("^[a-z][a-z0-9_-]{0,63}$").matches(id) && ids.add(id))
                val pkg = r.get("packageName") as String
                require(pkg in packages)
                val types = r.getJSONArray("mimeTypes")
                require(types.length() in 1..4)
                val mimeTypes = (0 until types.length()).map { types.get(it) as String }
                require(mimeTypes.all { it in mimes } && mimeTypes.distinct().size == mimeTypes.size)
                val min = integer(r.get("minVersionCode"))
                val max = if (r.isNull("maxVersionCode")) null else integer(r.get("maxVersionCode"))
                require(max == null || max >= min)
                val version = nullableString(r, "versionName")
                require(version == null || version.length in 1..80)
                val sdk = integer(r.get("minSdk"))
                require(sdk in 23..100)
                val extras = r.getJSONObject("requiredEditorExtras")
                require(extras.length() <= 8)
                val requirements = extras.keys().asSequence().associateWith { key ->
                    require(key.length <= 160 && identifier.matches(key))
                    val n = extras.get(key)
                    require(n is Number && n.toDouble().isFinite() && n.toDouble() == n.toLong().toDouble())
                    require(n.toLong() in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong())
                    n.toInt()
                }
                val method = r.get("method") as String
                val action = nullableString(r, "action")
                val uriKey = nullableString(r, "uriKey")
                when (method) {
                    "commit_content" -> {
                        require(action == null && uriKey == null)
                        // 已在接收端证实静态化的路线不能被误配置重新启用。
                        // 未来标准GIF交付只能显式指定经验证的新版本，不能全版本盲放。
                        if (pkg == "com.tencent.mm" && "image/gif" in mimeTypes) {
                            require(version != null && version != "8.0.78")
                        }
                    }
                    "private_command" -> {
                        require(sdk >= 26)
                        require(action != null && action.length in 1..160 && identifier.matches(action))
                        require(uriKey != null && uriKey.length in 1..80 && identifier.matches(uriKey))
                    }
                    else -> error("unknown delivery method")
                }
                ExpressionDeliveryRule(id, pkg, mimeTypes, min, max, version, sdk.toInt(),
                    r.get("enabled") as Boolean, method, r.get("requireCompatIme") as Boolean,
                    requirements, action, uriKey)
            }
            ExpressionDeliveryPolicy(revision, rules)
        }.getOrNull()

        private fun integer(value: Any): Long {
            require(value is Number)
            val d = value.toDouble()
            require(d.isFinite() && d >= 0 && d <= MAX_SAFE_INTEGER.toDouble() && d == value.toLong().toDouble())
            return value.toLong()
        }
        private fun nullableString(json: JSONObject, key: String): String? =
            if (json.isNull(key)) null else json.get(key) as String
    }
}
