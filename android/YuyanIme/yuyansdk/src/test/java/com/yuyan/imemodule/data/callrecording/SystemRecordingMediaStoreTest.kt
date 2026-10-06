package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28,33])
class SystemRecordingMediaStoreTest {
    private val context=ApplicationProvider.getApplicationContext<Application>()
    private val now=System.currentTimeMillis()
    private val modified=now/1000-60
    private fun io(block:()->Unit) {val e=Executors.newSingleThreadExecutor();try{e.submit(block).get()}finally{e.shutdownNow()}}
    private fun row(id:Long,name:String,path:String="Sounds/CallRecord/",time:Long=modified)=mapOf<String,Any>(
        "_id" to id,"_display_name" to name,"_size" to 24L,"date_modified" to time,
        "relative_path" to path,"_data" to "/storage/emulated/0/$path$name","is_pending" to 0,"is_trashed" to 0)
    private open class Provider(val file:File,var rows:List<Map<String,Any>>):ContentProvider() {
        var queries=0;var opens=0;var writes=0;var selection:String?=null;var arguments:List<String>?=null
        var beforeQuery:(()->Unit)?=null
        override fun onCreate()=true
        override fun getType(uri:Uri)="audio/mp4"
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
            queries++;this.selection=selection;arguments=args?.toList();beforeQuery?.invoke()
            val selected=if(uri.lastPathSegment=="media")rows else rows.filter{it["_id"].toString()==uri.lastPathSegment}
            return MatrixCursor(projection!!).apply{selected.forEach{row->addRow(projection.map{row[it]}.toTypedArray())}}
        }
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor {
            require(mode=="r");opens++;return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun insert(uri:Uri,values:ContentValues?):Uri? {writes++;error("read_only")}
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int {writes++;error("read_only")}
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int {writes++;error("read_only")}
    }
    private fun provider(rows:List<Map<String,Any>>):Provider {
        val file=File(context.noBackupFilesDir,"media-"+UUID.randomUUID()).apply{parentFile!!.mkdirs();writeText("synthetic audio original")}
        return Provider(file,rows).also(::register)
    }
    private fun register(provider:Provider) {
        provider.attachInfo(context,ProviderInfo().apply{authority="media";exported=true})
        ShadowContentResolver.registerProviderInternal("media",provider)
    }
    private fun grant(){shadowOf(context).grantPermissions(SystemRecordingMediaStore.permission())}
    @Test fun `使用系统版本对应的音频读取权限`() {
        val expected=if(android.os.Build.VERSION.SDK_INT>=33)Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
        assertEquals(expected,SystemRecordingMediaStore.permission())
    }
    @Test fun `未授权不查询或打开媒体`()=io {
        val p=provider(listOf(row(1,"通话-测试.m4a")));val source=SystemRecordingMediaStore(context)
        shadowOf(context).denyPermissions(SystemRecordingMediaStore.permission())
        assertFalse(source.readable())
        assertTrue(runCatching{source.list("phone"){true}}.isFailure)
        val doc=SystemRecordingDocument("content://media/external/audio/media/1","通话-测试.m4a",24,modified*1000,"phone")
        assertTrue(runCatching{source.open(doc)}.isFailure)
        assertFalse(source.unchanged(doc));assertEquals(0,p.queries);assertEquals(0,p.opens)
    }
    @Test fun `只发现明确电话微信录音并过滤音乐语音消息旧日期和未完成文件`()=io {
        grant()
        val p=provider(listOf(row(1,"通话-微信客服.m4a"),row(2,"微信-测试.m4a"),row(3,"随手录音.m4a"),
            row(4,"微信-歌曲.mp3","Music/"),row(5,"msg.amr","Android/media/com.tencent.mm/MicroMsg/voice2/"),
            row(6,"call.mp3","MIUI/sound_recorder/call_rec/"),row(7,"测试.m4a","Recordings/Call/"),
            row(8,"通话-旧.m4a",time=modified-8*86400),row(9,"通话-测试-202001011200.m4a"),
            row(10,"通话-未完成.m4a")+mapOf("is_pending" to 1),row(11,"通话-回收.m4a")+mapOf("is_trashed" to 1),
            row(12,"通话-附件.exe")))
        val source=SystemRecordingMediaStore(context)
        val expected=mutableListOf(1L,6L,7L)
        // Android 28 的媒体表没有待写入与回收站字段。
        if(android.os.Build.VERSION.SDK_INT<29)expected.add(10)
        if(android.os.Build.VERSION.SDK_INT<30)expected.add(11)
        assertEquals(expected,source.list("phone"){true}.map{Uri.parse(it.uri).lastPathSegment!!.toLong()})
        assertEquals(listOf("微信-测试.m4a"),source.list("wechat"){true}.map{it.name})
        assertTrue(p.selection!!.contains("date_modified"));assertTrue(p.arguments!!.any{it.toLongOrNull()?.let{n->n>modified-8*86400}==true})
        assertEquals(0,p.opens);assertEquals(0,p.writes)
    }
    @Test fun `只读打开并复核原始元数据且拒绝任意URI`()=io {
        grant();val p=provider(listOf(row(1,"通话-测试.m4a")));val source=SystemRecordingMediaStore(context)
        val doc=source.list("phone"){true}.single();val bytes=p.file.readBytes()
        assertTrue(source.unchanged(doc));assertArrayEquals(bytes,source.open(doc).use{it.readBytes()})
        for(uri in listOf("file:///sdcard/test.m4a","content://other/external/audio/media/1",
            "content://media/internal/audio/media/1","content://media/external/images/media/1",
            "content://media/external/audio/media/1?redirect=1","content://media/external/audio/media/01")) {
            assertFalse(source.unchanged(doc.copy(uri=uri)))
            assertTrue(runCatching{source.open(doc.copy(uri=uri))}.isFailure)
        }
        p.rows=listOf(row(1,"通话-测试.m4a")+mapOf("_size" to 25L))
        assertFalse(source.unchanged(doc));assertTrue(runCatching{source.open(doc)}.isFailure)
        assertArrayEquals(bytes,p.file.readBytes());assertEquals(0,p.writes)
    }
    @Test fun `没有选择任何文件夹也通过统一来源发现并读取电话微信录音`()=io {
        CallRecordingRuntime.preferences(context).edit().clear().commit()
        assertTrue(context.contentResolver.persistedUriPermissions.isEmpty())
        grant();val p=provider(listOf(row(1,"通话-测试.m4a"),row(2,"微信-测试.m4a")))
        val sources=SystemRecordingSources(context)
        for(platform in listOf("phone","wechat")) {
            assertTrue(sources.configured(platform));assertTrue(sources.readable(platform))
            val scan=sources.scan(platform){true}
            assertTrue(scan.complete)
            val doc=scan.files.single();assertEquals(platform,doc.platform)
            assertTrue(sources.unchanged(doc))
            assertTrue(sources.exists(SystemRecordingSource(doc.uri,doc.size,doc.modifiedAt,false)))
            assertArrayEquals(p.file.readBytes(),sources.open(doc).use{it.readBytes()})
        }
        assertEquals(0,p.writes)
    }
    @Test fun `查询返回时撤销授权或输入忙立即停止扫描`()=io {
        grant();val p=provider(listOf(row(1,"通话-测试.m4a")));val source=SystemRecordingMediaStore(context)
        val allowed=AtomicBoolean(true);p.beforeQuery={allowed.set(false)}
        assertTrue(runCatching{source.list("phone"){allowed.get()}}.isFailure)
        p.beforeQuery={shadowOf(context).denyPermissions(SystemRecordingMediaStore.permission())}
        assertTrue(runCatching{source.list("phone"){true}}.isFailure)
        assertEquals(0,p.opens)
    }
    @Test fun `查询阻塞期间输入忙会取消ContentProvider查询`()=io {
        grant();val allowed=AtomicBoolean(true);val entered=CountDownLatch(1);val canceled=CountDownLatch(1)
        val p=object:Provider(File(context.noBackupFilesDir,"unused"),emptyList()) {
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?,signal:CancellationSignal?):Cursor {
                signal!!.setOnCancelListener{canceled.countDown()};entered.countDown()
                check(canceled.await(3,TimeUnit.SECONDS)){"query_not_canceled"}
                signal.throwIfCanceled();return MatrixCursor(projection!!)
            }
        };register(p)
        val e=Executors.newSingleThreadExecutor()
        try {
            val scan=e.submit<Boolean>{runCatching{SystemRecordingMediaStore(context).list("phone"){allowed.get()}}.isFailure}
            assertTrue(entered.await(3,TimeUnit.SECONDS));allowed.set(false)
            assertTrue(canceled.await(3,TimeUnit.SECONDS));assertTrue(scan.get(3,TimeUnit.SECONDS))
        }finally{e.shutdownNow()}
    }
    @Test fun `超过扫描上限中止而不静默漏扫`()=io {
        grant();provider((1L..5001L).map{row(it,"通话-测试.m4a")})
        assertTrue(runCatching{SystemRecordingMediaStore(context).list("phone"){true}}.isFailure)
    }
}
