package com.yuyan.imemodule.data.usage

import com.yuyan.imemodule.data.collect.HumanInteraction
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

internal data class UsageBatchReceipt(val recordsAccepted: Boolean=false, val evidenceDisposition: String?=null)

/** Each receipt confirms only its own payload; no ACK means the durable snapshot remains pending. */
internal fun uploadUsageEvidenceBatch(
    http: OkHttpClient, target: String, deviceId: String, batch: List<UsageRecord>, evidence: UsageEvidence?,
    interaction: HumanInteraction?=null, gate: UsageUploadGate?=null,
): UsageBatchReceipt {
    if(batch.isEmpty() && evidence==null) return UsageBatchReceipt()
    val payload=JSONObject().put("records",JSONArray().apply { batch.forEach { put(it.toJson()) } }).apply {
        evidence?.let { put("usage_evidence",it.toJson(deviceId)) }
        interaction?.let {
            put("last_interaction_at",java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",java.util.Locale.US).apply {
                timeZone=java.util.TimeZone.getTimeZone("UTC")
            }.format(java.util.Date(it.at)))
            put("last_interaction_source",it.source)
        }
    }
    val request=Request.Builder().url(target.trimEnd('/')+"/api/v1/mobile/app-usage/batch")
        .header("X-Device-Id",deviceId).post(payload.toString().toRequestBody("application/json".toMediaType())).build()
    val call=if(gate!=null) gate.prepare(http,request) ?: return UsageBatchReceipt() else http.newCall(request)
    try {
        return call.execute().use { response ->
            if(!response.isSuccessful) return@use UsageBatchReceipt()
            val body=response.body?.string().orEmpty()
            val j=runCatching { JSONObject(body) }.getOrNull() ?: return@use UsageBatchReceipt()
            val ack=j.optJSONObject("usage_evidence_ack")
            val disposition=ack?.optString("disposition")?.takeIf {
                j.opt("ok")==true && evidence!=null && ack.optString("version")==evidence.version &&
                    (it=="saved" && !j.optBoolean("discarded") || it=="discarded" && j.optBoolean("discarded"))
            }
            UsageBatchReceipt(AppUsageTracker.acceptsReceipt(body,batch.size),disposition)
        }
    } finally { gate?.finish(call) }
}
