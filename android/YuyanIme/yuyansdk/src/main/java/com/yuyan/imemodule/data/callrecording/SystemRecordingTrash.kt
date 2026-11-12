package com.yuyan.imemodule.data.callrecording

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ServerConfig
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream

/** 荣耀共享回收站的已知录音子目录；不遍历其他应用内容，不恢复或删除原件。 */
internal class SystemRecordingTrash(
    private val storage:File,
    private val access:()->Boolean,
    private val now:()->Long=System::currentTimeMillis,
) {
    constructor(context:Context):this(Environment.getExternalStorageDirectory().canonicalFile,{
        val consent=CallRecordingRuntime.consent(context)
        supported() && hasFileAccess() && CollectionConsent.enabled(context) &&
            consent.wantsUpload && consent.hasTrashScope &&
            com.yuyan.imemodule.data.collect.BackendAddressMigration.resolveTarget(context,consent.destination)==ServerConfig.baseUrl
    })
    private val root get()=File(storage,".File_Recycle")
    fun readable()=access()
    fun list(platform:String,allowed:()->Boolean):List<SystemRecordingDocument> {
        if(!readable() || !root.exists())return emptyList()
        require(root.canonicalFile==root.absoluteFile)
        val result=mutableListOf<SystemRecordingDocument>()
        val buckets=root.listFiles() ?: error("trash_unreadable")
        require(buckets.size<=5000)
        var visited=0
        for(bucket in buckets) {
            check(allowed() && readable()){"transfer_paused"}
            if(!bucket.name.matches(Regex("[0-9]{13}")) || bucket.canonicalFile!=bucket.absoluteFile)continue
            val content=File(bucket,"storage/emulated/${userId()}/Sounds/CallRecord/hn_recycle_content")
            if(!content.exists() || content.canonicalFile!=content.absoluteFile)continue
            for(file in content.listFiles() ?: error("trash_unreadable")) {
                check(allowed() && readable()){"transfer_paused"}
                check(++visited<=5000){"trash_scan_limit"}
                val doc=describe(file) ?: continue
                if(doc.platform==platform && systemRecordingWithinImportWindow(doc,now()))result.add(doc)
            }
        }
        return result
    }
    private fun userId()=storage.name.takeIf{it.matches(Regex("[0-9]+"))} ?: "0"
    private fun describe(file:File):SystemRecordingDocument? {
        if(!readable() || file.canonicalFile!=file.absoluteFile || !file.isFile)return null
        val relative=file.relativeToOrNull(root)?.invariantSeparatorsPath ?: return null
        val pattern=Regex("[0-9]{13}/storage/emulated/${userId()}/Sounds/CallRecord/hn_recycle_content/[^/]+")
        if(!pattern.matches(relative))return null
        if(file.extension.lowercase() !in setOf("m4a","mp4","mp3","amr","wav"))return null
        val platform=when {
            file.name.startsWith("微信-")->"wechat"
            file.name.startsWith("通话-")->"phone"
            else->return null
        }
        // 删除时间和回收站mtime均不能冒充原录音时间；无法识别日期则不补传。
        if(systemRecordingNamedTime(file.name)==null)return null
        if(file.length() !in 1..CallRecordingOutbox.MAX_AUDIO_BYTES || file.lastModified()<=0)return null
        return SystemRecordingDocument(Uri.fromFile(file).toString(),file.name,file.length(),file.lastModified(),platform,trashed=true)
    }
    private fun file(uri:String):File {
        val value=Uri.parse(uri)
        require(value.scheme=="file" && value.authority.isNullOrEmpty())
        return File(requireNotNull(value.path))
    }
    fun unchanged(doc:SystemRecordingDocument)=runCatching{describe(file(doc.uri))==doc}.getOrDefault(false)
    fun exists(source:SystemRecordingSource)=runCatching {
        describe(file(source.uri))?.let{it.size==source.size && it.modifiedAt==source.modifiedAt}==true
    }.getOrDefault(false)
    fun open(doc:SystemRecordingDocument):InputStream {
        check(unchanged(doc)){"trash_changed_or_denied"}
        val input=file(doc.uri).inputStream()
        if(!unchanged(doc)){input.close();error("trash_changed_or_denied")}
        return object:FilterInputStream(input) {
            override fun read():Int {check(readable());return `in`.read()}
            override fun read(b:ByteArray,off:Int,len:Int):Int {check(readable());return `in`.read(b,off,len)}
        }
    }
    companion object {
        fun supported()=Build.VERSION.SDK_INT>=30 && Build.MANUFACTURER.equals("HONOR",ignoreCase=true)
        fun hasFileAccess()=Build.VERSION.SDK_INT>=30 && runCatching{Environment.isExternalStorageManager()}.getOrDefault(false)
        fun owns(uri:String)=Uri.parse(uri).scheme=="file"
    }
}
