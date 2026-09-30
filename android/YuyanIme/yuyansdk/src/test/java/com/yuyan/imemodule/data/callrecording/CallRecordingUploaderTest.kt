package com.yuyan.imemodule.data.callrecording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingUploaderTest {
    private fun io(block:()->Unit) {val executor=Executors.newSingleThreadExecutor();try{executor.submit(block).get()}finally{executor.shutdownNow()}}
    private fun box():CallRecordingOutbox {
        val context=ApplicationProvider.getApplicationContext<Context>()
        return CallRecordingOutbox(File(context.noBackupFilesDir,"calls-"+UUID.randomUUID()))
    }
    private fun enqueue(box:CallRecordingOutbox):CallTask {
        val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A synthetic fixture")
        return box.enqueue(id,UUID.randomUUID().toString(),CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000))
    }
    private class Transport:CallTransport {
        var existing:CallReceipt?=null;var lookups=0
        override fun existing(task:CallTask):CallReceipt? {lookups++;return existing}
        var queries=0;var uploads=0;var saved:CallReceipt?=null;var error=false;var revoke:(()->Unit)?=null
        override fun receipt(task:CallTask):CallReceipt? {queries++;if(error)throw IOException();return saved}
        override fun upload(task:CallTask,file:File,allowed:()->Boolean):CallReceipt {
            uploads++;revoke?.invoke();if(!allowed())throw IOException()
            return saved ?: CallReceipt(true,task.id,task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
        }
    }
    @Test fun `不同本地ID同内容后台已保存时不上传并保存原回执供恢复清理`()=io {
        val box=box();val task=enqueue(box);val network=Transport()
        val originalId=UUID.randomUUID().toString()
        network.existing=CallReceipt(true,originalId,task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
        assertFalse(box.acceptReceipt(task.id,network.existing!!))
        CallRecordingUploader(box,network,{true},{"https://example.test"}).runOnce()
        assertEquals(0,network.uploads);assertEquals(1,network.lookups)
        assertEquals(originalId,box.tasks().single().receipt!!.record_id)
        assertFalse(box.audioFile(task.id).exists())
    }
    @Test fun `内容查询回执属于别的设备或内容不匹配时不上传不删除`()=io {
        for(kind in 0..3){
            val box=box();val task=enqueue(box);val network=Transport()
            val good=CallReceipt(true,UUID.randomUUID().toString(),task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
            network.existing=when(kind){0->good.copy(device_id=UUID.randomUUID().toString());1->good.copy(sha256="f".repeat(64));2->good.copy(byte_size=1);else->good.copy(record_id="invalid")}
            CallRecordingUploader(box,network,{true},{"https://example.test"}).runOnce()
            assertEquals(0,network.uploads);assertTrue(box.audioFile(task.id).exists())
            assertEquals("failed",box.tasks().single().uploadStatus)
        }
    }
    @Test fun `撤权输入忙或目标变化时不联网不删文件`()=io {
        val box=box();val task=enqueue(box);val network=Transport()
        CallRecordingUploader(box,network,{false},{"https://example.test"}).runOnce()
        CallRecordingUploader(box,network,{true},{"https://other.test"}).runOnce()
        assertEquals(0,network.queries);assertEquals(0,network.uploads);assertTrue(box.audioFile(task.id).exists())
        assertEquals("paused",box.tasks().single().uploadStatus)
    }
    @Test fun `超时后查原回执无需重复上传且成功才删文件`()=io {
        val box=box();val task=enqueue(box);val network=Transport()
        network.saved=CallReceipt(true,task.id,task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
        CallRecordingUploader(box,network,{true},{"https://example.test"}).runOnce()
        assertEquals(1,network.queries);assertEquals(0,network.uploads)
        assertFalse(box.audioFile(task.id).exists());assertEquals("deleted",box.tasks().single().cleanupStatus)
        CallRecordingUploader(box,network,{true},{"https://example.test"}).runOnce()
        assertEquals(1,network.queries)
    }
    @Test fun `网络错误或错误回执不删除且退避重试`()=io {
        val box=box();val task=enqueue(box);val network=Transport();network.error=true
        val uploader=CallRecordingUploader(box,network,{true},{"https://example.test"},now={1000})
        uploader.runOnce();uploader.runOnce();assertEquals(1,network.queries);assertTrue(box.audioFile(task.id).exists())
        network.error=false;network.saved=CallReceipt(true,task.id,task.deviceId,0,"f".repeat(64),"2026-09-30T00:00:00Z")
        CallRecordingUploader(box,network,{true},{"https://example.test"},now={100000}).runOnce()
        assertTrue(box.audioFile(task.id).exists());assertEquals("failed",box.tasks().single().uploadStatus)
    }
    @Test fun `传输期间撤权不确认不清理`()=io {
        val box=box();val task=enqueue(box);val network=Transport();var allowed=true
        network.revoke={allowed=false}
        CallRecordingUploader(box,network,{allowed},{"https://example.test"}).runOnce()
        assertEquals(1,network.uploads);assertTrue(box.audioFile(task.id).exists())
    }
    @Test fun `网络等待不持有录音创建入队的锁`()=io {
        val box=box();enqueue(box)
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val transport=object:CallTransport {
            override fun receipt(task:CallTask):CallReceipt? {entered.countDown();release.await();throw IOException()}
            override fun upload(task:CallTask,file:File,allowed:()->Boolean):CallReceipt=throw IOException()
        }
        val executor=Executors.newFixedThreadPool(2)
        try {
            val upload=executor.submit{CallRecordingUploader(box,transport,{true},{"https://example.test"}).runOnce()}
            assertTrue(entered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val create=executor.submit<Pair<String,File>>{box.createAudioFile()}
            assertTrue(create.get(2,java.util.concurrent.TimeUnit.SECONDS).second.exists())
            release.countDown();upload.get()
        }finally{release.countDown();executor.shutdownNow()}
    }
    @Test fun `一个清理抛异常不能阻断后续健康录音`()=io {
        val context=ApplicationProvider.getApplicationContext<Context>();var brokenId=""
        val box=CallRecordingOutbox(File(context.noBackupFilesDir,"calls-"+UUID.randomUUID()),deleteAudio={if(it.nameWithoutExtension==brokenId)throw IOException() else it.delete()})
        enqueue(box);enqueue(box);val tasks=box.tasks();val broken=tasks.first();val normal=tasks.last();brokenId=broken.id
        box.acceptReceipt(broken.id,CallReceipt(true,broken.id,broken.deviceId,broken.metadata.byte_size,broken.metadata.sha256,"2026-09-30T00:00:00Z"))
        val network=Transport()
        CallRecordingUploader(box,network,{true},{"https://example.test"}).runOnce()
        assertEquals(1,network.uploads);assertTrue(box.audioFile(broken.id).exists());assertFalse(box.audioFile(normal.id).exists())
    }
}
