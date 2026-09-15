package com.yuyan.imemodule.data.completion

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class PublicPhraseIndexTest {
    private fun fixture(): ByteBuffer {
        val words = listOf("你好", "欢迎").map { it.toByteArray(Charsets.UTF_8) }
        return ByteBuffer.allocate(36).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("T9WORD1\u0000".toByteArray(Charsets.US_ASCII));putInt(2)
            putInt(0);putInt(6);putInt(12);words.forEach { put(it) };rewind()
        }
    }
    @Test fun `精确匹配不接受前缀后缀或近似词`() {
        val index=PublicPhraseIndex(fixture())
        assertTrue(index.contains("你好"));assertTrue(index.contains("欢迎"))
        for (text in listOf("你", "你好啊", "你赢了", "", "欢迎😀")) assertFalse(index.contains(text))
    }
    @Test fun `不改变共享缓冲区游标`() {
        val data=fixture();val index=PublicPhraseIndex(data)
        repeat(3) { assertTrue(index.contains("你好"));assertEquals(0,data.position()) }
    }
    @Test fun `破损偏移拒绝加载`() {
        val bad=fixture().apply { putInt(20,Int.MAX_VALUE) }
        assertThrows(IllegalArgumentException::class.java) { PublicPhraseIndex(bad) }
    }
}
