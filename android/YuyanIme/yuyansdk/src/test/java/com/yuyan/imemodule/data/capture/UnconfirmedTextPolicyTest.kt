package com.yuyan.imemodule.data.capture

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class UnconfirmedTextPolicyTest {
    private fun batch(confidence: Double = .55, vararg messages: JsonObject) = buildJsonObject {
        put("device_id", "device")
        put("conversation", buildJsonObject { put("platform","wechat");put("identity_confidence",confidence);put("display_name","普通名称") })
        put("messages", JsonArray(messages.toList()))
    }
    private fun message(id: String, status: String? = null, type: String = "text", resource: Boolean = false, extra: Map<String,String> = emptyMap()) = buildJsonObject {
        put("id",id);put("message_type",type);put("text","普通正文")
        if (resource) put("asset_sha256",JsonArray(listOf(JsonPrimitive("a".repeat(64)))))
        put("metadata",buildJsonObject { status?.let { put("conversation_identity_status",it) };extra.forEach { (key,value) -> put(key,value) } })
    }
    @Test fun explicitUnconfirmedTextWithoutResourcesIsRemovedWithoutBusinessNameRules() {
        for (metadata in listOf(emptyMap(),mapOf("conversation_identity_status" to "pending"),mapOf("identity_unavailable" to "true"))) {
            assertTrue(shouldDiscardUnconfirmedText(.55,"text",metadata,false))
        }
        assertNull(filterUnconfirmedTextMessages(batch(.55,message("noise"))))
        assertFalse(shouldDiscardUnconfirmedText(.95,"text",emptyMap(),false))
    }
    @Test fun confirmedTextAndAllResourceOrMediaTasksArePreserved() {
        assertFalse(shouldDiscardUnconfirmedText(.9,"text",mapOf("conversation_identity_status" to "confirmed"),false))
        assertFalse(shouldDiscardUnconfirmedText(.55,"text",emptyMap(),true))
        for (metadata in listOf(mapOf("asset_capture_deferred" to "quota"),mapOf("asset_capture_failed" to "true"),mapOf("notification_media_readable" to "false"))) {
            assertFalse(shouldDiscardUnconfirmedText(.55,"text",metadata,false))
        }
        for (type in listOf("image","video","voice","file")) assertFalse(shouldDiscardUnconfirmedText(.55,type,emptyMap(),false))
        assertFalse(shouldDiscardUnconfirmedText(null,"text",emptyMap(),false))
        assertFalse(shouldDiscardUnconfirmedText(Double.NaN,"text",emptyMap(),false))
        assertFalse(shouldDiscardUnconfirmedText(Double.NEGATIVE_INFINITY,"text",emptyMap(),false))
        assertFalse(shouldDiscardUnconfirmedText(null,"text",mapOf("conversation_identity_status" to "unknown","identity_unavailable" to "false"),false))
    }
    @Test fun mixedBatchOnlyLosesExplicitPendingTextAndPreservesEnvelope() {
        val original=batch(.9,message("noise","pending"),message("confirmed","confirmed"),message("asset","pending",resource=true),
            message("future-image","pending",extra=mapOf("asset_capture_deferred" to "quota")),message("image","pending",type="image"))
        val filtered=filterUnconfirmedTextMessages(original)!!
        assertEquals(listOf("confirmed","asset","future-image","image"),filtered["messages"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        assertEquals(original["conversation"],filtered["conversation"]);assertEquals(original["device_id"],filtered["device_id"])
    }
    @Test fun malformedMetadataIsPreservedWhileMissingOrNullMetadataUsesKnownConfidence() {
        for (metadata in listOf(JsonPrimitive("unknown"),JsonArray(emptyList()))) {
            val original=batch(.55,JsonObject(message("uncertain") + ("metadata" to metadata)))
            assertEquals(original,filterUnconfirmedTextMessages(original))
        }
        assertNull(filterUnconfirmedTextMessages(batch(.55,JsonObject(message("missing") - "metadata"))))
        assertNull(filterUnconfirmedTextMessages(batch(.55,JsonObject(message("null") + ("metadata" to JsonNull)))))
    }
}
