package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.Looper
import android.provider.MediaStore
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingUploadRecoveryTest {
    private val context=ApplicationProvider.getApplicationContext<Application>()
    private val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private fun <T> io(block:()->T):T {
        val executor=Executors.newSingleThreadExecutor()
        try{return executor.submit<T>{block()}.get()}finally{executor.shutdownNow()}
    }
    @Before fun setup() {
        CallRecordingJobService.cancel(context)
        CallRecordingRuntime.preferences(context).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(CollectionConsent.KEY,true).commit()
        ServerConfig.init(context)
        io{CallRecordingRuntime.consent(context).grant(DataCollector.deviceId(context),ServerConfig.baseUrl,false,true)}
        // Robolectric resets elapsedRealtime between tests; reset the singleton gate's resume deadline too.
        GameWorkRuntime.setGaming(true);GameWorkRuntime.setGaming(false)
        ImageUploadRuntime.finishInput()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        network()
    }
    @After fun cleanup(){CallRecordingJobService.cancel(context);GameWorkRuntime.setGaming(false)}
    private fun network(wifi:Boolean=true) {
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if(wifi)ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE,0,true,true))
        shadowOf(manager).setDefaultNetworkActive(true)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(if(wifi)NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork,caps)
    }
    private fun pending():CallTask {
        val box=CallRecordingRuntime.outbox(context)
        val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A retry fixture")
        val now=System.currentTimeMillis()
        return box.enqueue(id,DataCollector.deviceId(context),CallMetadata(platform="wechat",destination=ServerConfig.baseUrl,
            recording_started_at=now-60_000,recording_ended_at=now-30_000,audio_duration_ms=30_000),
            source=SystemRecordingSource("content://media/external/audio/media/1",file.length(),now-30_000,false))
    }
    @Test fun `仅上传授权也监听微信语音视频结束并立即预约而不启动自录服务`() {
        ImageUploadRuntime.noteKeyActivity() // 本测试只验证接线，不执行真实网络传输。
        for(text in listOf("语音通话中","视频通话中")) {
            CallRecordingJobService.cancel(context)
            CallRecordingJobService.schedule(context)
            val now=System.currentTimeMillis()
            WechatCallSignals.notification("com.tencent.mm","call",true,text,false,now,now)
            WechatCallSignals.removed("com.tencent.mm","call",now)
            val deadline=System.nanoTime()+2_000_000_000L
            while(WechatCallUpload.recentEnd(context)==null && System.nanoTime()<deadline)Thread.sleep(5)
            assertNotNull(text,WechatCallUpload.recentEnd(context))
            val wake=scheduler.allPendingJobs.single{!it.isPeriodic}
            assertEquals(0L,wake.minLatencyMillis)
            assertFalse(CallRecordingService.isRunning)
        }
    }
    @Test fun `微信结束断网也立即执行本地导入检查`() {
        shadowOf(manager).setActiveNetworkInfo(null)
        CallRecordingJobService.schedule(context)
        WechatCallUpload.finished(context)
        val deadline=System.nanoTime()+3_000_000_000L
        while(CallRecordingRuntime.preferences(context).getString("system_scan_wechat",null)==null && System.nanoTime()<deadline)Thread.sleep(10)
        assertNotNull("本地复制检查不应等WiFi",CallRecordingRuntime.preferences(context).getString("system_scan_wechat",null))
        assertFalse(CallRecordingService.isRunning)
    }
    @Test fun `已复制队列超过七天仍保留并安排失败补传`()=io {
        val box=CallRecordingRuntime.outbox(context)
        val(id,file)=box.createAudioFile();file.writeText("old private audio")
        val old=System.currentTimeMillis()-8*86400000L
        val task=box.enqueue(id,DataCollector.deviceId(context),CallMetadata(destination=ServerConfig.baseUrl,
            recording_started_at=old-60000,recording_ended_at=old,audio_duration_ms=60000),
            source=SystemRecordingSource("content://media/external/audio/media/9",file.length(),old,false))
        box.markAttempt(task.id);box.fail(task.id,System.currentTimeMillis(),"network_error")
        assertNotNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
        assertTrue(file.exists());assertEquals(1,box.tasks().single().attempts)
    }
    @Test fun `系统录音失败按已记录退避时间安排重试而非等三十分钟`()=io {
        val task=pending();val box=CallRecordingRuntime.outbox(context)
        box.markAttempt(task.id);box.fail(task.id,System.currentTimeMillis(),"network_error")
        assertTrue("fixture must be input/game idle",ImageUploadRuntime.isBackgroundWorkAllowed())
        val delay=CallRecordingRuntime.runUploadsWithRetry(context){true}
        assertNotNull("已有失败待传必须安排到期重试",delay)
        assertTrue("actual retry delay=$delay",delay!! in 55_000L..60_000L)
        assertEquals(1,box.tasks().single().attempts)
        assertTrue(box.audioFile(task.id).exists())
    }
    @Test fun `有效WiFi有待传被输入暂停时保留退避重试且不上传`()=io {
        val task=pending();ImageUploadRuntime.noteKeyActivity()
        val first=CallRecordingRuntime.runUploadsWithRetry(context){true}
        val second=CallRecordingRuntime.runUploadsWithRetry(context){true}
        assertNotNull(first);assertNotNull(second)
        assertTrue(first!!>=30_000L);assertTrue(second!!>first)
        assertEquals(0,CallRecordingRuntime.outbox(context).tasks().single().attempts)
        assertTrue(CallRecordingRuntime.outbox(context).audioFile(task.id).exists())
    }
    @Test fun `游戏暂停有待传也保留退避但空队列不短轮询`()=io {
        GameWorkRuntime.setGaming(true)
        assertNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
        pending()
        assertNotNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
    }
    @Test fun `长期游戏等待最终退回三十分钟周期而不是无限短轮询`()=io {
        pending();GameWorkRuntime.setGaming(true)
        val delays=(1..8).map{CallRecordingRuntime.runUploadsWithRetry(context){true}}
        assertEquals(listOf(35_000L,70_000L,140_000L,280_000L,560_000L,1_120_000L,null,null),delays)
    }
    @Test fun `自录结束尚未发布的日志被输入挡住也安排恢复`()=io {
        val now=System.currentTimeMillis()
        val sessions=CallRecordingRuntime.sessions(context)
        val task=sessions.begin(DataCollector.deviceId(context),CallMetadata(destination=ServerConfig.baseUrl,
            recording_started_at=now-60_000,recording_ended_at=now,audio_duration_ms=60_000))
        sessions.finish(task,task.metadata,{false})
        ImageUploadRuntime.noteKeyActivity()
        assertTrue(CallRecordingRuntime.outbox(context).tasks().isEmpty())
        assertNotNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
        assertEquals(1,sessions.pendingCount())
    }
    @Test fun `已确认保存仅清理未完成时不用短轮询重复上传`()=io {
        val task=pending();val box=CallRecordingRuntime.outbox(context)
        assertTrue(box.acceptReceipt(task.id,CallReceipt(true,task.id,task.deviceId,task.metadata.byte_size,
            task.metadata.sha256,"2026-10-13T00:00:00.000Z")))
        ImageUploadRuntime.noteKeyActivity()
        assertNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
        assertTrue(box.audioFile(task.id).exists())
    }
    @Test fun `流量待传仍预约重试而断网或撤权不空转`()=io {
        pending();ImageUploadRuntime.noteKeyActivity()
        network(wifi=false)
        assertNotNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
        shadowOf(manager).setActiveNetworkInfo(null)
        assertNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
        network();CallRecordingRuntime.consent(context).revokeUpload()
        assertNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
    }
    @Test fun `重复唤醒保留较早任务更紧急事件可以提前`() {
        CallRecordingJobService.wake(context,35_000)
        val first=scheduler.allPendingJobs.single{!it.isPeriodic}
        ShadowSystemClock.advanceBy(Duration.ofSeconds(2))
        CallRecordingJobService.wake(context,35_000)
        assertSame(first,scheduler.allPendingJobs.single{!it.isPeriodic})
        CallRecordingJobService.wake(context,3_000)
        assertEquals(3_000L,scheduler.allPendingJobs.single{!it.isPeriodic}.minLatencyMillis)
    }
    @Test fun `录音补传运行时的新事件留到完成后而不替换正在运行的任务`() {
        CallRecordingJobService.wake(context,3_000)
        val running=scheduler.allPendingJobs.single{!it.isPeriodic}
        CallRecordingJobService.wakeStarted(running.extras.getString("wake_token"))
        CallRecordingJobService.wake(context,3_000)
        assertSame("同ID重新schedule会取消当前传输",running,scheduler.allPendingJobs.single{!it.isPeriodic})
        scheduler.cancel(running.id) // 系统收到jobFinished后移除运行项。
        CallRecordingJobService.wakeFinished(context,60_000)
        assertEquals(3_000L,scheduler.allPendingJobs.single{!it.isPeriodic}.minLatencyMillis)
    }
    @Test fun `系统音频变化唤醒补传而不必重新连接WiFi`() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        CallRecordingJobService.schedule(context)
        scheduler.allPendingJobs.filter{!it.isPeriodic}.forEach{scheduler.cancel(it.id)}
        context.contentResolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(scheduler.allPendingJobs.any{!it.isPeriodic})
        val first=scheduler.allPendingJobs.single{!it.isPeriodic}
        context.contentResolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,null)
        shadowOf(Looper.getMainLooper()).idle()
        assertSame(first,scheduler.allPendingJobs.single{!it.isPeriodic})
    }
    @Test fun `没有音频权限和撤回授权后的媒体变化不唤醒`() {
        shadowOf(context).denyPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        CallRecordingJobService.schedule(context)
        scheduler.allPendingJobs.filter{!it.isPeriodic}.forEach{scheduler.cancel(it.id)}
        context.contentResolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(scheduler.allPendingJobs.none{!it.isPeriodic})
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        CallRecordingJobService.schedule(context)
        CallRecordingRuntime.consent(context).revokeUpload()
        context.contentResolver.notifyChange(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,null)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(scheduler.allPendingJobs.none{!it.isPeriodic})
    }
}
