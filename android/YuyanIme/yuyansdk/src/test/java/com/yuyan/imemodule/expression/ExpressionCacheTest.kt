package com.yuyan.imemodule.expression

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ExpressionCacheTest {
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        root = java.nio.file.Files.createTempDirectory("expression-cache-").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `缓存命中必须通过 SHA 校验`() {
        val cache = ExpressionCache(root)
        val bytes = "valid-image".toByteArray()
        val sha = sha256(bytes)
        val file = requireNotNull(
            cache.writeVerified("v1", "templates/exact.webp", sha, bytes.inputStream()),
        )

        assertEquals(file, cache.validFile("v1", "templates/exact.webp", sha))

        file.writeText("corrupt-image")

        assertNull(cache.validFile("v1", "templates/exact.webp", sha))
    }

    @Test
    fun `同一素材并发写入使用相互独立的临时文件`() {
        val cache = ExpressionCache(root)
        val bytes = ByteArray(128 * 1024) { (it % 251).toByte() }
        val sha = sha256(bytes)
        val bothOpened = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)

        val writes = List(2) {
            executor.submit<java.io.File?> {
                cache.writeVerified(
                    "v1",
                    "templates/shared.webp",
                    sha,
                    object : ByteArrayInputStream(bytes) {
                        private var firstRead = true

                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (firstRead) {
                                firstRead = false
                                bothOpened.countDown()
                                check(bothOpened.await(2, TimeUnit.SECONDS))
                            }
                            return super.read(buffer, offset, length)
                        }
                    },
                )
            }
        }

        val results = writes.map { it.get(3, TimeUnit.SECONDS) }
        executor.shutdownNow()

        assertTrue(results.all { it?.isFile == true })
        assertEquals(sha, sha256(requireNotNull(results.first()).readBytes()))
    }

    @Test
    fun `API23写读替换均不依赖java nio file`() {
        val cache = ExpressionCache(root)
        val old = "old-image".toByteArray()
        val fresh = "fresh-image".toByteArray()
        val target = requireNotNull(cache.writeVerified("v1", "templates/replace.webp", sha256(old), old.inputStream()))

        val replaced = cache.writeVerified("v1", "templates/replace.webp", sha256(fresh), fresh.inputStream())

        assertEquals(target, replaced)
        assertEquals("fresh-image", target.readText())
        assertTrue(requireNotNull(requireNotNull(target.parentFile).listFiles()).none {
            it.name.endsWith(".part") || it.name.endsWith(".bak")
        })
        val source = java.io.File("src/main/java/com/yuyan/imemodule/expression/ExpressionCache.kt").readText()
        assertTrue("不得直接引用 API26 java.nio.file", "java.nio.file" !in source)
        assertTrue("不得调用 File.toPath", ".toPath(" !in source)
    }

    @Test
    fun `校验失败保留旧缓存并清理临时文件`() {
        val cache = ExpressionCache(root)
        val old = "old-valid".toByteArray()
        val target = requireNotNull(cache.writeVerified("v1", "templates/safe.webp", sha256(old), old.inputStream()))

        assertNull(cache.writeVerified("v1", "templates/safe.webp", sha256("wanted".toByteArray()), "broken".byteInputStream()))

        assertEquals("old-valid", target.readText())
        assertTrue(requireNotNull(requireNotNull(target.parentFile).listFiles()).none {
            it.name.endsWith(".part") || it.name.endsWith(".bak")
        })
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString("") { "%02x".format(it) }
}
