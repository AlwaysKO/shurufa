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
class CallRecordingSessionsTest {
    private fun io(block:()->Unit){val e=Executors.newSingleThreadExecutor();try{e.submit(block).get()}finally{e.shutdownNow()}}
    @Test fun `自录按原六十四MiB预留且重建队列后仍遵守总容量`()=io {
        val context=ApplicationProvider.getApplicationContext<Context>();val root=File(context.noBackupFilesDir,UUID.randomUUID().toString())
        val audioRoot=File(root,"outbox");val sessionsRoot=File(root,"sessions");val device=UUID.randomUUID().toString()
        val metadata=CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=1000,audio_duration_ms=0)
        repeat(8) {
            val sessions=CallRecordingSessions(sessionsRoot,CallRecordingOutbox(audioRoot))
            sessions.begin(device,metadata)
        }
        val sessions=CallRecordingSessions(sessionsRoot,CallRecordingOutbox(audioRoot))
        assertEquals(8,sessions.pendingCount())
        assertThrows(IllegalStateException::class.java){sessions.begin(device,metadata)}
        assertEquals(8,audioRoot.listFiles()!!.count{it.extension=="m4a"})
    }
    @Test fun `输入忙时保留已结束日志后续恢复入队且不重复`()=io {
        val context=ApplicationProvider.getApplicationContext<Context>();val root=File(context.noBackupFilesDir,UUID.randomUUID().toString())
        val box=CallRecordingOutbox(File(root,"outbox"));val sessions=CallRecordingSessions(File(root,"sessions"),box,inspectAudio={1000})
        val task=sessions.begin(UUID.randomUUID().toString(),CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=1000,audio_duration_ms=0))
        box.audioFile(task.id).writeText("0000ftypM4A fixture")
        sessions.finish(task,task.metadata.copy(recording_ended_at=2000,audio_duration_ms=1000),{false})
        assertEquals(1,sessions.pendingCount());assertTrue(box.tasks().isEmpty())
        sessions.recover({true},null);sessions.recover({true},null)
        assertEquals(1,box.tasks().size);assertEquals(0,sessions.pendingCount());assertTrue(box.audioFile(task.id).exists())
    }
    @Test fun `尚在录音和无法解析的中断文件均不上传不删`()=io {
        val context=ApplicationProvider.getApplicationContext<Context>();val root=File(context.noBackupFilesDir,UUID.randomUUID().toString())
        val box=CallRecordingOutbox(File(root,"outbox"));val sessions=CallRecordingSessions(File(root,"sessions"),box)
        val task=sessions.begin(UUID.randomUUID().toString(),CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=1000,audio_duration_ms=0))
        sessions.recover({true},task.id);sessions.recover({true},null)
        assertTrue(box.tasks().isEmpty());assertTrue(box.audioFile(task.id).exists());assertEquals(1,sessions.pendingCount())
    }
    @Test fun `录音停止失败且容器不可解析时不得进入可上传队列`()=io {
        val context=ApplicationProvider.getApplicationContext<Context>();val root=File(context.noBackupFilesDir,UUID.randomUUID().toString())
        val box=CallRecordingOutbox(File(root,"outbox"));val sessions=CallRecordingSessions(File(root,"sessions"),box,inspectAudio={null})
        val task=sessions.begin(UUID.randomUUID().toString(),CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=1000,audio_duration_ms=0))
        box.audioFile(task.id).writeText("0000ftypM4A incomplete container")
        sessions.finish(task,task.metadata.copy(recording_ended_at=2000,audio_duration_ms=1000,failure_reason="recording_interrupted"),{true})
        sessions.recover({true},null)
        assertTrue(box.tasks().isEmpty());assertTrue(box.audioFile(task.id).exists());assertEquals(1,sessions.pendingCount())
    }
}
