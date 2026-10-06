package com.yuyan.imemodule.data.callrecording

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import java.io.InputStream

/** 仅使用 SAF 只读授权，不申请全盘访问，不调用删除或修改外部文件的 API。 */
internal class SystemRecordingDocuments(private val context:Context) {
    private val resolver=context.contentResolver
    private val prefs=CallRecordingRuntime.preferences(context)
    fun tree(platform:String):Uri?=prefs.getString("system_tree_$platform",null)?.let(Uri::parse)
    fun setTree(platform:String,uri:Uri) {
        require(platform in listOf("phone","wechat") && uri.scheme=="content" && DocumentsContract.isTreeUri(uri))
        val other=tree(if(platform=="phone")"wechat"else"phone")
        require(other==null || !sameDirectory(uri,other) || isHonorMixedDirectory(uri)){"same_recording_directory"}
        resolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)
        check(prefs.edit().putString("system_tree_$platform",uri.toString()).commit())
    }
    fun clear(platform:String){require(platform in listOf("phone","wechat"));prefs.edit().remove("system_tree_$platform").apply()}
    private fun trees(platform:String):List<Uri> {
        val granted=resolver.persistedUriPermissions.filter{it.isReadPermission}.map{it.uri}
        return (granted.filter{runCatching{isHonorMixedDirectory(it)}.getOrDefault(false)} +
            listOfNotNull(tree(platform)?.takeIf{it in granted})).distinct()
    }
    fun readable(platform:String)=trees(platform).isNotEmpty()
    var scanComplete=false
        private set
    fun list(platform:String,allowed:()->Boolean):List<SystemRecordingDocument> {
        CallRecordingOutbox.checkWorker()
        val authorized=trees(platform)
        scanComplete=tree(platform)==null || tree(platform) in authorized
        val result=mutableListOf<SystemRecordingDocument>();var count=0
        for(tree in authorized) try {
            check(allowed()){"transfer_paused"}
            val children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree))
            val columns=arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED,DocumentsContract.Document.COLUMN_MIME_TYPE)
            val cursor=resolver.query(children,columns,null,null,null) ?: error("recording_directory_unavailable")
            cursor.use{c->while(c.moveToNext()) {
                check(allowed()){"transfer_paused"};check(++count<=5000){"recording_directory_too_large"}
                val child=c.getString(0);val name=c.getString(1)?:""
                if(c.getString(4)==DocumentsContract.Document.MIME_TYPE_DIR)continue
                if(name.substringAfterLast('.',"").lowercase() !in setOf("m4a","mp4","mp3","amr","wav"))continue
                if(isHonorMixedDirectory(tree)) {
                    val source=when {
                        name.startsWith("微信-")->"wechat"
                        name.startsWith("通话-")->"phone"
                        else->null
                    }
                    if(source!=platform)continue
                }
                result.add(SystemRecordingDocument(DocumentsContract.buildDocumentUriUsingTree(tree,child).toString(),name,
                    if(c.isNull(2))0 else c.getLong(2),if(c.isNull(3))0 else c.getLong(3),platform))
            }}
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
        catch(_:Exception){check(allowed()){"transfer_paused"};scanComplete=false}
        return result.distinctBy{it.uri}.sortedByDescending{it.modifiedAt}
    }
    fun open(doc:SystemRecordingDocument):InputStream=resolver.openInputStream(Uri.parse(doc.uri)) ?: error("recording_unavailable")
    fun unchanged(doc:SystemRecordingDocument)=exists(SystemRecordingSource(doc.uri,doc.size,doc.modifiedAt,false))
    fun exists(source:SystemRecordingSource):Boolean=runCatching {
        val uri=Uri.parse(source.uri)
        val authorized=listOf("phone","wechat").any{platform->
            trees(platform).any{selected->selected.authority==uri.authority &&
                DocumentsContract.getTreeDocumentId(selected)==DocumentsContract.getTreeDocumentId(uri)}
        }
        if(!authorized)return@runCatching false
        resolver.query(uri,arrayOf(DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_LAST_MODIFIED),null,null,null)?.use{
            it.moveToFirst() && !it.isNull(0) && !it.isNull(1) && it.getLong(0)==source.size && it.getLong(1)==source.modifiedAt
        } ?: false
    }.getOrDefault(false)
    private fun sameDirectory(a:Uri,b:Uri):Boolean {
        if(a.authority!=b.authority)return false
        val x=DocumentsContract.getTreeDocumentId(a).trimEnd('/');val y=DocumentsContract.getTreeDocumentId(b).trimEnd('/')
        return x==y
    }
    private fun isHonorMixedDirectory(uri:Uri)=Build.MANUFACTURER.equals("HONOR",ignoreCase=true) &&
        uri.authority=="com.android.externalstorage.documents" &&
        DocumentsContract.getTreeDocumentId(uri).trimEnd('/')=="primary:Sounds/CallRecord"
}

internal fun systemRecordingUploadReady(task:CallTask,configured:Boolean,scanComplete:Boolean,now:Long):Boolean =
    task.source!=null || !configured || now-task.metadata.recording_ended_at >= if(scanComplete)120_000 else 600_000
