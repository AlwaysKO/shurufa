package com.yuyan.imemodule.expression

import java.io.IOException
import kotlinx.coroutines.CancellationException
import java.io.FileNotFoundException
import java.security.MessageDigest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ExpressionAssetResolverTest {
    @get:Rule val folder = TemporaryFolder()
    private val bytes = "verified image".toByteArray()
    private val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test fun `预览解析命中查询原件时不创建额外交付副本`() = runBlocking {
        val cache = ExpressionCache(folder.root)
        val original = java.io.File(cache.queryRoot, "originals/$sha").apply {
            parentFile!!.mkdirs(); writeBytes(bytes)
        }
        val resolver = ExpressionAssetResolver(cache, { error("不应重新读内置") }) { _, _, _, _ -> error("不应重新下载") }
        val resolved = resolver.resolve("v1", "remote/example.gif", sha, null)!!
        assertEquals(original, resolved)
        assertFalse(cache.file("v1", "remote/example.gif").exists())
        assertArrayEquals(original.readBytes(), resolved.readBytes())
    }

    @Test fun `预览解析下载原件后保持有容量限制的缓存路径`() = runBlocking {
        val cache = ExpressionCache(folder.root)
        val original = java.io.File(cache.queryRoot, "originals/$sha")
        var calls = 0
        val resolver = ExpressionAssetResolver(cache, { throw FileNotFoundException() }) { _, _, _, _ ->
            calls++
            original.parentFile!!.mkdirs(); original.writeBytes(bytes); original
        }
        val resolved = resolver.resolve("v1", "remote/example.gif", sha, null)!!
        assertEquals(original, resolved)
        assertFalse(cache.file("v1", "remote/example.gif").exists())
        assertArrayEquals(bytes, resolved.readBytes())
        assertEquals(resolved, resolver.resolve("v1", "remote/example.gif", sha, null))
        assertEquals(1, calls)
    }

    @Test fun `缺URL时使用标准服务端路径且发送复用预览缓存`() = runBlocking {
        val cache = ExpressionCache(folder.root)
        var calls = 0
        val resolver = ExpressionAssetResolver(cache, { throw FileNotFoundException() }) { version, path, url, hash ->
            calls++
            assertEquals("/uploads/expression/emoji/combo.webp", url)
            cache.writeVerified(version, path, hash, bytes.inputStream())
        }
        val preview = resolver.resolve("v1", "emoji/combo.webp", sha, null)
        assertNotNull(preview)
        assertEquals(preview, resolver.resolve("v1", "emoji/combo.webp", sha, null))
        assertEquals(1, calls)
    }

    @Test fun `内置素材校验后落缓存且不联网`() = runBlocking {
        val resolver = ExpressionAssetResolver(ExpressionCache(folder.root), { bytes.inputStream() }) { _, _, _, _ ->
            error("不应联网")
        }
        assertArrayEquals(bytes, resolver.resolve("v1", "builtin.webp", sha, null)!!.readBytes())
    }

    @Test fun `显式URL优先且坏SHA下载拒绝`() = runBlocking {
        val cache = ExpressionCache(folder.root)
        val resolver = ExpressionAssetResolver(cache, { throw FileNotFoundException() }) { v, p, url, hash ->
            assertEquals("/explicit.webp", url)
            cache.writeVerified(v, p, hash, "bad".byteInputStream())
        }
        assertNull(resolver.resolve("v1", "combo.webp", sha, "/explicit.webp"))
    }

    @Test fun `已有合法缓存离线缺URL仍可读取`() = runBlocking {
        val cache = ExpressionCache(folder.root)
        val file = cache.writeVerified("v1", "combo.webp", sha, bytes.inputStream())
        val resolver = ExpressionAssetResolver(cache, { error("不应读内置") }) { _, _, _, _ -> error("离线") }
        assertEquals(file, resolver.resolve("v1", "combo.webp", sha, null))
    }
    @Test fun `普通解析异常返回失败而不是逃逸调用协程`() = runBlocking {
        val resolver = ExpressionAssetResolver(ExpressionCache(folder.root), { throw FileNotFoundException() }) { _, _, _, _ ->
            throw IOException("缓存读取或下载 I/O 失败")
        }
        assertNull(resolver.resolve("v1", "combo.webp", sha, null))
    }

    @Test fun `缓存路径异常返回失败且不继续访问素材`() = runBlocking {
        var accessedAsset = false
        val resolver = ExpressionAssetResolver(ExpressionCache(folder.root), {
            accessedAsset = true
            bytes.inputStream()
        }) { _, _, _, _ ->
            accessedAsset = true
            null
        }
        assertNull(resolver.resolve("v1", "../escape.webp", sha, null))
        assertFalse(accessedAsset)
    }

    @Test fun `内置读取取消必须传播且不能下载或交付失败`() = runBlocking {
        var delivered = false
        var downloaded = false
        val cancelled = CancellationException("cancelled")
        val resolver = ExpressionAssetResolver(ExpressionCache(folder.root), { throw cancelled }) { _, _, _, _ ->
            downloaded = true
            null
        }
        try {
            resolver.resolve("v1", "combo.webp", sha, null)
            delivered = true
            fail("取消必须传播")
        } catch (error: CancellationException) {
            assertEquals(cancelled.message, error.message)
        }
        assertFalse(delivered)
        assertFalse(downloaded)
    }

    @Test fun `下载取消必须传播且不能交付失败`() = runBlocking {
        var delivered = false
        val cancelled = CancellationException("cancelled")
        val resolver = ExpressionAssetResolver(ExpressionCache(folder.root), { throw FileNotFoundException() }) { _, _, _, _ ->
            throw cancelled
        }
        try {
            resolver.resolve("v1", "combo.webp", sha, null)
            delivered = true
            fail("取消必须传播")
        } catch (error: CancellationException) {
            assertEquals(cancelled.message, error.message)
        }
        assertFalse(delivered)
    }

}
