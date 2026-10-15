package com.yuyan.imemodule.data.usage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.HumanInteraction
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28])
class UsageEvidenceUploadTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val evidence=UsageEvidence("d67fd09a-78f6-45fa-8740-b359be084d29",screenOff=UsageEvidenceEvent(1_792_000_000_000,"usage_screen_off"))
    @Before fun setup() { app.getSharedPreferences("usage_server_capabilities_v1",0).edit().clear().commit() }
    @Test fun `snapshot only has identity raw original time and supplemental interaction`() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"ok":true,"received":0,"usage_evidence_ack":{"version":"${evidence.version}","disposition":"saved"}}"""))
            val receipt=uploadUsageEvidenceBatch(OkHttpClient(),server.url("/").toString(),"device",emptyList(),evidence,HumanInteraction(1_792_000_000_001,"ime_input"))
            assertTrue(receipt.recordsAccepted);assertEquals("saved",receipt.evidenceDisposition)
            val j=JSONObject(server.takeRequest().body.readUtf8())
            assertEquals(0,j.getJSONArray("records").length())
            assertEquals("device",j.getJSONObject("usage_evidence").getString("device_id"))
            assertEquals(evidence.screenOff!!.atMs,j.getJSONObject("usage_evidence").getJSONObject("screen_off").getLong("at_ms"))
            assertEquals("ime_input",j.getString("last_interaction_source"))
            assertEquals(java.time.Instant.ofEpochMilli(1_792_000_000_001).toString(),j.getString("last_interaction_at"))
        }
    }
    @Test fun `old mismatched failed and discarded receipts remain distinct`() {
        MockWebServer().use { server ->
            val bodies=listOf(
                """{"ok":true,"received":0}""" to null,
                """{"ok":true,"received":0,"usage_evidence_ack":{"version":"old","disposition":"saved"}}""" to null,
                """{"ok":true,"received":0,"usage_evidence_ack":{"version":"${evidence.version}","disposition":"nonsense"}}""" to null,
                """{"ok":true,"discarded":true,"received":0,"usage_evidence_ack":{"version":"${evidence.version}","disposition":"discarded"}}""" to "discarded"
            )
            for((body,disposition) in bodies) {
                server.enqueue(MockResponse().setBody(body))
                val receipt=uploadUsageEvidenceBatch(OkHttpClient(),server.url("/").toString(),"device",emptyList(),evidence)
                assertEquals(disposition,receipt.evidenceDisposition)
                assertEquals(disposition!="discarded",receipt.recordsAccepted)
            }
            server.enqueue(MockResponse().setResponseCode(503).setBody("""{"ok":true,"received":0}"""))
            assertEquals(UsageBatchReceipt(),uploadUsageEvidenceBatch(OkHttpClient(),server.url("/").toString(),"device",emptyList(),evidence))
        }
    }
    @Test fun `empty requests and revoked gates never reach server`() {
        MockWebServer().use { server ->
            val http=OkHttpClient(); val url=server.url("/").toString()
            assertEquals(UsageBatchReceipt(),uploadUsageEvidenceBatch(http,url,"device",emptyList(),null))
            assertEquals(UsageBatchReceipt(),uploadUsageEvidenceBatch(http,url,"device",emptyList(),evidence,gate=UsageUploadGate { false }))
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
        }
    }
    @Test fun `negative capabilities are target scoped durable and bounded without polling`() {
        val cache=UsageEvidenceCapabilities(app)
        cache.record("https://one.example/","""{"capabilities":{}}""",1000)
        assertEquals(false,UsageEvidenceCapabilities(app).supported("https://one.example",1001))
        assertNull(cache.supported("https://two.example",1001))
        assertEquals(7_199_999L,cache.retryDelay("https://one.example/",1001))
        assertNull(cache.supported("https://one.example",7_201_000))
        cache.record("https://two.example","""{"capabilities":{"usage_evidence":1}}""",1000)
        assertEquals(true,cache.supported("https://two.example/",1001))
        assertEquals(false,cache.supported("https://one.example",1001))
    }
    @Test fun `capability request uses gate and rejects malformed success without enabling feature`() {
        MockWebServer().use { server ->
            val cache=UsageEvidenceCapabilities(app);val target=server.url("/").toString()
            assertFalse(cache.fetch(OkHttpClient(),target,"device",UsageUploadGate { false }))
            assertNull(server.takeRequest(100,java.util.concurrent.TimeUnit.MILLISECONDS))
            server.enqueue(MockResponse().setBody("not-json"))
            assertFalse(cache.fetch(OkHttpClient(),target,"device",UsageUploadGate { true }))
            assertEquals("/api/v1/mobile/config",server.takeRequest().path)
            assertNull(cache.supported(target))
        }
    }
    @Test fun `transient capability failure must not delay evidence for two hours`() {
        MockWebServer().use { server ->
            val cache=UsageEvidenceCapabilities(app);val target=server.url("/").toString()
            server.enqueue(MockResponse().setResponseCode(503))
            assertFalse(cache.fetch(OkHttpClient(),target,"device",UsageUploadGate { true }))
            assertNull("temporary failure is not proof of an old server",cache.supported(target))
            assertEquals(0L,cache.retryDelay(target))
        }
    }
    @Test fun `malformed capabilities are unknown rather than an old server`() {
        val cache=UsageEvidenceCapabilities(app)
        cache.record("https://one.example","not-json")
        assertNull(cache.supported("https://one.example"))
    }
    @Test fun `error objects and malformed capability schemas remain unknown`() {
        val bodies=listOf(
            "{}", """{"error":"temporary"}""", """{"capabilities":null}""",
            """{"capabilities":[]}""", """{"capabilities":{"usage_evidence":"1"}}""",
            """{"capabilities":{"usage_evidence":null}}""", """{"capabilities":{"usage_evidence":true}}""",
            """{"collector_base_url":null}""", """{"collector_base_url":"not-a-url"}""",
            """{"collector_base_url":"https://collector.example","capabilities":{"usage_evidence":"1"}}"""
        )
        val cache=UsageEvidenceCapabilities(app)
        for((index,body) in bodies.withIndex()) {
            val target="https://schema-$index.example"
            cache.record(target,body,1000)
            assertNull(body,cache.supported(target,1001))
            assertEquals(body,0L,cache.retryDelay(target,1001))
        }
    }
    @Test fun `real legacy configuration identifies old receiver without copying advertised target capability`() {
        val cache=UsageEvidenceCapabilities(app)
        cache.record("https://old.example","""{"collector_base_url":"https://other.example"}""",1000)
        assertEquals(false,cache.supported("https://old.example",1001))
        assertNull(cache.supported("https://other.example",1001))
        cache.record("https://new.example","""{"collector_base_url":"https://other.example","capabilities":{"usage_evidence":1}}""",1000)
        assertEquals(true,cache.supported("https://new.example",1001))
    }
}
