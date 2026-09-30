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
class CallRecordingOutboxTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val root=File(context.noBackupFilesDir,"calls-"+UUID.randomUUID())
    private fun io(block:()->Unit) { val executor=Executors.newSingleThreadExecutor();try {executor.submit(block).get()} finally {executor.shutdownNow()} }
    private val device=UUID.randomUUID().toString()
    private fun enqueue(box:CallRecordingOutbox):CallTask {
        val (id,file)=box.createAudioFile()
        file.writeText("0000ftypM4A synthetic fixture")
        return box.enqueue(id,device,CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000))
    }
    private fun receipt(task:CallTask)=CallReceipt(true,task.id,task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
    @Test fun `错误回执不删文件而匹配回执先落盘再清理`()=io {
        val box=CallRecordingOutbox(root);val task=enqueue(box)
        assertFalse(box.acceptReceipt(task.id,receipt(task).copy(sha256="f".repeat(64))))
        assertFalse(box.acceptReceipt(task.id,receipt(task).copy(device_id=UUID.randomUUID().toString())))
        assertFalse(box.acceptReceipt(task.id,receipt(task).copy(stored=false)))
        assertFalse(box.acceptReceipt(task.id,receipt(task).copy(stored_at="")))
        assertTrue(box.audioFile(task.id).exists())
        assertTrue(box.acceptReceipt(task.id,receipt(task)))
        val recovered=CallRecordingOutbox(root)
        assertEquals("saved",recovered.tasks().single().uploadStatus)
        assertEquals("pending",recovered.tasks().single().cleanupStatus)
        assertTrue(recovered.cleanup(task.id))
        assertFalse(recovered.audioFile(task.id).exists())
        assertEquals("deleted",recovered.tasks().single().cleanupStatus)
    }
    @Test fun `清理失败保存回执重启后只重试清理`()=io {
        val box=CallRecordingOutbox(root,deleteAudio={false});val task=enqueue(box)
        assertTrue(box.acceptReceipt(task.id,receipt(task)))
        assertFalse(box.cleanup(task.id));assertTrue(box.audioFile(task.id).exists())
        assertEquals("pending",box.tasks().single().cleanupStatus)
        assertTrue(CallRecordingOutbox(root).cleanup(task.id))
    }
    @Test fun `本地容量满不删除旧文件并拒绝新文件`()=io {
        val box=CallRecordingOutbox(root,maxPendingBytes=10)
        val (_,file)=box.createAudioFile();file.writeBytes(ByteArray(10))
        assertThrows(IllegalStateException::class.java){box.createAudioFile()}
        assertEquals(10,file.length())
    }
    @Test fun `失败恢复待传且非法路径不能访问外部文件`()=io {
        val box=CallRecordingOutbox(root);val task=enqueue(box)
        box.markAttempt(task.id);box.fail(task.id,1000,"network_error")
        val restored=CallRecordingOutbox(root).tasks().single()
        assertEquals("failed",restored.uploadStatus);assertTrue(restored.nextAttemptAt>1000)
        assertTrue(box.audioFile(task.id).exists())
        assertThrows(IllegalArgumentException::class.java){box.audioFile("../outside")}
        assertFalse(box.cleanup(task.id))
    }
    @Test fun `禁止主线程执行队列文件操作`() {
        assertThrows(IllegalStateException::class.java){CallRecordingOutbox(root).tasks()}
    }
    @Test fun `坏清单隔离保留不能阻塞其他待传`()=io {
        val box=CallRecordingOutbox(root);val good=enqueue(box);val broken=UUID.randomUUID().toString()
        File(root,"$broken.json").writeText("not json")
        assertEquals(listOf(good.id),box.tasks().map{it.id})
        assertEquals(listOf(broken),box.invalidEntries())
        assertTrue(File(root,"$broken.json").exists())
    }
    @Test fun `已完成清单有界回收不永久耗尽队列`()=io {
        val box=CallRecordingOutbox(root,maxCompletedTasks=2)
        repeat(5){val task=enqueue(box);box.acceptReceipt(task.id,receipt(task));box.cleanup(task.id)}
        assertEquals(2,box.tasks().size)
        assertEquals(0,root.listFiles()!!.count{it.extension=="m4a"})
        enqueue(box)
    }
    @Test fun `超大清单不得写入不可读状态且保留音频`()=io {
        val box=CallRecordingOutbox(root);val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A fixture")
        assertThrows(IllegalArgumentException::class.java){box.enqueue(id,device,CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000,counterpart_display="x".repeat(17000)))}
        assertTrue(file.exists());assertTrue(box.tasks().isEmpty())
    }
    @Test fun `待传总量为新录音预留空间而非只看当前未满`()=io {
        val box=CallRecordingOutbox(root,maxPendingBytes=CallRecordingOutbox.MAX_AUDIO_BYTES*2)
        val(_,first)=box.createAudioFile();java.io.RandomAccessFile(first,"rw").use{it.setLength(CallRecordingOutbox.MAX_AUDIO_BYTES-1)}
        val(_,second)=box.createAudioFile();java.io.RandomAccessFile(second,"rw").use{it.setLength(CallRecordingOutbox.MAX_AUDIO_BYTES-1)}
        assertThrows(IllegalStateException::class.java){box.createAudioFile()}
        assertTrue(first.exists());assertTrue(second.exists())
    }
}
