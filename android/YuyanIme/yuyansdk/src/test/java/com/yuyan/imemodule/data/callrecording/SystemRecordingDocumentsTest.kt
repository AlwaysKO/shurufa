package com.yuyan.imemodule.data.callrecording

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SystemRecordingDocumentsTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private fun io(block:()->Unit){val e=Executors.newSingleThreadExecutor();try{e.submit(block).get()}finally{e.shutdownNow()}}
    private open class Provider(val file:File):ContentProvider() {
        var writes=0
        override fun onCreate()=true
        override fun getType(uri:Uri)="audio/mp4"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            val columns=projection!!
            val values:Map<String,Any> = mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to "primary:Calls/Call_20260930_123000.m4a",
                DocumentsContract.Document.COLUMN_DISPLAY_NAME to "Call_20260930_123000.m4a",DocumentsContract.Document.COLUMN_SIZE to file.length(),
                DocumentsContract.Document.COLUMN_LAST_MODIFIED to file.lastModified(),DocumentsContract.Document.COLUMN_MIME_TYPE to "audio/mp4")
            return MatrixCursor(columns).apply{addRow(columns.map{values[it]}.toTypedArray())}
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            check(mode=="r"){"must_read_only"}
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int{writes++;error("original_must_not_be_deleted")}
        override fun insert(uri:Uri,values:ContentValues?):Uri?{writes++;error("original_must_not_be_changed")}
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int{writes++;error("original_must_not_be_changed")}
    }
    @Test fun `ContentResolver只读授权导入上传后保留提供者原文件`()=io {
        CallRecordingRuntime.preferences(context).edit().clear().commit()
        val root=File(context.noBackupFilesDir,"provider-"+UUID.randomUUID()).apply{mkdirs()}
        val original=File(root,"original.m4a").apply{writeText("0000ftypM4A synthetic provider audio");setLastModified(1_790_000_000_000)}
        val originalBytes=original.readBytes()
        val provider=Provider(original)
        provider.attachInfo(context,ProviderInfo().apply{authority="recording.test";exported=true;grantUriPermissions=true})
        ShadowContentResolver.registerProviderInternal("recording.test",provider)
        val documents=SystemRecordingDocuments(context)
        documents.setTree("phone",DocumentsContract.buildTreeDocumentUri("recording.test","primary:Calls"))
        assertTrue(documents.readable("phone"))
        assertTrue(context.contentResolver.persistedUriPermissions.all{it.isReadPermission&&!it.isWritePermission})
        val document=documents.list("phone"){true}.single()
        assertTrue(documents.unchanged(document))
        assertArrayEquals(originalBytes,documents.open(document).use{it.readBytes()})
        val box=CallRecordingOutbox(File(root,"outbox"));var now=original.lastModified()+60000
        val importer=SystemRecordingImporter(File(root,"index"),box,{RecordedSystemAudio(60000,"audio/mp4")},{now})
        val device=UUID.randomUUID().toString();val target="https://example.test"
        importer.scan(documents.list("phone"){true},device,target,{true},documents::open,documents::unchanged)
        now+=31000
        importer.scan(documents.list("phone"){true},device,target,{true},documents::open,documents::unchanged)
        assertEquals(1,box.tasks().size)
        val transport=object:CallTransport {
            override fun receipt(task:CallTask):CallReceipt?=null
            override fun upload(task:CallTask,file:File,allowed:()->Boolean)=CallReceipt(true,task.id,task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
        }
        CallRecordingUploader(box,transport,{true},{target},systemSourceExists=documents::exists,onSaved=importer::markSaved).runOnce()
        assertEquals("deleted",box.tasks().single().cleanupStatus)
        assertTrue(original.exists());assertArrayEquals(originalBytes,original.readBytes());assertEquals(0,provider.writes)
        documents.clear("phone");assertFalse(documents.exists(box.tasks().single().source!!))
    }
    @Test fun `电话微信拒绝相同目录防止同一原件分类两次上传`()=io {
        CallRecordingRuntime.preferences(context).edit().clear().commit()
        val documents=SystemRecordingDocuments(context)
        documents.setTree("phone",DocumentsContract.buildTreeDocumentUri("recording.test","primary:Calls"))
        try {documents.setTree("wechat",DocumentsContract.buildTreeDocumentUri("recording.test","primary:Calls"));fail("必须拒绝相同目录")}
        catch(_:IllegalArgumentException){}
        assertNull(documents.tree("wechat"))
    }
    @Test fun `荣耀微信根目录与电话子目录可分别配置`()=io {
        CallRecordingRuntime.preferences(context).edit().clear().commit()
        val documents=SystemRecordingDocuments(context)
        documents.setTree("phone",DocumentsContract.buildTreeDocumentUri("recording.test","primary:Sounds/CallRecord"))
        documents.setTree("wechat",DocumentsContract.buildTreeDocumentUri("recording.test","primary:Sounds"))
        assertTrue(documents.readable("phone"));assertTrue(documents.readable("wechat"))
    }
    @Test fun `读取微信根目录不会递归把电话子目录音频当微信上传`()=io {
        CallRecordingRuntime.preferences(context).edit().clear().commit()
        val file=File(context.noBackupFilesDir,"fixture-"+UUID.randomUUID()).apply{writeText("synthetic")}
        val provider=object:Provider(file) {
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
                val columns=projection!!
                val parent=DocumentsContract.getDocumentId(uri)
                val entries=if(parent=="primary:Sounds")listOf("wechat.m4a" to "audio/mp4","CallRecord" to DocumentsContract.Document.MIME_TYPE_DIR)
                    else listOf("phone.m4a" to "audio/mp4")
                return MatrixCursor(columns).apply{for((name,mime) in entries){
                    val values:Map<String,Any> = mapOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID to "$parent/$name",
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME to name,DocumentsContract.Document.COLUMN_SIZE to 100L,
                        DocumentsContract.Document.COLUMN_LAST_MODIFIED to 1000L,DocumentsContract.Document.COLUMN_MIME_TYPE to mime)
                    addRow(columns.map{values[it]}.toTypedArray())
                }}
            }
        }
        provider.attachInfo(context,ProviderInfo().apply{authority="recording.test";exported=true;grantUriPermissions=true})
        ShadowContentResolver.registerProviderInternal("recording.test",provider)
        val documents=SystemRecordingDocuments(context)
        documents.setTree("wechat",DocumentsContract.buildTreeDocumentUri("recording.test","primary:Sounds"))
        assertEquals(listOf("wechat.m4a"),documents.list("wechat"){true}.map{it.name})
    }
}
