package com.yuyan.imemodule.data.callrecording

import android.media.MediaMetadataRetriever
import android.util.AtomicFile
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID

internal data class SystemRecordingDocument(val uri:String,val name:String,val size:Long,val modifiedAt:Long,val platform:String)
internal data class RecordedSystemAudio(val duration:Long,val mime:String)
internal data class SystemImportResult(val imported:Int,val waiting:Int,val errors:Int,val finished:Boolean)

/** 索引独立于有界上传历史；成功记录不能因队列清理/应用重启再次导入。 */
internal class SystemRecordingImporter(
    private val root:File,private val outbox:CallRecordingOutbox,
    private val inspect:(File)->RecordedSystemAudio?=::inspectSystemRecording,
    private val now:()->Long=System::currentTimeMillis,
) {
    @Serializable private data class Entry(val observedAt:Long,val taskId:String?=null,val complete:Boolean=false)
    private val json=Json{encodeDefaults=true;ignoreUnknownKeys=true}
    fun scan(documents:List<SystemRecordingDocument>,device:String,target:String,allowed:()->Boolean,
             open:(SystemRecordingDocument)->InputStream,current:(SystemRecordingDocument)->Boolean):SystemImportResult {
        CallRecordingOutbox.checkWorker()
        var imported=0;var waiting=0;var errors=0
        val tasks=outbox.tasks().associateBy{it.id}
        // 崩溃可能遗留旧版本原件的半份缓存；通过先写索引的 UUID 日志精确回收。
        for(file in root.listFiles().orEmpty().filter{it.name.endsWith(".json")||it.name.endsWith(".json.bak")}) {
            if(!allowed())return SystemImportResult(0,0,errors,false)
            try {
                val key=file.name.removeSuffix(".bak").removeSuffix(".json")
                val entry=read(key) ?: continue
                if(!entry.complete && entry.taskId!=null && entry.taskId !in tasks) {
                    outbox.discardUnqueuedImport(entry.taskId);write(key,entry.copy(taskId=null))
                }
            }catch(e:CancellationException){throw e}catch(_:Exception){errors++}
        }
        for(doc in documents) {
            if(!allowed())return SystemImportResult(imported,waiting,errors,false)
            if(doc.size !in 1..CallRecordingOutbox.MAX_AUDIO_BYTES || doc.modifiedAt<=0){errors++;continue}
            var stagedId:String?=null
            try {
                val key=key(device,target,doc.platform,doc.uri,doc.size,doc.modifiedAt)
                var entry=read(key) ?: Entry(now()).also{write(key,it)}
                if(entry.complete)continue
                val existing=entry.taskId?.let{tasks[it]}
                if(existing!=null){if(existing.uploadStatus=="saved")markSaved(existing);continue}
                // 两次元数据一致，且超过最后一次写入 30 秒；不读正在增长的容器。
                if(now()-entry.observedAt<30_000 || now()-doc.modifiedAt<30_000){waiting++;continue}
                if(!current(doc)){waiting++;continue}
                if(entry.taskId==null) {
                    val id=UUID.randomUUID().toString()
                    // 日志先行，保证任何已预留缓存都有可恢复的所有权记录。
                    entry=entry.copy(taskId=id);write(key,entry)
                }
                stagedId=entry.taskId
                val file=outbox.audioFile(entry.taskId!!)
                if(!file.exists())outbox.createAudioFile(entry.taskId!!)
                open(doc).use{input->FileOutputStream(file).use{output->
                    val buffer=ByteArray(16384);var total=0L
                    while(true){
                        check(allowed()){"transfer_paused"}
                        val n=input.read(buffer);if(n<0)break
                        total+=n;check(total<=doc.size && total<=CallRecordingOutbox.MAX_AUDIO_BYTES)
                        output.write(buffer,0,n)
                    }
                    check(total==doc.size);output.fd.sync()
                }}
                if(!allowed())return SystemImportResult(imported,waiting,errors,false)
                if(!current(doc)){waiting++;continue}
                val audio=inspect(file)
                if(audio==null || audio.duration !in 1..7_200_000){errors++;continue}
                val namedStart=systemRecordingStartTime(doc.name)
                val start=namedStart ?: (doc.modifiedAt-audio.duration).coerceAtLeast(0)
                val metadata=CallMetadata(platform=doc.platform,destination=target,
                    recording_started_at=start,recording_ended_at=start+audio.duration,
                    audio_duration_ms=audio.duration,mime_type=audio.mime,call_duration_estimated=namedStart==null)
                outbox.enqueue(entry.taskId!!,device,metadata,allowed,SystemRecordingSource(doc.uri,doc.size,doc.modifiedAt,namedStart!=null))
                stagedId=null
                imported++
            }catch(e:CancellationException){throw e}
            catch(_:Exception){errors++;waiting++;if(!allowed())return SystemImportResult(imported,waiting,errors,false)}
            finally {stagedId?.let{runCatching{outbox.discardUnqueuedImport(it)}}}
        }
        return SystemImportResult(imported,waiting,errors,true)
    }
    fun markSaved(task:CallTask) {
        val source=task.source ?: return
        check(task.uploadStatus=="saved" && task.receipt!=null)
        val key=key(task.deviceId,task.metadata.destination,task.metadata.platform,source.uri,source.size,source.modifiedAt)
        val entry=read(key) ?: error("missing_system_recording_index")
        check(entry.taskId==task.id)
        if(!entry.complete)write(key,entry.copy(complete=true))
    }
    private fun key(device:String,target:String,platform:String,uri:String,size:Long,modified:Long)=
        UUID.nameUUIDFromBytes(listOf(device,target,platform,uri,size.toString(),modified.toString()).joinToString("\u0000").toByteArray()).toString()
    private fun atomic(key:String):AtomicFile {
        CallRecordingOutbox.checkWorker();check(root.mkdirs()||root.isDirectory)
        require(root.canonicalFile==root.absoluteFile);require(CallRecordingOutbox.validId(key))
        val file=File(root,"$key.json");require(file.canonicalFile==file.absoluteFile)
        return AtomicFile(file)
    }
    private fun read(key:String):Entry? {
        val file=atomic(key)
        if(!file.baseFile.exists()&&!File(file.baseFile.path+".bak").exists())return null
        return file.openRead().use{input->
            val bytes=ByteArray(4097);val n=input.read(bytes);require(n in 1..4096 && input.read()==-1)
            json.decodeFromString(Entry.serializer(),bytes.copyOf(n).toString(Charsets.UTF_8))
        }
    }
    private fun write(key:String,entry:Entry) {
        val file=atomic(key);val stream=file.startWrite()
        try{stream.write(json.encodeToString(Entry.serializer(),entry).toByteArray());file.finishWrite(stream)}
        catch(e:Exception){file.failWrite(stream);throw e}
    }
}

internal fun systemRecordingStartTime(name:String):Long? {
    val pattern=Regex("(?<![0-9])(20[0-9]{2})[-_]?([0-9]{2})[-_]?([0-9]{2})[ _-]?([0-9]{2})[-_:]?([0-9]{2})[-_:]?([0-9]{2})(?![0-9])")
    val matches=pattern.findAll(name).toList()
    val match=matches.singleOrNull() ?: return null
    val value=match.groupValues.drop(1).joinToString("")
    return runCatching{SimpleDateFormat("yyyyMMddHHmmss",Locale.ROOT).apply{isLenient=false}.parse(value)?.time}.getOrNull()
}

internal fun inspectSystemRecording(file:File):RecordedSystemAudio? {
    val header=ByteArray(12)
    if(file.inputStream().use{it.read(header)}<12)return null
    val mime=when {
        String(header,4,4,Charsets.US_ASCII)=="ftyp"->"audio/mp4"
        String(header,0,5,Charsets.US_ASCII)=="#!AMR"->"audio/amr"
        String(header,0,4,Charsets.US_ASCII)=="RIFF" && String(header,8,4,Charsets.US_ASCII)=="WAVE"->"audio/wav"
        String(header,0,3,Charsets.US_ASCII)=="ID3" || (header[0].toInt() and 255==255 && header[1].toInt() and 224==224)->"audio/mpeg"
        else->return null
    }
    val retriever=MediaMetadataRetriever()
    return try {
        retriever.setDataSource(file.absolutePath)
        if(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)!="yes")return null
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let{RecordedSystemAudio(it,mime)}
    }catch(_:Exception){null}finally{retriever.release()}
}
