package com.yuyan.imemodule.data.calllog

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class PhoneCallLogHttpTest {
    @Test fun `鉴权绑定当前目标且上传只携带记录不携带音频`() {
        val server=MockWebServer();server.start()
        try {
            val id=UUID.randomUUID().toString();val requestId=UUID.randomUUID().toString()
            val client=OkHttpClient.Builder().followRedirects(false).build()
            val factory=Call.Factory{client.newCall(it.newBuilder().url(server.url(it.url.encodedPath)).build())}
            val http=PhoneCallLogHttp("https://example.test",id,{"a".repeat(64)},{true},factory)
            server.enqueue(MockResponse().setBody("{\"request_id\":\"$requestId\"}"))
            assertEquals(requestId,http.requestId())
            val get=server.takeRequest();assertEquals(id,get.getHeader("X-Device-Id"));assertEquals("a".repeat(64),get.getHeader("X-Dictionary-Token"))
            server.enqueue(MockResponse().setBody("{\"stored\":true,\"count\":0}"))
            http.send(PhoneCallLogBatch(requestId,"synced",emptyList(),false))
            val post=server.takeRequest();assertEquals("POST",post.method);assertTrue(post.body.readUtf8().contains("\"records\":[]"))
        }finally{server.shutdown()}
    }
    @Test fun `状态码成功不能代替完整保存回执`() {
        val server=MockWebServer();server.start()
        try {
            val client=OkHttpClient.Builder().followRedirects(false).build()
            val factory=Call.Factory{client.newCall(it.newBuilder().url(server.url(it.url.encodedPath)).build())}
            val http=PhoneCallLogHttp("https://example.test",UUID.randomUUID().toString(),{"a".repeat(64)},{true},factory)
            for(body in listOf("{}","{\"stored\":false,\"count\":0}","{\"stored\":true,\"count\":1}")){
                server.enqueue(MockResponse().setBody(body))
                try{http.send(PhoneCallLogBatch(null,"synced",emptyList(),false));fail("不能确认保存: $body")}catch(_:Exception){}
            }
        }finally{server.shutdown()}
    }
    @Test fun `响应等待期间撤权取消传输`() {
        val server=MockWebServer();server.start()
        val executor=Executors.newSingleThreadExecutor()
        try {
            val allowed=AtomicBoolean(true)
            val client=OkHttpClient.Builder().followRedirects(false).build()
            val factory=Call.Factory{client.newCall(it.newBuilder().url(server.url(it.url.encodedPath)).build())}
            val http=PhoneCallLogHttp("https://example.test",UUID.randomUUID().toString(),{"a".repeat(64)},{allowed.get()},factory)
            server.enqueue(MockResponse().setBody("{\"request_id\":null}").setHeadersDelay(5,TimeUnit.SECONDS))
            val task=executor.submit<Boolean>{try{http.requestId();false}catch(_:IOException){true}}
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS));allowed.set(false)
            assertTrue(task.get(2,TimeUnit.SECONDS))
        }finally{executor.shutdownNow();server.shutdown()}
    }
    @Test fun `服务器重定向不会转发设备令牌`() {
        val server=MockWebServer();server.start()
        try {
            val client=OkHttpClient.Builder().followRedirects(false).build()
            val factory=Call.Factory{client.newCall(it.newBuilder().url(server.url(it.url.encodedPath)).build())}
            val http=PhoneCallLogHttp("https://example.test",UUID.randomUUID().toString(),{"a".repeat(64)},{true},factory)
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location",server.url("/other")))
            try{http.requestId();fail("应拒绝重定向")}catch(_:IOException){}
            assertEquals(1,server.requestCount)
        }finally{server.shutdown()}
    }
    @Test fun `撤权后不读取凭据或发送请求`() {
        var credentialRead=false
        val http=PhoneCallLogHttp("https://example.test",UUID.randomUUID().toString(),{credentialRead=true;"a".repeat(64)},{false})
        try{http.requestId();fail("应停止") }catch(_:IOException){}
        assertFalse(credentialRead)
    }
}
