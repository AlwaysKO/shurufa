package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import java.io.InputStream
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** 只读取媒体库已公开的通话录音；不遍历私有存储，不修改原件。 */
internal class SystemRecordingMediaStore(private val context:Context) {
    private val resolver=context.contentResolver
    companion object {
        fun permission():String=if(Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        private val collection:Uri=MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        private const val WEEK_MS=7*86_400_000L
    }
    fun readable():Boolean=ContextCompat.checkSelfPermission(context,permission())==PackageManager.PERMISSION_GRANTED
    fun list(platform:String,allowed:()->Boolean):List<SystemRecordingDocument> {
        CallRecordingOutbox.checkWorker()
        require(platform in listOf("phone","wechat"))
        fun checkAllowed(){check(readable()){"recording_audio_permission_lost"};check(allowed()){"transfer_paused"}}
        checkAllowed()
        val now=System.currentTimeMillis()
        val selection=buildList {
            add("${MediaStore.Audio.Media.DATE_MODIFIED} >= ?")
            add("${MediaStore.Audio.Media.DATE_MODIFIED} <= ?")
            if(Build.VERSION.SDK_INT>=29)add("${MediaStore.MediaColumns.IS_PENDING} = 0")
            if(Build.VERSION.SDK_INT>=30)add("${MediaStore.MediaColumns.IS_TRASHED} = 0")
        }.joinToString(" AND ")
        val signal=CancellationSignal()
        val watcher=Executors.newSingleThreadScheduledExecutor{r->Thread(r,"recording-media-cancel").apply{isDaemon=true}}
        val watch=watcher.scheduleWithFixedDelay({
            if(!runCatching{readable()&&allowed()}.getOrDefault(false))signal.cancel()
        },0,100,TimeUnit.MILLISECONDS)
        try {
            val cursor=resolver.query(collection,columns(),selection,
                arrayOf(((now-WEEK_MS)/1000).toString(),(now/1000).toString()),
                "${MediaStore.Audio.Media.DATE_MODIFIED} DESC",signal) ?: error("recording_media_unavailable")
            val result=mutableListOf<SystemRecordingDocument>();var count=0
            cursor.use{c->
                checkAllowed()
                while(c.moveToNext()) {
                    checkAllowed();check(++count<=5000){"recording_media_too_large"}
                    val doc=document(c) ?: continue
                    if(doc.platform!=platform || doc.modifiedAt<now-WEEK_MS || doc.modifiedAt>now)continue
                    if(systemRecordingWithinSevenDays(doc,now))result.add(doc)
                }
            }
            checkAllowed()
            return result.sortedByDescending{it.modifiedAt}
        }finally{watch.cancel(true);watcher.shutdownNow()}
    }
    fun open(doc:SystemRecordingDocument):InputStream {
        CallRecordingOutbox.checkWorker()
        check(readable()){"recording_audio_permission_lost"}
        val uri=checkedUri(doc.uri) ?: error("recording_media_uri_invalid")
        val current=readDocument(uri)
        check(current==doc){"recording_changed"}
        check(readable()){"recording_audio_permission_lost"}
        return resolver.openInputStream(uri) ?: error("recording_unavailable")
    }
    fun unchanged(doc:SystemRecordingDocument):Boolean=runCatching {
        CallRecordingOutbox.checkWorker()
        if(!readable())return@runCatching false
        val uri=checkedUri(doc.uri) ?: return@runCatching false
        readDocument(uri)==doc && readable()
    }.getOrDefault(false)
    fun exists(source:SystemRecordingSource):Boolean=runCatching {
        CallRecordingOutbox.checkWorker()
        if(!readable())return@runCatching false
        val uri=checkedUri(source.uri) ?: return@runCatching false
        val doc=readDocument(uri) ?: return@runCatching false
        doc.size==source.size && doc.modifiedAt==source.modifiedAt && readable()
    }.getOrDefault(false)
    private fun checkedUri(value:String):Uri? {
        val uri=Uri.parse(value)
        val id=uri.lastPathSegment?.toLongOrNull() ?: return null
        if(id<=0 || ContentUris.withAppendedId(collection,id).toString()!=value)return null
        return uri
    }
    private fun columns():Array<String> = buildList {
        add(MediaStore.Audio.Media._ID);add(MediaStore.Audio.Media.DISPLAY_NAME)
        add(MediaStore.Audio.Media.SIZE);add(MediaStore.Audio.Media.DATE_MODIFIED)
        add(if(Build.VERSION.SDK_INT>=29)MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.Audio.Media.DATA)
        if(Build.VERSION.SDK_INT>=29)add(MediaStore.MediaColumns.IS_PENDING)
        if(Build.VERSION.SDK_INT>=30)add(MediaStore.MediaColumns.IS_TRASHED)
    }.toTypedArray()
    private fun readDocument(uri:Uri):SystemRecordingDocument? =
        resolver.query(uri,columns(),null,null,null)?.use{c->
            if(!c.moveToFirst())null else document(c)?.takeIf{it.uri==uri.toString()}
        }
    private fun document(c:Cursor):SystemRecordingDocument? {
        for(i in 0..4)if(c.isNull(i))return null
        if(Build.VERSION.SDK_INT>=29 && (c.isNull(5)||c.getInt(5)!=0))return null
        if(Build.VERSION.SDK_INT>=30 && (c.isNull(6)||c.getInt(6)!=0))return null
        val id=c.getLong(0);val name=c.getString(1);val size=c.getLong(2);val seconds=c.getLong(3)
        if(id<=0 || size !in 1..CallRecordingOutbox.MAX_AUDIO_BYTES || seconds<=0 || seconds>Long.MAX_VALUE/1000)return null
        if(name.substringAfterLast('.',"").lowercase(Locale.ROOT) !in setOf("m4a","mp4","mp3","amr","wav"))return null
        val location=c.getString(4)
        val path=if(Build.VERSION.SDK_INT>=29)location else {
            // 老版本只借 DATA 识别目录，打开仍使用只读 content URI。
            val relative=location.replaceFirst(Regex("^/storage/(?:emulated/[0-9]+|[A-Fa-f0-9]{4}-[A-Fa-f0-9]{4})/"),"")
            if(relative==location)return null
            relative.substringBeforeLast('/',"")
        }
        val platform=platform(path,name) ?: return null
        return SystemRecordingDocument(ContentUris.withAppendedId(collection,id).toString(),name,size,seconds*1000,platform)
    }
    private fun platform(path:String,name:String):String? {
        val directory=path.trimEnd('/').lowercase(Locale.ROOT)
        return when(directory) {
            "sounds/callrecord"->when {
                name.startsWith("微信-")->"wechat"
                name.startsWith("通话-")->"phone"
                else->null
            }
            "recordings/call","miui/sound_recorder/call_rec"->if(name.startsWith("微信-"))"wechat" else "phone"
            else->null
        }
    }
}
