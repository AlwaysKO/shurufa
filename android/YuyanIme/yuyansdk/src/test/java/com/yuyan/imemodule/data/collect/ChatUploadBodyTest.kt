package com.yuyan.imemodule.data.collect

import okhttp3.RequestBody.Companion.toRequestBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ChatUploadBodyTest {
    @Test fun `wifi loss or typing interrupts body before next chunk`() {
        var checks=0
        val body=GuardedChatBody(ByteArray(50000).toRequestBody(),allowed={++checks <= 1}, pause={})
        val sink=Buffer()
        assertThrows(IOException::class.java){body.writeTo(sink)}
        assertTrue(sink.size <= 8192)
    }
    @Test fun `body preserves bytes and applies per chunk throttling`() {
        val original=ByteArray(20000){(it%251).toByte()};var sleeps=0
        val body=GuardedChatBody(original.toRequestBody(),allowed={true},pause={sleeps++})
        val sink=Buffer();body.writeTo(sink)
        assertArrayEquals(original,sink.readByteArray())
        assertEquals(original.size.toLong(),body.contentLength())
        assertTrue(sleeps>=3)
    }
}
