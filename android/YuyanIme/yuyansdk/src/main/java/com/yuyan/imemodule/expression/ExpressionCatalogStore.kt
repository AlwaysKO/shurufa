package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionCatalogDocument
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/** 完整元数据与原件缓存分开；用户/服务端/APK内置版本任一变化均不能沿用旧私有目录。 */
internal class ExpressionCatalogStore(directory: File, endpoint: String, user: String, bundledVersion: String) {
    private val json = Json { ignoreUnknownKeys = true }
    internal val key = MessageDigest.getInstance("SHA-256")
        .digest("${endpoint.trimEnd('/')}\u0000$user\u0000$bundledVersion".toByteArray())
        .joinToString("") { "%02x".format(it) }
    private val target = File(directory, "$key.json")
    internal val refreshMutex = refreshLocks.getOrPut(target.canonicalPath) { Mutex() }

    fun read(): ExpressionCatalogDocument? = runCatching {
        check(target.isFile && target.length() <= MAX_BYTES)
        json.decodeFromString<ExpressionCatalogDocument>(target.readText()).takeIf { it.complete }
    }.getOrNull()

    fun write(document: ExpressionCatalogDocument) {
        require(document.complete)
        val bytes = json.encodeToString(document).toByteArray()
        require(bytes.size <= MAX_BYTES)
        val directory = requireNotNull(target.parentFile)
        check(directory.mkdirs() || directory.isDirectory)
        val part = File.createTempFile("catalog-", ".part", directory)
        try {
            FileOutputStream(part).use { it.write(bytes); it.fd.sync() }
            check(part.renameTo(target)) { "cannot replace catalog metadata" }
        } finally { part.delete() }
    }

    companion object {
        const val MAX_BYTES = 16 * 1024 * 1024
        private val refreshLocks = ConcurrentHashMap<String, Mutex>()
    }
}
