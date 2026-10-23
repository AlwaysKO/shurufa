package com.yuyan.imemodule.data.completion

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IndexAssetCacheTest {
    @get:Rule val temporary = TemporaryFolder()
    private val bytes = "保留全部词条与原始索引内容".repeat(100).toByteArray()
    private fun sha(data: ByteArray) = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    private fun contents(buffer: ByteBuffer) = ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
    private fun cached(root: File, data: ByteArray) = File(root, "sample-${sha(data)}.t9idx")
    private fun load(root: File, data: ByteArray = bytes, open: () -> InputStream = { data.inputStream() }) =
        IndexAssetCache.map(root, "sample", data.size.toLong(), sha(data), open)

    @Test fun `从真正压缩的ZIP资产流恢复逐字节一致的只读索引`() {
        val zip = temporary.newFile("asset.zip")
        ZipOutputStream(zip.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("sample.t9idx"));out.write(bytes);out.closeEntry()
        }
        ZipFile(zip).use { source ->
            val entry = source.getEntry("sample.t9idx")
            assertEquals(ZipEntry.DEFLATED, entry.method)
            val data = load(temporary.newFolder()) { source.getInputStream(entry) }
            assertArrayEquals(bytes, contents(data))
            assertTrue(data.isReadOnly)
        }
    }

    @Test fun `重复初始化直接复用且不重新打开资产`() {
        val root = temporary.newFolder()
        load(root)
        val second = load(root) { throw AssertionError("重复解压") }
        assertArrayEquals(bytes, contents(second))
    }

    @Test fun `相同长度但内容损坏的缓存重新准备`() {
        val root = temporary.newFolder()
        load(root)
        cached(root, bytes).writeBytes(ByteArray(bytes.size))
        assertArrayEquals(bytes, contents(load(root)))
        assertArrayEquals(bytes, cached(root, bytes).readBytes())
    }

    @Test fun `解压中断清理半成品且下一次可以恢复`() {
        val root = temporary.newFolder()
        assertThrows(IOException::class.java) {
            load(root) { object : InputStream() {
                private var remaining = 20
                override fun read(): Int { if (remaining-- > 0) return 1;throw IOException("模拟存储中断") }
            } }
        }
        assertFalse(cached(root, bytes).exists())
        assertFalse(root.listFiles().orEmpty().any { it.extension == "tmp" })
        assertArrayEquals(bytes, contents(load(root)))
    }

    @Test fun `截断内容或错误摘要不发布缓存`() {
        for (bad in listOf(bytes.copyOf(bytes.size - 1), ByteArray(bytes.size), bytes + byteArrayOf(1))) {
            val root = temporary.newFolder()
            assertThrows(IOException::class.java) { load(root) { bad.inputStream() } }
            assertFalse(cached(root, bytes).exists())
        }
    }

    @Test fun `词库更新成功后才清理旧版且现有映射仍可读`() {
        val root = temporary.newFolder()
        val old = load(root)
        val updated = bytes + "新词".toByteArray()
        assertThrows(IOException::class.java) { load(root, updated) { bytes.inputStream() } }
        assertTrue(cached(root, bytes).exists())
        assertArrayEquals(updated, contents(load(root, updated)))
        assertFalse(cached(root, bytes).exists())
        assertArrayEquals(bytes, contents(old))
    }

    @Test fun `并发初始化只准备一份且互不截断`() {
        val root = temporary.newFolder()
        val opened = AtomicInteger()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val tasks = List(4) { Callable { load(root) { opened.incrementAndGet();ByteArrayInputStream(bytes) } } }
            pool.invokeAll(tasks).forEach { assertArrayEquals(bytes, contents(it.get())) }
            assertEquals(1, opened.get())
        } finally { pool.shutdownNow() }
    }
}
