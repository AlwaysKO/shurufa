package com.yuyan.imemodule.data.usage

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class UsageUploadGateTest {
    @Test fun `closing gate cancels even prepared calls that have not executed yet`() {
        var allowed=true
        val gate=UsageUploadGate { allowed }
        MockWebServer().use { server ->
            val call=gate.prepare(OkHttpClient(),Request.Builder().url(server.url("/")).build())!!
            allowed=false; gate.cancel()
            assertTrue(call.isCanceled())
            try { call.execute(); fail("call must be cancelled before sending") } catch (_: java.io.IOException) { }
            assertEquals(0,server.requestCount)
            assertNull(gate.prepare(OkHttpClient(),Request.Builder().url(server.url("/")).build()))
            gate.finish(call)
        }
    }
}
