package com.yuyan.imemodule.data.collect

import java.io.IOException
import java.util.concurrent.Executor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class GameWorkGateTest {
    @Test fun gamePausesImmediatelyAndExitWaitsForStableIdle() {
        var now = 10_000L
        val gate = GameWorkGate({ now }, Executor { it.run() })
        assertTrue(gate.isAllowed())
        gate.setGaming(true)
        assertFalse(gate.isAllowed())
        now += 60_000
        assertFalse(gate.isAllowed())
        gate.setGaming(false)
        now += 2_999
        assertFalse(gate.isAllowed())
        now++
        assertTrue(gate.isAllowed())
        gate.setGaming(false)
        assertTrue(gate.isAllowed())
    }

    @Test fun noRequestIsSentDuringGameAndSameRequestWorksAfterwards() {
        var now = 10_000L
        val gate = GameWorkGate({ now }, Executor { it.run() })
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("ok"))
            val client = OkHttpClient.Builder().addInterceptor(gate.interceptor).build()
            val request = Request.Builder().url(server.url("/batch")).build()
            gate.setGaming(true)
            try { client.newCall(request).execute().close(); fail("游戏中不得请求服务器") }
            catch (_: IOException) { }
            assertEquals(0, server.requestCount)
            gate.setGaming(false)
            now += 3_000
            client.newCall(request).execute().use { assertEquals("ok", it.body!!.string()) }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun enteringGameCancelsCallEvenAfterResponseHeadersArrive() {
        val work = mutableListOf<Runnable>()
        val gate = GameWorkGate({ 10_000L }, Executor { work.add(it) })
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("queued data"))
            val call = OkHttpClient.Builder().addInterceptor(gate.interceptor).build()
                .newCall(Request.Builder().url(server.url("/download")).build())
            call.execute().use {
                gate.setGaming(true)
                assertFalse("取消不得在前台事件线程关闭网络", call.isCanceled())
                work.toList().forEach(Runnable::run)
                assertTrue(call.isCanceled())
                try { it.body!!.string(); fail("暂停后不得继续读取正文") } catch (_: IOException) { }
            }
        }
    }

    @Test fun finishedResponseIsNoLongerAnActiveCall() {
        val gate = GameWorkGate({ 10_000L }, Executor { it.run() })
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("done"))
            val call = OkHttpClient.Builder().addInterceptor(gate.interceptor).build()
                .newCall(Request.Builder().url(server.url("/done")).build())
            call.execute().use { it.body!!.string() }
            gate.setGaming(true)
            assertFalse(call.isCanceled())
        }
    }
}
