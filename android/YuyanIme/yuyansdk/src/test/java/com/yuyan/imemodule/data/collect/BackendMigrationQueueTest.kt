package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.callrecording.*
import com.yuyan.imemodule.data.usage.*
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
class BackendMigrationQueueTest {
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val old="https://old.example.test"
    private val next="https://next.example.test"
    private val device=UUID.randomUUID().toString()
    private fun io(block:()->Unit) {val e=Executors.newSingleThreadExecutor();try{e.submit(block).get()}finally{e.shutdownNow()}}
    private fun metadata()=CallMetadata(destination=old,recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000)
    @Test fun `使用记录只迁移未确认投递且保留原始ID`()=io {
        UsageStore(app).use { store ->
            val row=UsageRecord(UUID.randomUUID().toString(),"usage","test.app","Test",1000,11000,"switch")
            val state=UsageState(cursor=11000)
            store.save(UsageReduction(state,listOf(row)),listOf(old))
            store.replaceTarget(old,next);store.replaceTarget(old,next)
            assertEquals(listOf(row.id),store.pending(next).map{it.id});assertTrue(store.pending(old).isEmpty())
            store.acknowledge(next,listOf(row.id));store.replaceTarget(old,next)
            assertTrue(store.pending(next).isEmpty())
        }
    }
    @Test fun `晚结束的旧目标录音发布时解析迁移且音频不复制`()=io {
        val root=File(app.noBackupFilesDir,UUID.randomUUID().toString());val box=CallRecordingOutbox(File(root,"outbox"))
        val sessions=CallRecordingSessions(File(root,"sessions"),box,inspectAudio={1000},resolveTarget={if(it==old)next else it})
        val task=sessions.begin(device,metadata());box.audioFile(task.id).writeText("0000ftypM4A fixture")
        sessions.finish(task,task.metadata,{true})
        assertEquals(next,box.tasks().single().metadata.destination)
        assertEquals(task.id,box.tasks().single().id);assertTrue(box.audioFile(task.id).exists())
    }
    @Test fun `迁移后先查询新地址真实回执且错误回执保留副本`()=io {
        val box=CallRecordingOutbox(File(app.noBackupFilesDir,UUID.randomUUID().toString()))
        val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A fixture")
        val task=box.enqueue(id,device,metadata());box.replaceTarget(device,old,next)
        var lookups=0
        val transport=object:CallTransport {
            override fun receipt(task:CallTask):CallReceipt? {assertEquals(next,task.metadata.destination);lookups++;return null}
            override fun upload(task:CallTask,file:File,allowed:()->Boolean)=CallReceipt(true,task.id,device,task.metadata.byte_size,"bad","2026-11-04T00:00:00Z")
        }
        CallRecordingUploader(box,transport,{true},{next}).runOnce()
        assertEquals(1,lookups);assertTrue(file.exists());assertEquals("invalid_receipt",box.tasks().single().lastError)
        assertEquals(task.metadata.sha256,box.tasks().single().metadata.sha256)
    }
    @Test fun `已确认历史不重传到新地址且不伪造新目标回执`()=io {
        val box=CallRecordingOutbox(File(app.noBackupFilesDir,UUID.randomUUID().toString()))
        val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A fixture")
        val task=box.enqueue(id,device,metadata())
        assertTrue(box.acceptReceipt(id,CallReceipt(true,id,device,task.metadata.byte_size,task.metadata.sha256,"2026-11-04T00:00:00Z")))
        box.replaceTarget(device,old,next)
        assertEquals(old,box.tasks().single().metadata.destination)
        assertTrue(box.hasSavedReceipt(box.tasks().single()))
    }
    @Test fun `系统导入按旧地址索引查重迁移不产生第二份任务`()=io {
        val root=File(app.noBackupFilesDir,UUID.randomUUID().toString());val box=CallRecordingOutbox(File(root,"outbox"))
        val bytes="0000ftypM4A fixture".toByteArray();var now=System.currentTimeMillis()
        val doc=SystemRecordingDocument("content://test/audio","Call.m4a",bytes.size.toLong(),now-60000,"phone")
        fun importer(previous:List<String> = emptyList())=SystemRecordingImporter(File(root,"index"),box,
            {RecordedSystemAudio(1000,"audio/mp4")},{now},previousTargets={previous})
        importer().scan(listOf(doc),device,old,{true},{bytes.inputStream()},{true})
        now+=31000
        assertEquals(1,importer().scan(listOf(doc),device,old,{true},{bytes.inputStream()},{true}).imported)
        val task=box.tasks().single();box.replaceTarget(device,old,next)
        assertEquals(0,importer(listOf(old)).scan(listOf(doc),device,next,{true},{error("不应重复读原件")},{true}).imported)
        assertEquals(listOf(task.id),box.tasks().map{it.id})
        val moved=box.tasks().single()
        assertTrue(box.acceptReceipt(task.id,CallReceipt(true,task.id,device,moved.metadata.byte_size,moved.metadata.sha256,"2026-11-04T00:00:00Z")))
        importer(listOf(old)).markSaved(box.tasks().single());box.cleanup(task.id)
        assertEquals(0,importer(listOf(old)).scan(listOf(doc),device,next,{true},{error("已完成索引不重读")},{true}).imported)
    }
    @Test fun `使用证据迁移不以旧端快照覆盖新端更新证据`()=io {
        val t=1800000000000L
        UsageStore(app).use {store ->
            store.save(UsageReduction(UsageState(cursor=t-100),emptyList()),listOf(old))
            store.save(UsageReduction(UsageState(cursor=t),emptyList()),listOf(old),listOf(UsageEvent(t-1,"resume","test.app")))
            assertNotNull(store.pendingEvidence(old))
            store.save(UsageReduction(UsageState(cursor=t+100),emptyList()),listOf(next),listOf(UsageEvent(t+1,"off")))
            val newer=store.pendingEvidence(next)!!
            store.replaceTarget(old,next)
            assertEquals(newer.version,store.pendingEvidence(next)!!.version)
            assertEquals(t+1,store.pendingEvidence(next)!!.screenOff!!.atMs)
            assertNull(store.pendingEvidence(old))
        }
    }
}
