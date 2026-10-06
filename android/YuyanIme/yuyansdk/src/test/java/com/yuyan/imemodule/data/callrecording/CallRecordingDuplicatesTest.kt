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
class CallRecordingDuplicatesTest {
    private val device=UUID.randomUUID().toString()
    private val target="https://example.test"
    private fun io(block:()->Unit){val e=Executors.newSingleThreadExecutor();try{e.submit(block).get()}finally{e.shutdownNow()}}
    private fun box()=CallRecordingOutbox(File(ApplicationProvider.getApplicationContext<Context>().noBackupFilesDir,UUID.randomUUID().toString()))
    private fun add(b:CallRecordingOutbox,system:Boolean=false,start:Long=100000,end:Long=160000,platform:String="phone",known:Boolean=true,bytes:String?=null):CallTask {
        val(id,file)=b.createAudioFile();file.writeText(bytes ?: if(system)"system audio"else"ime audio")
        return b.enqueue(id,device,CallMetadata(platform=platform,destination=target,recording_started_at=start,recording_ended_at=end,audio_duration_ms=end-start),
            source=if(system)SystemRecordingSource("content://test/$id",file.length(),end,known) else null)
    }
    private class Transport:CallTransport {
        val uploaded=mutableListOf<String>();var fail=false
        override fun receipt(task:CallTask):CallReceipt?=null
        override fun upload(task:CallTask,file:File,allowed:()->Boolean):CallReceipt {
            if(fail)throw java.io.IOException()
            uploaded.add(task.id)
            return CallReceipt(true,task.id,task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
        }
    }
    @Test fun `同次通话优先系统且收到回执后清理输入法副本`()=io {
        val b=box();val local=add(b);val system=add(b,true);val t=Transport()
        CallRecordingUploader(b,t,{true},{target},systemSourceExists={true}).runOnce()
        assertEquals(listOf(system.id),t.uploaded)
        assertFalse(b.audioFile(local.id).exists());assertFalse(b.audioFile(system.id).exists())
        assertEquals("superseded",b.tasks().first{it.id==local.id}.uploadStatus)
    }
    @Test fun `系统上传失败保留两份队列音频重启后只补传系统`()=io {
        val b=box();val local=add(b);val system=add(b,true);val t=Transport();t.fail=true
        CallRecordingUploader(b,t,{true},{target},now={200000},systemSourceExists={true}).runOnce()
        assertTrue(b.audioFile(local.id).exists());assertTrue(b.audioFile(system.id).exists())
        t.fail=false
        CallRecordingUploader(b,t,{true},{target},now={1000000},systemSourceExists={true}).runOnce()
        assertEquals(listOf(system.id),t.uploaded);assertFalse(b.audioFile(local.id).exists())
    }
    @Test fun `系统原件不可访问不清理或吞掉输入法录音`()=io {
        val b=box();val local=add(b);val system=add(b,true);val t=Transport()
        CallRecordingUploader(b,t,{true},{target},systemSourceExists={false}).runOnce()
        assertEquals(setOf(system.id,local.id),t.uploaded.toSet())
        assertEquals("saved",b.tasks().first{it.id==local.id}.uploadStatus)
    }
    @Test fun `模糊时间部分录音跨平台和多候选不判重复`()=io {
        for(kind in listOf("unknown","partial","platform","ambiguous")) {
            val b=box();val local=add(b)
            add(b,true,start=if(kind=="partial")110000 else 100000,platform=if(kind=="platform")"wechat"else"phone",known=kind!="unknown")
            if(kind=="ambiguous")add(b,true,bytes="other system audio")
            assertNull(CallRecordingDuplicates.preferredSystem(local,b.tasks()))
        }
    }
    @Test fun `同一原件经媒体库和目录重复发现仍优先系统录音`()=io {
        val b=box();val local=add(b);val media=add(b,true);val directory=add(b,true)
        assertNotEquals(media.source!!.uri,directory.source!!.uri)
        assertEquals(media.metadata.sha256,directory.metadata.sha256)
        val preferred=CallRecordingDuplicates.preferredSystem(local,listOf(local,media,directory))
        assertNotNull(preferred);assertTrue(preferred!!.id in setOf(media.id,directory.id))
        // 只有可信的相同内容才合并来源；缺少指纹、不同字节或独立本地片段仍然保守。
        assertNull(CallRecordingDuplicates.preferredSystem(local,listOf(local,
            media.copy(metadata=media.metadata.copy(sha256="")),directory.copy(metadata=directory.metadata.copy(sha256="")))))
        assertNull(CallRecordingDuplicates.preferredSystem(local,listOf(local,media,
            directory.copy(metadata=directory.metadata.copy(sha256="f".repeat(64))))))
        val otherLocal=add(b)
        assertNull(CallRecordingDuplicates.preferredSystem(local,listOf(local,otherLocal,media,directory)))
    }
    @Test fun `媒体库目录双来源仅上传一份系统音频并清理输入法副本`()=io {
        val b=box();val local=add(b);val media=add(b,true);val directory=add(b,true)
        val saved=mutableMapOf<String,CallReceipt>();val uploaded=mutableListOf<String>()
        val transport=object:CallTransport {
            override fun receipt(task:CallTask):CallReceipt?=null
            override fun existing(task:CallTask):CallReceipt?=saved[task.metadata.sha256]
            override fun upload(task:CallTask,file:File,allowed:()->Boolean):CallReceipt {
                uploaded.add(task.id)
                return CallReceipt(true,task.id,task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
                    .also{saved[task.metadata.sha256]=it}
            }
        }
        CallRecordingUploader(b,transport,{true},{target},systemSourceExists={true}).runOnce()
        assertEquals(1,uploaded.size);assertTrue(uploaded.single() in setOf(media.id,directory.id))
        assertEquals("superseded",b.tasks().single{it.id==local.id}.uploadStatus)
        assertFalse(b.audioFile(local.id).exists())
    }
    @Test fun `媒体URI撤权时使用同内容可读目录且全撤权不误删输入法录音`()=io {
        for(directoryReadable in listOf(true,false)) {
            val b=box();val local=add(b);val systems=listOf(add(b,true),add(b,true)).sortedBy{it.id}
            val media=systems.first();val directory=systems.last()
            val saved=mutableMapOf<String,CallReceipt>();val uploaded=mutableListOf<String>()
            val transport=object:CallTransport {
                override fun receipt(task:CallTask):CallReceipt?=null
                override fun existing(task:CallTask):CallReceipt?=saved[task.metadata.sha256]
                override fun upload(task:CallTask,file:File,allowed:()->Boolean):CallReceipt {
                    uploaded.add(task.id)
                    return CallReceipt(true,task.id,task.deviceId,task.metadata.byte_size,task.metadata.sha256,"2026-09-30T00:00:00Z")
                        .also{saved[task.metadata.sha256]=it}
                }
            }
            CallRecordingUploader(b,transport,{true},{target},
                systemSourceExists={directoryReadable && it.uri==directory.source!!.uri}).runOnce()
            if(directoryReadable) {
                assertEquals(1,uploaded.size);assertTrue(uploaded.single() in setOf(media.id,directory.id))
                assertEquals("superseded",b.tasks().single{it.id==local.id}.uploadStatus)
            }else {
                assertTrue(local.id in uploaded)
                assertEquals("saved",b.tasks().single{it.id==local.id}.uploadStatus)
            }
        }
    }
    @Test fun `不同设备目的地和已经尝试上传的本地录音不合并`()=io {
        val b=box();val local=add(b);val system=add(b,true)
        assertNull(CallRecordingDuplicates.preferredSystem(local,listOf(local,system.copy(deviceId=UUID.randomUUID().toString()))))
        assertNull(CallRecordingDuplicates.preferredSystem(local,listOf(local,system.copy(metadata=system.metadata.copy(destination="https://other.test")))))
        assertNull(CallRecordingDuplicates.preferredSystem(local.copy(attempts=1),listOf(local,system)))
    }
    @Test fun `错误系统回执不允许删除本地副本`()=io {
        val b=box();val local=add(b);add(b,true)
        val t=object:CallTransport {
            override fun receipt(task:CallTask):CallReceipt?=null
            override fun upload(task:CallTask,file:File,allowed:()->Boolean)=CallReceipt(true,task.id,task.deviceId,0,"f".repeat(64),"2026-09-30T00:00:00Z")
        }
        CallRecordingUploader(b,t,{true},{target},systemSourceExists={true}).runOnce()
        assertTrue(b.audioFile(local.id).exists())
    }
    @Test fun `配置系统目录后等待文件落盘扫描失败不抢先上传输入法版本`()=io {
        val b=box();val local=add(b);val system=add(b,true)
        assertFalse(systemRecordingUploadReady(local,true,true,170000))
        assertFalse(systemRecordingUploadReady(local,true,false,400000))
        assertTrue(systemRecordingUploadReady(local,true,false,900000))
        assertTrue(systemRecordingUploadReady(local,true,true,900000))
        assertTrue(systemRecordingUploadReady(local,false,false,170000))
        assertTrue(systemRecordingUploadReady(system,true,false,170000))
    }
}
