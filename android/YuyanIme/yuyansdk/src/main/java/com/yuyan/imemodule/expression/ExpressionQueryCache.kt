package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 只管理本功能自己的索引/预取原件，不清理历史发送缓存或 APK 导出的素材。 */
internal class ExpressionQueryCache(
    private val cache: ExpressionCache,
    private val maxQueries: Int = 128,
    private val maxBytes: Long = 64L * 1024 * 1024,
    val maxAssetBytes: Long = 2L * 1024 * 1024,
    private val ttlMs: Long = 7L * 24 * 60 * 60 * 1000,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val indexes = File(cache.queryRoot, "indexes")
    private val originals = File(cache.queryRoot, "originals")
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    data class Item(val asset: ExpressionAsset, val sourceType: String? = null)

    @Serializable
    data class Entry(val fetchedAt: Long, val items: List<Item>)

    fun fresh(entry: Entry): Boolean = now() - entry.fetchedAt in 0 until ttlMs

    fun read(endpoint: String, query: String): Entry? = synchronized(diskLock) {
        val file = indexFile(endpoint, query)
        if (!file.isFile) return@synchronized null
        runCatching {
            check(file.length() <= MAX_METADATA_BYTES)
            json.decodeFromString<Entry>(file.readText()).also {
                check(it.items.size <= 20)
                file.setLastModified(now())
            }
        }.getOrElse { file.delete(); null }
    }

    fun write(endpoint: String, query: String, items: List<Item>) = synchronized(diskLock) {
        val data = json.encodeToString(Entry(now(), items.take(20))).toByteArray()
        require(data.size <= MAX_METADATA_BYTES)
        check(indexes.mkdirs() || indexes.isDirectory)
        val target = indexFile(endpoint, query)
        val part = File.createTempFile("query-", ".part", indexes)
        try {
            FileOutputStream(part).use { it.write(data); it.fd.sync() }
            check(part.renameTo(target)) { "cannot replace query index" }
            target.setLastModified(now())
            indexes.listFiles()?.filter { it.extension == "json" && it != target }
                ?.sortedByDescending { it.lastModified() }?.drop((maxQueries - 1).coerceAtLeast(0))?.forEach { it.delete() }
        } finally { part.delete() }
    }

    /** 有界流式复制，不能把远端整个图片加载到内存。失败不替换既有有效原件。 */
    fun writeOriginal(sha256: String, input: InputStream, byteLimit: Long = maxAssetBytes): File? {
        if (!SHA_PATTERN.matches(sha256)) { input.close(); return null }
        synchronized(diskLock) { check(originals.mkdirs() || originals.isDirectory) }
        val part = File.createTempFile("original-", ".part", originals)
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var length = 0L
            input.use { source ->
                FileOutputStream(part).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val count = source.read(buffer)
                        if (count < 0) break
                        length += count
                        if (length > byteLimit || length > maxAssetBytes || length > maxBytes) return null
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (digest.digest().hex() != sha256) return null
            synchronized(diskLock) {
                val target = File(originals, sha256)
                val others = originals.listFiles().orEmpty()
                    .filter { SHA_PATTERN.matches(it.name) && it.name != sha256 }
                    .sortedBy { it.lastModified() }
                var total = others.sumOf { it.length() } + length
                for (old in others) {
                    if (total <= maxBytes) break
                    val size = old.length()
                    if (old.delete()) total -= size
                }
                if (total > maxBytes) return@synchronized null
                if (!part.renameTo(target)) return@synchronized null
                target.setLastModified(now())
                target
            }
        } finally { part.delete() }
    }

    private fun indexFile(endpoint: String, query: String): File = File(indexes,
        MessageDigest.getInstance("SHA-256").digest("$endpoint\n$query".toByteArray()).hex() + ".json")

    companion object {
        const val MAX_METADATA_BYTES = 256L * 1024
        internal val SHA_PATTERN = Regex("[a-f0-9]{64}")
        private val diskLock = Any()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
    }
}
