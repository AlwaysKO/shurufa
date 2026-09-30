package com.yuyan.imemodule.data.callrecording
import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File

/** 崩溃时保留私有文件与录音日志；只在输入空闲时解析和哈希，不上传未封装音频。 */
internal class CallRecordingSessions(private val root:File,private val outbox:CallRecordingOutbox,
    private val inspectAudio:(File)->Long? = ::inspectRecordedAudio) {
    private val json=Json{encodeDefaults=true;ignoreUnknownKeys=true}
    fun begin(device:String,metadata:CallMetadata):CallTask {
        val(id,_)=outbox.createAudioFile()
        val task=CallTask(id,device,metadata.copy(recording_status="interrupted",failure_reason="process_interrupted"))
        write(task);return task
    }
    fun finish(task:CallTask,metadata:CallMetadata,allowed:()->Boolean) {
        val final=task.copy(metadata=metadata)
        write(final)
        if(allowed())publish(final,allowed)
    }
    fun pendingCount():Int {CallRecordingOutbox.checkWorker();return root.listFiles().orEmpty().count{it.name.endsWith(".json")||it.name.endsWith(".json.bak")}}
    fun recover(allowed:()->Boolean,activeId:String?) {
        CallRecordingOutbox.checkWorker()
        val ids=root.listFiles().orEmpty().map{it.name.removeSuffix(".bak")}.filter{it.endsWith(".json")}.map{it.removeSuffix(".json")}.distinct()
        for(id in ids){
            if(!allowed())return
            if(id==activeId)continue
            try {
                val bytes=atomic(id).openRead().use{it.readBytesLimited()}
                val task=json.decodeFromString(CallTask.serializer(),bytes.toString(Charsets.UTF_8));check(task.id==id)
                if(outbox.tasks().any{it.id==id}){atomic(id).delete();continue}
                publish(task,allowed)
            }catch(e:kotlinx.coroutines.CancellationException){throw e}
            catch(_:Exception){ /* 坏日志或未完成容器原样保留，设置页报告待恢复数量。 */ }
        }
    }
    private fun publish(task:CallTask,allowed:()->Boolean) {
        if(!allowed())return
        val duration=inspectAudio(outbox.audioFile(task.id)) ?: return
        if(duration !in 1..7_200_000 || !allowed())return
        val metadata=task.metadata.copy(audio_duration_ms=duration,
            recording_ended_at=maxOf(task.metadata.recording_ended_at,task.metadata.recording_started_at+duration))
        if(outbox.tasks().none{it.id==task.id})outbox.enqueue(task.id,task.deviceId,metadata,allowed)
        atomic(task.id).delete()
    }
    private fun write(task:CallTask){
        val bytes=json.encodeToString(CallTask.serializer(),task).toByteArray();require(bytes.size<=16384)
        val file=atomic(task.id);val stream=file.startWrite()
        try{stream.write(bytes);file.finishWrite(stream)}catch(e:Exception){file.failWrite(stream);throw e}
    }
    private fun atomic(id:String):AtomicFile {
        CallRecordingOutbox.checkWorker();require(CallRecordingOutbox.validId(id));check(root.mkdirs()||root.isDirectory)
        val file=File(root,"$id.json");require(root.canonicalFile==root.absoluteFile&&file.canonicalFile==file.absoluteFile)
        return AtomicFile(file)
    }
    private fun java.io.InputStream.readBytesLimited():ByteArray {
        val result=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
        while(true){val n=read(buffer);if(n<0)break;require(result.size()+n<=16384);result.write(buffer,0,n)}
        return result.toByteArray()
    }
}


// 容器成功解析只证明存在音轨，不代表已采到通话双方。
private fun inspectRecordedAudio(file:File):Long? {
    val retriever=MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        if(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)!="yes")null
        else retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
    }catch(_:Exception){null}finally{retriever.release()}
}
