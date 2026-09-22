package com.yuyan.imemodule.data.collect

import android.content.SharedPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.security.MessageDigest
import java.security.SecureRandom

/** 当前目标独立上传；只有主控目标可恢复与确认，备份目标绝不覆盖手机决策；两端均可纯追加词语。调用方在 IO 线程运行。 */
internal class PersonalDictionarySync(
    private val store: LocalInputStore,
    private val prefs: SharedPreferences,
    private val http: OkHttpClient,
    private val deviceId: String,
    baseUrl: String,
    private val enabled: () -> Boolean,
    private val migration: () -> Pair<String,Int>,
    private val restoreFromTarget: Boolean = true,
    private val statePrefix: String = "",
) {
    private class AdditionCursorReset : Exception()
    private val endpoint=baseUrl.trimEnd('/')+"/api/v1/mobile/dictionary"
    private val json=Json { ignoreUnknownKeys=true; encodeDefaults=true }
    private val mediaType="application/json; charset=utf-8".toMediaType()
    @Synchronized fun run(): Boolean {
        if (!enabled()) return false
        return try {
            val token=prefs.getString(statePrefix+"token",null) ?: ByteArray(32).also { SecureRandom().nextBytes(it) }
                .joinToString("") { "%02x".format(it) }.also { check(prefs.edit().putString(statePrefix+"token",it).commit()) }
            fun request(path:String, body:String?=null):String {
                check(enabled()) { "sync disabled" }
                val builder=Request.Builder().url(endpoint+path).header("X-Device-Id",deviceId).header("X-Dictionary-Token",token)
                if(body!=null) builder.post(body.toRequestBody(mediaType))
                return http.newCall(builder.build()).execute().use { response ->
                    val payload=response.body ?: error("empty dictionary response")
                    check(payload.contentLength() <= 32L*1024*1024) { "dictionary response too large" }
                    // 同时限制 chunked 响应，不能只相信 Content-Length。
                    val bytes=payload.byteStream().use { input ->
                        val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                        while(true) {val n=input.read(buffer);if(n<0) break;check(out.size()+n<=32*1024*1024);out.write(buffer,0,n)}
                        out.toByteArray()
                    }
                    val text=String(bytes,Charsets.UTF_8)
                    if(response.code==409 && (path.startsWith("/additions?after=") || path.startsWith("/habits?after=")) &&
                        runCatching { json.parseToJsonElement(text).jsonObject["code"]?.jsonPrimitive?.content }.getOrNull()=="dictionary_cursor_reset") {
                        throw AdditionCursorReset()
                    }
                    check(response.isSuccessful) { "dictionary HTTP ${response.code}" }
                    text
                }
            }
            // 注册回执不缓存：服务端重置后必须补传本机数据。
            val registered=json.parseToJsonElement(request("/register",buildJsonObject {put("restore_enabled",restoreFromTarget);put("additions_supported",true);put("habits_supported",true)}.toString())).jsonObject
            val records=store.dictionaryExport()
            val (status,imported)=migration()
            val serialized=json.encodeToString(ListSerializer(DictionaryRecord.serializer()),records)
            val fingerprint=MessageDigest.getInstance("SHA-256").digest((endpoint+serialized+status+imported).toByteArray()).joinToString("") { "%02x".format(it) }
            // 周期性补传全量也采用替换语义，服务器不会把上报当新点击。
            val due=System.currentTimeMillis()-prefs.getLong(statePrefix+"uploaded_at",0)>24*60*60*1000L
            if(registered["has_report"]?.jsonPrimitive?.booleanOrNull != true || prefs.getString(statePrefix+"uploaded_hash",null)!=fingerprint || due) {
                val sequence=maxOf(System.currentTimeMillis(),prefs.getLong(statePrefix+"sequence",0)+1)
                check(prefs.edit().putLong(statePrefix+"sequence",sequence).commit())
                val batches=records.chunked(500).ifEmpty { listOf(emptyList()) }
                batches.forEach { batch -> request("/report",json.encodeToString(DictionaryReport.serializer(),DictionaryReport(sequence,batch,status,imported))) }
                check(prefs.edit().putString(statePrefix+"uploaded_hash",fingerprint).putLong(statePrefix+"uploaded_at",System.currentTimeMillis()).commit())
            }
            if(registered["additions_supported"]?.jsonPrimitive?.booleanOrNull == true) {
                val target=endpoint+"#"+deviceId
                var after=store.dictionaryAdditionCursor(target)
                var reset=false
                var pages=0
                while(pages<20) {
                    val payload=try { request("/additions?after=$after") } catch(e:AdditionCursorReset) {
                        check(!reset && after>0)
                        // 服务器明确要求重放时仅复位本目标游标，绝不清词或修改另一端状态。
                        reset=true;after=0;store.saveDictionaryAdditionCursor(target,0);continue
                    }
                    val additions=json.decodeFromString(DictionaryAdditions.serializer(),payload)
                    require(additions.validAfter(after))
                    check(enabled())
                    if(additions.entries.isNotEmpty()) {
                        store.mergeDictionaryAdditions(additions.entries)
                        val ack=json.parseToJsonElement(request("/additions/ack",buildJsonObject {put("cursor",additions.cursor)}.toString())).jsonObject
                        check(ack["ok"]?.jsonPrimitive?.booleanOrNull==true)
                        store.saveDictionaryAdditionCursor(target,additions.cursor)
                        after=additions.cursor
                    }
                    pages++
                    if(!additions.hasMore) break
                }
            }
            if(registered["habits_supported"]?.jsonPrimitive?.booleanOrNull == true) {
                val target=endpoint+"#"+deviceId
                var after=store.dictionaryHabitCursor(target)
                var reset=false
                var pages=0
                while(pages<20) {
                    val payload=try {request("/habits?after=$after")} catch(e:AdditionCursorReset) {
                        check(!reset && after>0)
                        reset=true;after=0;store.saveDictionaryHabitCursor(target,0);continue
                    }
                    val habits=json.decodeFromString(DictionaryHabits.serializer(),payload)
                    require(habits.validAfter(after))
                    check(enabled())
                    if(habits.entries.isNotEmpty()) {
                        store.mergeDictionaryHabits(habits.entries,deviceId)
                        val ack=json.parseToJsonElement(request("/habits/ack",buildJsonObject {put("cursor",habits.cursor)}.toString())).jsonObject
                        check(ack["ok"]?.jsonPrimitive?.booleanOrNull==true)
                        store.saveDictionaryHabitCursor(target,habits.cursor)
                        after=habits.cursor
                    }
                    pages++
                    if(!habits.hasMore) break
                }
            }
            if (!restoreFromTarget) return true
            val snapshot=json.decodeFromString(DictionarySnapshot.serializer(),request(""))
            check(enabled())
            store.applyDictionarySnapshot(snapshot,deviceId)
            request("/ack",buildJsonObject {put("revision",snapshot.revision)}.toString())
            true
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {false} // 不记录词语、拼音或凭据到日志；原始数据保留待重试。
    }
}
