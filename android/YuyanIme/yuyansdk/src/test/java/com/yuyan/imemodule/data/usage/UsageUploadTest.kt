package com.yuyan.imemodule.data.usage

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UsageUploadTest {
    private val row=UsageRecord("87af0210-18ef-445a-86f3-a3eafcd14a90","usage","pkg","应用",1000,2000,"pause")
    @Test fun `real request contains device identity and stable usage metadata only`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{\"ok\":true,\"received\":1}"))
            assertTrue(uploadUsageBatch(OkHttpClient(),server.url("/").toString(),"device",listOf(row)))
            val request=server.takeRequest()
            assertEquals("/api/v1/mobile/app-usage/batch",request.path)
            assertEquals("device",request.getHeader("X-Device-Id"))
            assertEquals(row,usageRecordFromJson(JSONObject(request.body.readUtf8()).getJSONArray("records").getJSONObject(0)))
        }
    }
    @Test fun `failure and mismatched acknowledgement do not confirm delivery`() {
        MockWebServer().use { server ->
            listOf(MockResponse().setResponseCode(503),MockResponse().setBody("{\"ok\":true}"),MockResponse().setBody("{\"ok\":true,\"received\":0}")).forEach { response ->
                server.enqueue(response)
                assertFalse(uploadUsageBatch(OkHttpClient(),server.url("/").toString(),"device",listOf(row)))
            }
        }
    }
}
