package com.yuyan.imemodule.data.callrecording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingHttpTransportTest {
    private fun io(block:()->Unit){val executor=Executors.newSingleThreadExecutor();try{executor.submit(block).get()}finally{executor.shutdownNow()}}
    @Test fun `请求凭据按设备发送并用原始音频上传缺失回执才返回null`()=io {
        val server=MockWebServer();server.start()
        try {
            val context=ApplicationProvider.getApplicationContext<Context>()
            val box=CallRecordingOutbox(File(context.noBackupFilesDir,"calls-"+UUID.randomUUID()))
            val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A fixture")
            val task=box.enqueue(id,UUID.randomUUID().toString(),CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000))
            // 仅测试替身将已校验 HTTPS 请求转向进程内 HTTP 服务，不改变生产客户端 TLS 策略。
            val http=OkHttpClient.Builder().followRedirects(false).build()
            val factory=Call.Factory{req->http.newCall(req.newBuilder().url(server.url(req.url.encodedPath+(req.url.encodedQuery?.let{"?$it"}?:""))).build())}
            val transport=CallRecordingHttpTransport({"a".repeat(64)},{true},factory)
            server.enqueue(MockResponse().setResponseCode(404));assertNull(transport.receipt(task))
            val query=server.takeRequest();assertEquals("a".repeat(64),query.getHeader("X-Dictionary-Token"));assertEquals(task.deviceId,query.getHeader("X-Device-Id"))
            server.enqueue(MockResponse().setBody("""{"stored":true,"record_id":"$id","device_id":"${task.deviceId}","byte_size":${file.length()},"sha256":"${task.metadata.sha256}","stored_at":"2026-09-30T00:00:00Z"}"""))
            assertTrue(transport.upload(task,file){true}.stored)
            val upload=server.takeRequest();assertEquals("PUT",upload.method);assertEquals(file.readText(),upload.body.readUtf8());assertNotNull(upload.getHeader("X-Call-Metadata"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"record_not_found"}"""));assertNull(transport.existing(task))
            val lookup=server.takeRequest();assertEquals("GET",lookup.method);assertEquals(0L,lookup.bodySize)
            assertEquals("/api/v1/mobile/call-recordings/by-content",lookup.requestUrl!!.encodedPath)
            assertEquals(task.metadata.sha256,lookup.requestUrl!!.queryParameter("sha256"))
            assertEquals(task.metadata.byte_size.toString(),lookup.requestUrl!!.queryParameter("byte_size"))
            server.enqueue(MockResponse().setResponseCode(404).setBody("Cannot GET /by-content"))
            assertThrows(java.io.IOException::class.java){transport.existing(task)}
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(410));assertThrows(java.io.IOException::class.java){transport.existing(task)}
            server.takeRequest()
            server.enqueue(MockResponse().setResponseCode(503));assertThrows(java.io.IOException::class.java){transport.receipt(task)}
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location","https://other.test"));assertThrows(java.io.IOException::class.java){transport.receipt(task)}
        } finally {server.shutdown()}
    }
}
