package com.yuyan.imemodule.data.capture.adapter
import org.junit.Assert.*
import org.junit.Test

class ChatDiagnosticsBufferTest {
    private fun snapshot(platform: String = "wechat", stage: String = "page", status: String = "matched", time: Long = 1000) =
        CaptureDiagnosticSnapshot("00000000-0000-4000-8000-000000000001", platform, 1, "1", 2, stage, status, null, time)
    @Test fun hasOnlyEightLatestSlotsAndRateLimitsEveryStageDespiteStatusChanges() {
        val buffer = ChatDiagnosticsBuffer()
        assertTrue(buffer.record(snapshot()))
        assertEquals("matched", buffer.nextDue(1000)!!.status)
        assertTrue(buffer.record(snapshot(status = "rejected", time = 1001)))
        assertNull(buffer.nextDue(60999))
        assertEquals("rejected", buffer.nextDue(61000)!!.status)
        for (platform in listOf("wechat", "douyin")) for ((stage, status) in listOf("page" to "matched", "screenshot" to "ready", "persist" to "inserted", "upload" to "waiting"))
            buffer.record(snapshot(platform, stage, status, 2000))
        assertEquals(8, buffer.latest().size)
        assertFalse(buffer.record(snapshot("qq")))
        assertFalse(buffer.record(snapshot(stage = "page", status = "acknowledged")))
    }
    @Test fun failureIsFiniteAndDisabledNeverFlushes() {
        val buffer = ChatDiagnosticsBuffer()
        val s = snapshot()
        buffer.record(s)
        assertNull(buffer.nextDue(1000, enabled = false))
        val pending = buffer.nextDue(1000)!!
        buffer.complete(pending, false)
        assertNull(buffer.nextDue(2000))
        assertNotNull(buffer.nextDue(61000))
        assertEquals(1, buffer.latest().size)
        assertFalse(buffer.record(snapshot(time = 500)))
        assertFalse(buffer.record(s.copy(appVersionName = "x\n")))
        val json = s.toJson()
        assertEquals(setOf("device_id","platform","app_version_code","app_version_name","config_revision","stage","status","error_code","observed_at"), kotlinx.serialization.json.Json.parseToJsonElement(json).let { it as kotlinx.serialization.json.JsonObject }.keys)
        assertFalse(json.contains("displayName"))
    }
}
