package com.yuyan.imemodule.expression

import com.yuyan.imemodule.expression.model.ExpressionAsset
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ExpressionQueryCacheTest {
    private lateinit var root: File
    @Before fun before() { root = java.nio.file.Files.createTempDirectory("query-cache-store").toFile() }
    @After fun after() { root.deleteRecursively() }
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun item() = ExpressionQueryCache.Item(ExpressionAsset("one", "prebuilt", "gif", "v1", "one.gif",
        sha256 = sha("one".toByteArray()), width = 240, height = 240), "ai-original")

    @Test fun `索引重启保留来源过期仍读有效条目但需要刷新`() {
        val cache = ExpressionCache(root)
        val first = ExpressionQueryCache(cache, ttlMs = 100, now = { 10 })
        first.write("https://one", "你们", listOf(item()))
        val second = ExpressionQueryCache(cache, ttlMs = 100, now = { 200 })
        val entry = requireNotNull(second.read("https://one", "你们"))
        assertEquals("ai-original", entry.items.single().sourceType)
        assertFalse(second.fresh(entry))
        assertNull(second.read("https://other", "你们"))
        assertTrue(File(cache.queryRoot, "indexes").listFiles()!!.all { it.name.matches(Regex("[a-f0-9]{64}\\.json")) })
    }

    @Test fun `索引容量使用最近访问淘汰且不含明文路径`() {
        var time = 1L
        val cache = ExpressionQueryCache(ExpressionCache(root), maxQueries = 2, now = { time++ })
        cache.write("api", "../one", listOf(item())); cache.write("api", "two", listOf(item()))
        assertNotNull(cache.read("api", "../one"))
        cache.write("api", "three", listOf(item()))
        assertNull(cache.read("api", "two"))
        assertNotNull(cache.read("api", "../one"))
        assertNotNull(cache.read("api", "three"))
    }

    @Test fun `原件流式单件上限和SHA拒绝不会留下临时文件`() {
        val storage = ExpressionCache(root)
        val cache = ExpressionQueryCache(storage, maxAssetBytes = 4)
        val huge = "too-large".toByteArray()
        assertNull(cache.writeOriginal(sha(huge), huge.inputStream()))
        assertNull(cache.writeOriginal("a".repeat(64), "bad".byteInputStream()))
        assertTrue(File(storage.queryRoot, "originals").listFiles().orEmpty().isEmpty())
    }

    @Test fun `预取容量淘汰只操作专用目录既有发送文件保留`() {
        val storage = ExpressionCache(root)
        val legacy = "legacy".toByteArray()
        val old = requireNotNull(storage.writeVerified("v1", "old.gif", sha(legacy), legacy.inputStream()))
        var time = 1L
        val cache = ExpressionQueryCache(storage, maxBytes = 6, maxAssetBytes = 4, now = { time++ })
        val a = "aaa".toByteArray(); val b = "bbb".toByteArray(); val c = "ccc".toByteArray()
        val first = requireNotNull(cache.writeOriginal(sha(a), a.inputStream()))
        cache.writeOriginal(sha(b), b.inputStream()); cache.writeOriginal(sha(c), c.inputStream())
        assertFalse(first.exists())
        assertTrue(old.exists())
        assertEquals(6, File(storage.queryRoot, "originals").listFiles()!!.sumOf { it.length() })
        assertNotNull(storage.validFile("v2", "new.gif", sha(c)))
    }

    @Test fun `预取原件损坏后不能提供本地URI`() {
        val storage = ExpressionCache(root); val bytes = "original".toByteArray()
        val file = requireNotNull(ExpressionQueryCache(storage).writeOriginal(sha(bytes), bytes.inputStream()))
        assertNotNull(storage.validFile("v2", "new.gif", sha(bytes)))
        file.writeText("damaged")
        assertNull(storage.validFile("v2", "new.gif", sha(bytes)))
    }
    @Test fun `远端版本不允许点目录绕过缓存根目录`() {
        val cache = ExpressionCache(root)
        for (version in listOf(".", "..")) {
            assertTrue("拒绝版本 $version", runCatching { cache.file(version, "outside.gif") }.isFailure)
        }
    }

    @Test fun `时钟小幅回拨时容量驱逐仍保留刚写入的词`() {
        var time = 100L
        val cache = ExpressionQueryCache(ExpressionCache(root), maxQueries = 2, now = { time })
        cache.write("api", "one", listOf(item())); cache.write("api", "two", listOf(item()))
        time = 99L // 校时回拨，刚写入条目的文件时间反而略小。
        cache.write("api", "three", listOf(item()))
        assertNotNull("刚取得的词不能立即被容量驱逐", cache.read("api", "three"))
        assertEquals(2, File(root, "expression-query/indexes").listFiles()!!.size)
    }

}
