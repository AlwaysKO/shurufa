package com.yuyan.imemodule.data.capture.adapter
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class ChatDiagnosticsReporterTest {
    @Test fun legacy404NeverThrowsOrCreatesUnboundedRetriesAndDisabledSkipsNetwork() {
        val server = MockWebServer().apply { start() }
        try {
            val buffer = ChatDiagnosticsBuffer()
            buffer.record(CaptureDiagnosticSnapshot("00000000-0000-4000-8000-000000000001", "wechat",1,"1",0,"screenshot","failed",3,1000))
            val client = OkHttpClient()
            val reporter = ChatDiagnosticsReporter(buffer, client::newCall)
            val target = server.url("/").toString().trimEnd('/')
            assertEquals(0, reporter.flush(target, { false }, { 1000 }))
            assertEquals(0, server.requestCount)
            server.enqueue(MockResponse().setResponseCode(404))
            assertEquals(1, reporter.flush(target, { true }, { 1000 }))
            assertEquals(1, server.requestCount)
            assertEquals(0, reporter.flush(target, { true }, { 2000 }))
            assertEquals(1, buffer.latest().size)
            val request = server.takeRequest()
            assertEquals("/api/v1/mobile/chat/diagnostics", request.path)
            assertFalse(request.body.readUtf8().contains("正文"))
        } finally { server.shutdown() }
    }
    /** 纯JVM本地HTTP：验证Reporter慢滴流总截止，不表示Android绑定socket端到端。 */
    @Test fun slowTrickleCannotExtendFiveSecondTotalDeadline() {
        val server=MockWebServer().apply { start() }
        val buffer=ChatDiagnosticsBuffer()
        buffer.record(CaptureDiagnosticSnapshot("00000000-0000-4000-8000-000000000001","wechat",1,"1",0,"page","matched",null,1000))
        val client=OkHttpClient.Builder().readTimeout(30,java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(5,java.util.concurrent.TimeUnit.SECONDS).build()
        val active=java.util.concurrent.atomic.AtomicReference<okhttp3.Call>()
        val reporter=ChatDiagnosticsReporter(buffer,prepare={request -> client.newCall(request).also(active::set) })
        val received=java.util.concurrent.atomic.AtomicBoolean()
        server.dispatcher=object: okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                received.set(true)
                return MockResponse().setBody("{\"ok\":true}" + " ".repeat(100))
                    .throttleBody(1,2,java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val started=System.nanoTime()
        val thread=Thread { reporter.flush(server.url("/").toString().trimEnd('/'),{true},
            { java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) }) }
        try {
            thread.start(); thread.join(8000)
            assertFalse("逐字滴流仍须在约5秒返回",thread.isAlive)
            val elapsed=java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-started)
            assertTrue("应由5秒总deadline而非30秒readTimeout终止: $elapsed",elapsed in 4000..7500)
            assertTrue("HTTP请求必须真正到达本地server",received.get())
            assertEquals(1,buffer.latest().size)
            assertNotNull("慢响应不得清除未确认诊断",buffer.nextDue(java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime()) + 60_000))
        } finally { active.get()?.cancel(); thread.join(3000); server.shutdown() }
    }

}
