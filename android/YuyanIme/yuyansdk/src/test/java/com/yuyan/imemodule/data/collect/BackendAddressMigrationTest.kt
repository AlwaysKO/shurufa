package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.calllog.PhoneCallLogRuntime
import com.yuyan.imemodule.data.callrecording.*
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class BackendAddressMigrationTest {
    private val app=ApplicationProvider.getApplicationContext<Context>()
    private val old="https://old.example.test"
    private val next="https://new.example.test"
    private lateinit var device:String
    @Before fun setup() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().putString("online_server_url",old).commit()
        app.getSharedPreferences("backend_address_migration_v1",0).edit().clear().commit()
        CallRecordingRuntime.preferences(app).edit().clear().commit()
        PhoneCallLogRuntime.preferences(app).edit().clear().commit()
        ServerConfig::class.java.getDeclaredField("prefs").apply{isAccessible=true}.set(null,null)
        ImageUploadRuntime.finishInput()
        org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(5))
        ServerConfig.init(app);device=DataCollector.deviceId(app)
    }
    @After fun cleanup() {
        CallRecordingJobService.cancel(app)
        com.yuyan.imemodule.data.calllog.PhoneCallLogJobService.cancel(app)
        UploadRecovery.cancel(app)
        ServerConfig::class.java.getDeclaredField("prefs").apply{isAccessible=true}.set(null,null)
    }
    private fun io(block:()->Unit) { val pool=Executors.newSingleThreadExecutor();try{pool.submit(block).get()}finally{pool.shutdownNow()} }
    private fun move() { ServerConfig.updateOnlineServerUrl(next);BackendAddressMigration.recover(app) }
    @Test fun `已有授权跟随迁移且关闭项保持关闭`()=io {
        val audio=CallRecordingRuntime.consent(app)
        audio.grant(device,old,record=false,upload=true)
        val calls=PhoneCallLogRuntime.consent(app);calls.grant(device,old,calls.revision)
        val revision=audio.revision
        move()
        assertTrue(audio.uploadAllowed(device,next));assertFalse(audio.recordingAllowed(device,next))
        assertTrue(calls.allowed(device,next));assertFalse(calls.allowed(device,old))
        assertTrue(audio.revision>revision)
        val applied=audio.revision
        BackendAddressMigration.recover(app)
        assertEquals(applied,audio.revision)
    }
    @Test fun `未授权设备与已撤回开关不能被迁移开启`()=io {
        val audio=CallRecordingRuntime.consent(app)
        audio.grant(UUID.randomUUID().toString(),old,true,true)
        move()
        assertEquals(old,audio.destination);assertFalse(audio.uploadAllowed(device,next))
        audio.revokeAll()
        BackendAddressMigration.recover(app)
        assertFalse(audio.wantsUpload);assertFalse(audio.wantsRecording)
    }
    @Test fun `待传保留任务身份和音频并且旧回执不能清理新目标`()=io {
        val box=CallRecordingRuntime.outbox(app)
        val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A fixture")
        val task=box.enqueue(id,device,CallMetadata(destination=old,recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000))
        box.markAttempt(id);box.fail(id,1000,"network_timeout")
        move()
        val moved=CallRecordingRuntime.outbox(app).tasks().single{it.id==id}
        assertEquals(next,moved.metadata.destination);assertEquals(task.metadata.sha256,moved.metadata.sha256)
        assertEquals(task.metadata.byte_size,file.length());assertEquals(1,moved.attempts)
        assertNull(moved.receipt);assertFalse(box.cleanup(id));assertEquals(0L,moved.nextAttemptAt)
        assertTrue(file.exists())
        BackendAddressMigration.recover(app)
        assertEquals(1,box.tasks().count{it.id==id})
    }
    @Test fun `地址已保存但尚未迁移时重启能够恢复`()=io {
        val audio=CallRecordingRuntime.consent(app);audio.grantCombined(device,old)
        ServerConfig.updateOnlineServerUrl(next)
        assertEquals(old,audio.destination)
        BackendAddressMigration.recover(app)
        assertTrue(CallRecordingRuntime.consent(app).expandedRecordingAllowed(device,next))
    }
    @Test fun `无效下发不迁移地址和授权`()=io {
        val audio=CallRecordingRuntime.consent(app);audio.grantCombined(device,old)
        assertNull(ServerConfig.updateOnlineServerUrl("http://unsafe.example"))
        BackendAddressMigration.recover(app)
        assertEquals(old,ServerConfig.baseUrl);assertTrue(audio.uploadAllowed(device,old))
    }
    @Test fun `连续切换与回切按日志顺序恢复而非地址循环`()=io {
        val audio=CallRecordingRuntime.consent(app);audio.grantCombined(device,old)
        ServerConfig.updateOnlineServerUrl(next)
        ServerConfig.updateOnlineServerUrl("https://third.example.test")
        ServerConfig.updateOnlineServerUrl(old)
        BackendAddressMigration.recover(app)
        assertTrue(audio.uploadAllowed(device,old))
        assertEquals(old,BackendAddressMigration.resolveTarget(app,next))
        assertEquals(old,BackendAddressMigration.resolveTarget(app,"https://third.example.test"))
        val revision=audio.revision
        BackendAddressMigration.recover(app)
        assertEquals(revision,audio.revision)
    }
    @Test fun `没有迁移待办时输入忙不改变已有上传资格`()=io {
        ImageUploadRuntime.noteKeyActivity()
        try { assertTrue(BackendAddressMigration.recover(app)) }
        finally { ImageUploadRuntime.finishInput() }
    }
    @Test fun `迁移完成后旧快照迟到入队仍绑定当前目标`()=io {
        move()
        val store=LocalInputStore(app)
        try {
            store.enqueueReport(PendingReport("late-report","input_session","{}"),listOf(old))
            assertFalse(store.reportTargets().contains(old));assertTrue(store.reportTargets().contains(next))
        } finally {store.close()}
        val box=CallRecordingRuntime.outbox(app)
        val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A fixture")
        box.enqueue(id,device,CallMetadata(destination=old,recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000))
        assertEquals(next,box.tasks().single{it.id==id}.metadata.destination)
    }
    @Test fun `旧目标真实回执可清理原副本及同次重复但不能伪造新回执`()=io {
        val box=CallRecordingRuntime.outbox(app)
        fun task(system:Boolean):CallTask {
            val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A same fixture")
            return box.enqueue(id,device,CallMetadata(destination=old,platform="wechat",recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000),
                source=if(system)SystemRecordingSource("content://test/audio",file.length(),2000,true)else null)
        }
        val system=task(true);val local=task(false)
        box.linkDuplicate(local.id,system.id)
        assertTrue(box.acceptReceipt(system.id,CallReceipt(true,system.id,device,system.metadata.byte_size,system.metadata.sha256,"2026-11-04T00:00:00Z")))
        move()
        assertTrue(box.cleanupDuplicate(local.id,system.id))
        CallRecordingUploader(box,object:CallTransport {
            override fun receipt(task:CallTask):CallReceipt?=error("已保存不得重新查询或上传")
            override fun upload(task:CallTask,file:java.io.File,allowed:()->Boolean):CallReceipt=error("不得重传")
        },{true},{next}).runOnce()
        assertFalse(box.audioFile(system.id).exists())
        assertNull(box.tasks().single{it.id==local.id}.receipt)
        assertEquals(old,box.tasks().single{it.id==system.id}.metadata.destination)
    }
    @Test fun `升级前地址已切换但旧授权没迁移也能补齐恢复`()=io {
        val audio=CallRecordingRuntime.consent(app);audio.grantCombined(device,old)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putString("online_server_url",next).commit()
        BackendAddressMigration.recover(app)
        assertTrue(audio.uploadAllowed(device,next));assertFalse(audio.needsCombinedConfirmation(device,next))
    }
    @Test fun `进度已落盘而通知中断时下次仍恢复待传Job`()=io {
        val audio=CallRecordingRuntime.consent(app);audio.grantCombined(device,next)
        ServerConfig.updateOnlineServerUrl(next)
        app.getSharedPreferences("backend_address_migration_v1",0).edit().putInt("applied",1).putBoolean("notify",true).commit()
        BackendAddressMigration.recover(app)
        val jobs=(app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as android.app.job.JobScheduler).allPendingJobs
        assertTrue(jobs.any{!it.isPeriodic && it.service.className.endsWith("CallRecordingJobService")})
    }
    @Test fun `数据库繁忙时不能占用全局地址锁阻塞其他模块`() {
        val locked=java.util.concurrent.CountDownLatch(1)
        val release=java.util.concurrent.CountDownLatch(1)
        val started=java.util.concurrent.CountDownLatch(1)
        val pool=Executors.newFixedThreadPool(3)
        val first=LocalInputStore(app);val second=LocalInputStore(app)
        val owner=pool.submit {
            first.writableDatabase.beginTransaction()
            try {locked.countDown();release.await()}finally{first.writableDatabase.endTransaction()}
        }
        assertTrue(locked.await(5,java.util.concurrent.TimeUnit.SECONDS))
        val writer=pool.submit {started.countDown();second.enqueueReport(PendingReport("busy-report","input_session","{}"),listOf(old))}
        assertTrue(started.await(5,java.util.concurrent.TimeUnit.SECONDS))
        Thread.sleep(200)
        val reader=pool.submit<Long>{ServerConfig.onlineEpoch}
        var unblocked=true
        try {reader.get(1,java.util.concurrent.TimeUnit.SECONDS)}catch(_:java.util.concurrent.TimeoutException){unblocked=false}
        finally {
            release.countDown();owner.get(5,java.util.concurrent.TimeUnit.SECONDS);writer.get(5,java.util.concurrent.TimeUnit.SECONDS)
            reader.get(5,java.util.concurrent.TimeUnit.SECONDS);first.close();second.close();pool.shutdownNow()
        }
        assertTrue("数据库写入不得持有全局配置锁",unblocked)
    }
    @Test fun `地址已发布但迁移未结束不应打断已授权录音`()=io {
        val audio=CallRecordingRuntime.consent(app);audio.grantCombined(device,old)
        ServerConfig.updateOnlineServerUrl(next)
        assertTrue(audio.expandedRecordingAllowed(device,next))
        assertEquals(old,audio.destination)
    }
    @Test fun `没有后台下发地址时不能把旧授权迁到打包默认地址`()=io {
        val audio=CallRecordingRuntime.consent(app);audio.grantCombined(device,old)
        PreferenceManager.getDefaultSharedPreferences(app).edit().remove("online_server_url").commit()
        BackendAddressMigration.recover(app)
        assertEquals(old,audio.destination)
        assertFalse(audio.uploadAllowed(device,ServerConfig.baseUrl))
    }
}
