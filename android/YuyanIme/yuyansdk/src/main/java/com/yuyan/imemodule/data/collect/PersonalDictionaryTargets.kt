package com.yuyan.imemodule.data.collect

import java.security.MessageDigest

internal data class DictionarySyncTarget(val url: String, val restoreFromTarget: Boolean, val statePrefix: String)

/** 上传范围取当前显式配置；主控固定，不因某一端离线而切换、覆盖个人决策。 */
internal fun dictionarySyncTargets(targets: List<String>, legacyPrimary: String, authority: String): List<DictionarySyncTarget> =
    targets.map { it.trim().trimEnd('/') }.distinct().map { url ->
        // 已上线主地址继续使用原 token/sequence；其他目标拥有独立、可持久恢复的命名空间。
        val prefix = if (url == legacyPrimary.trimEnd('/')) "" else "target_" +
            MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + "_"
        DictionarySyncTarget(url, url == authority.trimEnd('/'), prefix)
    }
