package com.yuyan.imemodule.data.callrecording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class SystemRecordingImporterTest {
    private fun io(block:()->Unit){val e=Executors.newSingleThreadExecutor();try{e.submit(block).get()}finally{e.shutdownNow()}}
    private val device=UUID.randomUUID().toString()
    private val target="https://example.test"
    private class Fixture {
        val root=File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir,UUID.randomUUID().toString())
        val box=CallRecordingOutbox(File(root,"outbox"))
        val bytes="0000ftypM4A synthetic".toByteArray()
        var now=systemRecordingStartTime("20260930_133000")!!
        val doc=SystemRecordingDocument("content://test/recording","Call_20260930_123000.m4a",bytes.size.toLong(),now-60000,"phone")
        fun importer()=SystemRecordingImporter(File(root,"index"),box,{RecordedSystemAudio(60000,"audio/mp4")},{now})
    }
    @Test fun `超过七天的原件不观察不打开即使刚复制过也跳过`()=io {
        val f=Fixture();var opened=0
        val old=f.doc.copy(name="微信-测试-202609201205.m4a",modifiedAt=f.now-60000)
        val expired=f.doc.copy(uri="content://test/old",name="unknown.m4a",modifiedAt=f.now-8*86400000L)
        repeat(2){f.importer().scan(listOf(old,expired),device,target,{true},{opened++;f.bytes.inputStream()},{true});f.now+=31000}
        assertEquals(0,opened);assertTrue(f.box.tasks().isEmpty())
    }
    @Test fun `七天边界包含临界点拒绝过期和未来分钟文件名不冒充精确起点`() {
        val f=Fixture()
        assertTrue(systemRecordingWithinSevenDays(f.doc.copy(name="unknown.m4a",modifiedAt=f.now-7*86400000L),f.now))
        assertFalse(systemRecordingWithinSevenDays(f.doc.copy(name="unknown.m4a",modifiedAt=f.now-7*86400000L-1),f.now))
        assertFalse(systemRecordingWithinSevenDays(f.doc.copy(name="微信-测试-202610011200.m4a"),f.now))
        assertNull(systemRecordingStartTime("微信-测试-202609301200.m4a"))
    }
    @Test fun `连续两次稳定观察后只读导入重复扫描和重启不重复入队`()=io {
        val f=Fixture();val original=f.bytes.clone()
        f.importer().scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true})
        assertTrue(f.box.tasks().isEmpty())
        f.now+=31000
        f.importer().scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true})
        assertEquals(1,f.box.tasks().size)
        val task=f.box.tasks().single();assertNotNull(task.source);assertTrue(task.source!!.startTimeKnown)
        assertArrayEquals(original,f.box.audioFile(task.id).readBytes())
        f.importer().scan(listOf(f.doc),device,target,{true},{error("重复打开原件")},{true})
        assertEquals(1,f.box.tasks().size);assertArrayEquals(original,f.bytes)
    }
    @Test fun `保存回执后独立索引阻止被清理历史重复上传`()=io {
        val f=Fixture();val importer=f.importer()
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true})
        val task=f.box.tasks().single()
        f.box.acceptReceipt(task.id,CallReceipt(true,task.id,device,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z"));importer.markSaved(f.box.tasks().single());f.box.cleanup(task.id)
        File(f.root,"outbox/${task.id}.json").delete()
        f.importer().scan(listOf(f.doc),device,target,{true},{error("已上传原件不能再次打开")},{true})
        assertTrue(f.box.tasks().isEmpty())
    }
    @Test fun `旧电话分类仍待传的同一原件不能按微信再次入队`()=io {
        val f=Fixture();val importer=f.importer()
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true})
        val corrected=f.doc.copy(platform="wechat")
        importer.scan(listOf(corrected),device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        importer.scan(listOf(corrected),device,target,{true},{f.bytes.inputStream()},{true})
        assertEquals(1,f.box.tasks().size)
    }
    @Test fun `旧分类上传历史清理后同一原件不因平台修正再次上传`()=io {
        val f=Fixture();val importer=f.importer()
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true})
        val task=f.box.tasks().single()
        f.box.acceptReceipt(task.id,CallReceipt(true,task.id,device,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z"))
        importer.markSaved(f.box.tasks().single());f.box.cleanup(task.id)
        File(f.root,"outbox/${task.id}.json").delete()
        val corrected=f.doc.copy(platform="wechat")
        f.importer().scan(listOf(corrected),device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        f.importer().scan(listOf(corrected),device,target,{true},{f.bytes.inputStream()},{true})
        assertTrue(f.box.tasks().isEmpty())
    }
    @Test fun `复制期间变化或输入忙不发布不删除原件下次可恢复`()=io {
        val f=Fixture();val importer=f.importer()
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{false})
        assertTrue(f.box.tasks().isEmpty())
        importer.scan(listOf(f.doc),device,target,{false},{error("输入忙不读原件")},{true})
        assertTrue(f.box.tasks().isEmpty())
        f.importer().scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true})
        assertEquals(1,f.box.tasks().size)
    }
    @Test fun `文件增长重新稳定不上传半成品`()=io {
        val f=Fixture();val importer=f.importer()
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        val changed=f.doc.copy(modifiedAt=f.now,size=f.doc.size+1)
        importer.scan(listOf(changed),device,target,{true},{error("仍在写入")},{true})
        assertTrue(f.box.tasks().isEmpty())
    }
    @Test fun `仅明确年月日时分秒可用于去重拒绝无效日期和只有日期`() {
        assertNotNull(systemRecordingStartTime("电话_20260930_123045.m4a"))
        assertNotNull(systemRecordingStartTime("微信_2026-09-30_12-30-45.m4a"))
        assertNull(systemRecordingStartTime("电话_20260230_123045.m4a"))
        assertNull(systemRecordingStartTime("电话_20260930.m4a"))
        assertNull(systemRecordingStartTime("recording.m4a"))
    }
    @Test fun `坏音频不会耗尽私有录音队列容量且系统原件不变`()=io {
        val f=Fixture()
        val importer=SystemRecordingImporter(File(f.root,"index"),f.box,{null},{f.now})
        val documents=(1..9).map{f.doc.copy(uri="content://test/bad$it")}
        importer.scan(documents,device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        val result=importer.scan(documents,device,target,{true},{f.bytes.inputStream()},{true})
        assertEquals(9,result.errors)
        assertEquals(0,File(f.root,"outbox").listFiles().orEmpty().count{it.extension=="m4a"})
        assertTrue(f.box.createAudioFile().second.exists())
    }
    @Test fun `短暂读取故障继续等待系统原件恢复而不提前上传输入法版本`()=io {
        val f=Fixture();val importer=f.importer()
        importer.scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true});f.now+=31000
        val failed=importer.scan(listOf(f.doc),device,target,{true},{throw java.io.IOException("temporary")},{true})
        assertEquals(1,failed.waiting);assertEquals(1,failed.errors)
        assertEquals(0,File(f.root,"outbox").listFiles().orEmpty().count{it.extension=="m4a"})
        val recovered=f.importer().scan(listOf(f.doc),device,target,{true},{f.bytes.inputStream()},{true})
        assertEquals(1,recovered.imported);assertEquals(0,recovered.waiting)
    }
    @Test fun `崩溃留下的旧原件缓存被精准回收而未结束自录文件保留`()=io {
        val f=Fixture();val (id,file)=f.box.createAudioFile();file.writeText("partial system import")
        val index=File(f.root,"index").apply{mkdirs()}
        File(index,UUID.randomUUID().toString()+".json").writeText("""{"observedAt":1,"taskId":"$id","complete":false}""")
        val (_,liveRecording)=f.box.createAudioFile();liveRecording.writeText("ongoing IME recording")
        f.importer().scan(emptyList(),device,target,{true},{error("无外部文件")},{true})
        assertFalse(file.exists());assertTrue(liveRecording.exists())
    }
}
