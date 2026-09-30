package com.yuyan.imemodule.data.callrecording

import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 默认严格 HTTPS，禁重定向，逐块避让输入/撤权，阻塞网络也定时取消。不记录音频、身份或令牌。 */
internal class CallRecordingHttpTransport(
    private val credential:(CallTask)->String?,
    private val allowed:()->Boolean,
    private val calls:Call.Factory=OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS)
        .callTimeout(180,TimeUnit.SECONDS).build(),
):CallTransport {
    private val json=Json {ignoreUnknownKeys=true;encodeDefaults=true}
    override fun receipt(task:CallTask):CallReceipt? = execute(task,"/receipt",null,allowed)
    override fun existing(task:CallTask):CallReceipt? {
        require(Regex("[a-f0-9]{64}").matches(task.metadata.sha256) && task.metadata.byte_size in 1..CallRecordingOutbox.MAX_AUDIO_BYTES)
        return execute(task,"",null,allowed,"by-content?sha256=${task.metadata.sha256}&byte_size=${task.metadata.byte_size}")
    }
    override fun upload(task:CallTask,file:File,allowed:()->Boolean):CallReceipt {
        val body=object:RequestBody(){
            override fun contentType()="application/octet-stream".toMediaType()
            override fun contentLength()=task.metadata.byte_size
            override fun writeTo(sink:BufferedSink) {
                if(file.length()!=task.metadata.byte_size)throw IOException("local_audio_changed")
                var written=0L
                file.inputStream().use{input->val buffer=ByteArray(16384);while(true){
                    if(!allowed() || !this@CallRecordingHttpTransport.allowed())throw IOException("transfer_paused")
                    val count=input.read(buffer);if(count<0)break
                    written+=count;if(written>task.metadata.byte_size)throw IOException("local_audio_changed")
                    sink.write(buffer,0,count)
                }}
                if(written!=task.metadata.byte_size)throw IOException("local_audio_changed")
            }
        }
        return execute(task,"",body,{allowed()&&this.allowed()}) ?: throw IOException("missing_receipt")
    }
    private fun execute(task:CallTask,suffix:String,body:RequestBody?,permitted:()->Boolean,path:String=task.id+suffix):CallReceipt? {
        CallRecordingOutbox.checkWorker()
        require(CallRecordingOutbox.validId(task.id)&&CallRecordingOutbox.validId(task.deviceId)&&CallRecordingOutbox.validDestination(task.metadata.destination))
        if(!permitted())throw IOException("transfer_paused")
        val token=credential(task) ?: throw IOException("device_credential_unavailable")
        if(!Regex("[a-f0-9]{64}").matches(token))throw IOException("device_credential_unavailable")
        val request=Request.Builder().url(task.metadata.destination+"/api/v1/mobile/call-recordings/"+path)
            .header("X-Device-Id",task.deviceId).header("X-Dictionary-Token",token)
        if(body!=null){
            val metadata=Base64.encodeToString(json.encodeToString(CallMetadata.serializer(),task.metadata).toByteArray(),Base64.NO_WRAP)
            require(metadata.length<=8192);request.header("X-Call-Metadata",metadata).put(body)
        }
        val call=calls.newCall(request.build())
        val watcher=Executors.newSingleThreadScheduledExecutor {r->Thread(r,"call-upload-cancel").apply{isDaemon=true}}
        val watch=watcher.scheduleWithFixedDelay({if(!permitted())call.cancel()},0,100,TimeUnit.MILLISECONDS)
        try {
            call.execute().use {response->
                if(!permitted())throw IOException("transfer_paused")
                if(body==null && response.code==404 && !path.startsWith("by-content?"))return null
                val contentMissing=body==null && response.code==404 && path.startsWith("by-content?")
                if(response.code!=200 && !contentMissing)throw IOException("call_http_${response.code}")
                val responseBody=response.body ?: throw IOException("missing_receipt")
                if(responseBody.contentLength()>16384)throw IOException("receipt_too_large")
                val bytes=responseBody.byteStream().use{input->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
                    while(true){if(!permitted())throw IOException("transfer_paused");val count=input.read(buffer);if(count<0)break
                        if(output.size()+count>16384)throw IOException("receipt_too_large");output.write(buffer,0,count)}
                    output.toByteArray()
                }
                if(contentMissing){
                    // 旧服务的路由 404 不等于“后台没有此文件”，否则升级期间会重复上传。
                    val error=runCatching{json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject["error"]?.jsonPrimitive?.content}.getOrNull()
                    if(error!="record_not_found")throw IOException("content_lookup_unavailable")
                    return null
                }
                return json.decodeFromString(CallReceipt.serializer(),bytes.toString(Charsets.UTF_8))
            }
        } finally {watch.cancel(true);watcher.shutdownNow()}
    }
}
