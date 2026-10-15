package com.yuyan.imemodule.data.usage

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/** Shared config cache for usage and altitude evidence, scoped to the actual receiving target.
 * Never inherit capabilities from the collector_base_url advertised by a different server.
 */
internal class UsageEvidenceCapabilities(context: Context) {
    private val prefs=context.applicationContext.getSharedPreferences("usage_server_capabilities_v1",Context.MODE_PRIVATE)
    private fun key(target: String)=target.trimEnd('/')
    private fun entry(target: String)=runCatching { JSONObject(prefs.getString(key(target),null) ?: return null) }.getOrNull()
    fun retryDelay(target: String, now: Long=System.currentTimeMillis()): Long {
        val checked=entry(target)?.optLong("checked",0) ?: return 0
        // A backwards wall-clock jump must not freeze capability discovery indefinitely.
        return if(now<checked) 0 else (checked+7_200_000L-now).coerceAtLeast(0)
    }
    fun supported(target: String, now: Long=System.currentTimeMillis()): Boolean? {
        if(retryDelay(target,now)==0L) return null
        return entry(target)?.optBoolean("supported",false)
    }
    fun altitudeSupported(target: String, now: Long=System.currentTimeMillis()): Boolean? {
        if(retryDelay(target,now)==0L) return null
        val cached=entry(target) ?: return null
        // Entries written by the previous APK only knew the usage capability.
        return if(cached.has("location_altitude_evidence")) cached.optBoolean("location_altitude_evidence",false) else null
    }
    fun record(target: String, body: String, now: Long=System.currentTimeMillis()) {
        val config=runCatching { JSONObject(body) }.getOrNull() ?: return
        if(config.has("error")) return
        var altitudeSupported=false
        val supported=if(config.has("capabilities")) {
            val capabilities=config.optJSONObject("capabilities") ?: return
            if(capabilities.has("location_altitude_evidence")) {
                val version=(capabilities.opt("location_altitude_evidence") as? Number)?.toDouble() ?: return
                if(!version.isFinite() || version<0 || version%1.0!=0.0) return
                altitudeSupported=version==1.0
            }
            if(!capabilities.has("usage_evidence")) false else {
                val version=(capabilities.opt("usage_evidence") as? Number)?.toDouble() ?: return
                if(!version.isFinite() || version<0 || version%1.0!=0.0) return
                version==1.0
            }
        } else {
            // Missing capabilities only identifies an old server inside its real config envelope.
            val legacyUrl=config.opt("collector_base_url") as? String ?: return
            if(com.yuyan.imemodule.data.collect.normalizeOnlineServerUrl(legacyUrl)==null) return
            false
        }
        prefs.edit().putString(key(target),JSONObject().put("checked",now).put("supported",supported).put("location_altitude_evidence",altitudeSupported).toString()).commit()
    }
    fun ensureAltitudeSupported(http: OkHttpClient, target: String, deviceId: String, gate: UsageUploadGate): Boolean {
        altitudeSupported(target)?.let { return it }
        fetch(http,target,deviceId,gate)
        return altitudeSupported(target)==true
    }
    /** Called only for pending usage/location evidence, after cadence, consent, input/game and target gates. */
    fun fetch(http: OkHttpClient, target: String, deviceId: String, gate: UsageUploadGate): Boolean {
        val request=Request.Builder().url(key(target)+"/api/v1/mobile/config").header("X-Device-Id",deviceId).get().build()
        val call=gate.prepare(http,request) ?: return false
        try {
            call.execute().use { response ->
                if(response.isSuccessful && !call.isCanceled()) record(target,response.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            // Transient/cancelled requests are not old-server evidence; cadence bounds the next retry.
        } finally { gate.finish(call) }
        return supported(target)==true
    }
}
