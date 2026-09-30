package com.yuyan.imemodule.data.calllog

import com.yuyan.imemodule.data.callrecording.CallRecordingOutbox
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal class PhoneCallLogHttp(private val target:String,private val device:String,
    private val token:()->String?,private val allowed:()->Boolean,
    private val calls:Call.Factory=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS)
        .callTimeout(60,TimeUnit.SECONDS).build()) {
    private val json=Json{ignoreUnknownKeys=true;encodeDefaults=true}
    @Serializable private data class Pending(val request_id:String?)
    @Serializable private data class Receipt(val stored:Boolean,val count:Int)
    fun requestId():String? {
        val id=json.decodeFromString(Pending.serializer(),execute(null)).request_id
        if(id!=null&&!CallRecordingOutbox.validId(id))throw IOException("invalid_call_log_request")
        return id
    }
    fun send(batch:PhoneCallLogBatch){
        if(!allowed())throw IOException("call_log_paused")
        val bytes=json.encodeToString(PhoneCallLogBatch.serializer(),batch).toByteArray(Charsets.UTF_8)
        val response=execute(object:RequestBody(){
            override fun contentType()="application/json".toMediaType()
            override fun contentLength()=bytes.size.toLong()
            override fun writeTo(sink:BufferedSink){
                var offset=0
                while(offset<bytes.size){if(!allowed())throw IOException("call_log_paused")
                    val size=minOf(8192,bytes.size-offset);sink.write(bytes,offset,size);offset+=size}
            }
        })
        val receipt=json.decodeFromString(Receipt.serializer(),response)
        if(!receipt.stored||receipt.count!=batch.records.size)throw IOException("invalid_call_log_receipt")
    }
    private fun execute(body:RequestBody?):String {
        if(!allowed())throw IOException("call_log_paused")
        require(CallRecordingOutbox.validDestination(target)&&CallRecordingOutbox.validId(device))
        val credential=token()?.takeIf{Regex("[a-f0-9]{64}").matches(it)}?:throw IOException("device_credential_unavailable")
        val builder=Request.Builder().url("$target/api/v1/mobile/call-recordings/call-log/sync")
            .header("X-Device-Id",device).header("X-Dictionary-Token",credential)
        if(body!=null)builder.post(body)
        val call=calls.newCall(builder.build())
        val watcher=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"call-log-network-cancel").apply{isDaemon=true}}
        val watch=watcher.scheduleWithFixedDelay({if(!allowed())call.cancel()},0,100,TimeUnit.MILLISECONDS)
        try {call.execute().use{response->
            if(!allowed())throw IOException("call_log_paused")
            if(response.code!=200)throw IOException("call_log_http_${response.code}")
            val stream=response.body?.byteStream()?:throw IOException("call_log_empty_response")
            return stream.use{input->val result=ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true){if(!allowed())throw IOException("call_log_paused");val n=input.read(buffer);if(n<0)break
                    if(result.size()+n>16384)throw IOException("call_log_response_too_large");result.write(buffer,0,n)}
                result.toString("UTF-8")}
        }}finally{watch.cancel(true);watcher.shutdownNow()}
    }
}
