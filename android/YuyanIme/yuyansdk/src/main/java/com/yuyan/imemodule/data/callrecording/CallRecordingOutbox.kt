package com.yuyan.imemodule.data.callrecording

import android.os.Looper
import android.util.AtomicFile
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** 仅在独立 IO worker 使用。root 必须由运行入口指定为应用 noBackupFilesDir 下的独立目录。 */
internal class CallRecordingOutbox(
    private val root:File,
    private val maxPendingBytes:Long=512L*1024*1024,
    private val deleteAudio:(File)->Boolean={it.delete()},
    private val maxCompletedTasks:Int=100,
) {
    private val lock=locks.getOrPut(root.absolutePath){Any()}
    private val uploadLock=uploadLocks.getOrPut(root.absolutePath){java.util.concurrent.locks.ReentrantLock()}
    private val json=Json { encodeDefaults=true;ignoreUnknownKeys=true }
    fun <T> exclusive(block:()->T):T { checkWorker();return synchronized(lock){block()} }
    fun uploadBatch(block:()->Unit) {
        checkWorker();if(!uploadLock.tryLock())return
        try {block()} finally {uploadLock.unlock()}
    }
    fun createAudioFile():Pair<String,File> = exclusive {
        ensureRoot()
        val reservation=minOf(MAX_AUDIO_BYTES,maxPendingBytes)
        val used=root.listFiles().orEmpty().filter{it.extension=="m4a"}.sumOf{
            if(File(root,it.nameWithoutExtension+".json").exists())it.length() else maxOf(it.length(),reservation)
        }
        check(used+reservation<=maxPendingBytes){"call_queue_full"}
        check(root.listFiles().orEmpty().size<20000){"call_queue_full"}
        val id=UUID.randomUUID().toString();val file=audioFile(id)
        check(file.createNewFile());id to file
    }
    fun audioFile(id:String):File { checkWorker();return owned(id,"m4a") }
    fun enqueue(id:String,deviceId:String,metadata:CallMetadata,allowed:()->Boolean={true}):CallTask = exclusive {
        require(validId(deviceId));require(validDestination(metadata.destination))
        require(metadata.consent_version=="call-audio-v1")
        require(metadata.audio_duration_ms in 0..7_200_000 && metadata.recording_ended_at>=metadata.recording_started_at)
        require(metadata.recording_status in listOf("ended","interrupted","restricted"))
        val file=audioFile(id)
        require(file.isFile && file.length() in 1..MAX_AUDIO_BYTES)
        check(!owned(id,"json").exists()){"record_exists"}
        // 文件完成且落盘后，才发布持久化队列清单。
        FileOutputStream(file,true).use{it.fd.sync()}
        val task=CallTask(id,deviceId,metadata.copy(byte_size=file.length(),sha256=sha256(file,allowed)))
        check(allowed()){"transfer_paused"};save(task);task
    }
    fun tasks():List<CallTask> = exclusive {scan().first}
    /** 坏清单原样保留，供运行入口显示诊断；不能让一个坏项饿死其他录音。 */
    fun invalidEntries():List<String> = exclusive {scan().second}
    private fun scan():Pair<List<CallTask>,List<String>> {
        ensureRoot();val tasks=mutableListOf<CallTask>();val invalid=mutableListOf<String>()
        root.listFiles().orEmpty().map{it.name.removeSuffix(".bak")}.filter{it.endsWith(".json")}.distinct().sorted().forEach {
            val id=it.removeSuffix(".json")
            try{tasks.add(read(id))}catch(_:Exception){invalid.add(id)}
        }
        return tasks to invalid
    }
    fun markAttempt(id:String) = exclusive { val t=read(id);save(t.copy(uploadStatus="uploading",attempts=(t.attempts+1).coerceAtMost(30))) }
    fun pause(id:String) = exclusive {val t=read(id);if(t.uploadStatus!="saved")save(t.copy(uploadStatus="paused",lastError="authorization_or_input_paused"))}
    fun fail(id:String,now:Long,reason:String) = exclusive {
        val t=read(id);require(reason in listOf("network_error","invalid_receipt","local_audio_changed"))
        if(t.uploadStatus!="saved")save(t.copy(uploadStatus="failed",lastError=reason,nextAttemptAt=now+minOf(3_600_000L,30_000L*(1L shl t.attempts.coerceIn(0,7)))))
    }
    fun acceptReceipt(id:String,receipt:CallReceipt):Boolean = exclusive {
        val t=read(id)
        if(!matches(t,receipt))return@exclusive false
        save(t.copy(uploadStatus="saved",cleanupStatus="pending",receipt=receipt,lastError=null,nextAttemptAt=0));true
    }
    fun cleanup(id:String):Boolean = exclusive {
        val t=read(id)
        if(t.uploadStatus!="saved" || t.receipt==null || !matches(t,t.receipt))return@exclusive false
        if(t.cleanupStatus=="deleted")return@exclusive true
        val file=audioFile(id)
        // 只删除本队列按 UUID 命名的私有文件，不跟随符号链接或外部路径。
        if(file.exists() && !deleteAudio(file))return@exclusive false
        save(t.copy(cleanupStatus="deleted"))
        // 成功状态只留有界近期诊断；失败/待传/损坏清单不回收。
        val completed=scan().first.filter{it.cleanupStatus=="deleted" && it.uploadStatus=="saved" && it.id!=id}
            .sortedByDescending{owned(it.id,"json").lastModified()}
        completed.drop((maxCompletedTasks-1).coerceAtLeast(0)).forEach{AtomicFile(owned(it.id,"json")).delete()}
        true
    }
    fun verify(task:CallTask,allowed:()->Boolean):Boolean = exclusive {
        val file=audioFile(task.id)
        file.isFile && file.length()==task.metadata.byte_size && sha256(file,allowed)==task.metadata.sha256
    }
    private fun read(id:String):CallTask {
        val atomic=AtomicFile(owned(id,"json"))
        val bytes=atomic.openRead().use{stream->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(4096)
            while(true){val count=stream.read(buffer);if(count<0)break;check(out.size()+count<=16384){"invalid_manifest"};out.write(buffer,0,count)}
            out.toByteArray()
        }
        val task=json.decodeFromString(CallTask.serializer(),bytes.toString(Charsets.UTF_8))
        check(task.id==id && validId(task.deviceId) && validDestination(task.metadata.destination)){"invalid_manifest"}
        return task
    }
    private fun save(task:CallTask) {
        val bytes=json.encodeToString(CallTask.serializer(),task).toByteArray()
        require(bytes.size<=16384){"manifest_too_large"}
        ensureRoot();val file=AtomicFile(owned(task.id,"json"));val stream=file.startWrite()
        try {stream.write(bytes);file.finishWrite(stream)}
        catch(e:Exception){file.failWrite(stream);throw e}
    }
    private fun owned(id:String,extension:String):File {
        require(validId(id));ensureRoot()
        val file=File(root,"$id.$extension")
        require(file.canonicalFile==file.absoluteFile && file.parentFile?.canonicalFile==root.canonicalFile){"unsafe_audio_path"}
        return file
    }
    private fun ensureRoot(){check(root.mkdirs()||root.isDirectory);require(root.canonicalFile==root.absoluteFile){"unsafe_queue_path"}}
    companion object {
        const val MAX_AUDIO_BYTES=64L*1024*1024
        private val locks=ConcurrentHashMap<String,Any>()
        private val uploadLocks=ConcurrentHashMap<String,java.util.concurrent.locks.ReentrantLock>()
        fun checkWorker(){check(Looper.myLooper()!=Looper.getMainLooper()){"call_audio_requires_io_worker"}}
        fun validId(id:String)=Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}").matches(id)
        fun validDestination(value:String):Boolean=runCatching {
            val uri=URI(value)
            uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && uri.query==null && uri.fragment==null && uri.path.isNullOrEmpty()
        }.getOrDefault(false)
        private fun matches(task:CallTask,r:CallReceipt):Boolean {
            val timestamp=runCatching{val f=SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",Locale.ROOT);f.isLenient=false
                val normalized=if(r.stored_at.matches(Regex("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z")))r.stored_at.replace("Z",".000Z")else r.stored_at
                normalized.length==24 && f.parse(normalized)!=null}.getOrDefault(false)
            return r.stored && r.record_id==task.id && r.device_id==task.deviceId && r.byte_size==task.metadata.byte_size &&
                r.sha256==task.metadata.sha256 && timestamp
        }
        fun sha256(file:File,allowed:()->Boolean):String {
            checkWorker();val hash=MessageDigest.getInstance("SHA-256")
            file.inputStream().use {input->val buffer=ByteArray(16384);while(true){check(allowed()){"transfer_paused"};val n=input.read(buffer);if(n<0)break;hash.update(buffer,0,n)}}
            return hash.digest().joinToString(""){"%02x".format(it)}
        }
    }
}
