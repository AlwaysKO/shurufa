package com.yuyan.imemodule.data.callrecording

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingRuntimePathTest {
    private val base=ApplicationProvider.getApplicationContext<Context>()
    private fun io(block:()->Unit) { val executor=Executors.newSingleThreadExecutor();try {executor.submit(block).get()} finally {executor.shutdownNow()} }
    private fun context(directory:File)=object:ContextWrapper(base) {
        override fun getNoBackupFilesDir()=directory
    }
    @Test fun `系统提供的私有根目录别名可建立录音队列和会话`()=io {
        val root=Files.createTempDirectory("call-runtime").toFile().canonicalFile
        val actual=File(root,"actual").apply{mkdirs()}
        val alias=File(root,"alias")
        Files.createSymbolicLink(alias.toPath(),actual.toPath())
        val ctx=context(alias)
        val box=CallRecordingRuntime.outbox(ctx)
        assertTrue(box.tasks().isEmpty())
        val task=CallRecordingRuntime.sessions(ctx).begin(UUID.randomUUID().toString(),CallMetadata(destination="https://example.test",recording_started_at=1000,recording_ended_at=1000,audio_duration_ms=0))
        assertTrue(File(actual,"call_audio/outbox/${task.id}.m4a").isFile)
        assertEquals(1,CallRecordingRuntime.sessions(ctx).pendingCount())
    }
    @Test fun `应用子目录和录音文件的符号链接仍被拒绝`()=io {
        val root=Files.createTempDirectory("call-runtime-guard").toFile().canonicalFile
        val outside=File(root,"outside").apply{mkdirs()}
        val privateRoot=File(root,"private").apply{mkdirs()}
        Files.createSymbolicLink(File(privateRoot,"call_audio").toPath(),outside.toPath())
        assertThrows(IllegalArgumentException::class.java){CallRecordingRuntime.outbox(context(privateRoot)).tasks()}
        val safe=File(root,"safe").apply{mkdirs()}
        val box=CallRecordingRuntime.outbox(context(safe))
        assertTrue(box.tasks().isEmpty())
        val id=UUID.randomUUID().toString()
        val original=File(outside,"original.m4a").apply{writeText("preserve")}
        Files.createSymbolicLink(File(safe,"call_audio/outbox/$id.m4a").toPath(),original.toPath())
        assertThrows(IllegalArgumentException::class.java){box.audioFile(id)}
        assertEquals("preserve",original.readText())
    }
}
