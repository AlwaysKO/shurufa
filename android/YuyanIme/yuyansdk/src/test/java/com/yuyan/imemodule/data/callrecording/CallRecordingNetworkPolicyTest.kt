package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.app.Application
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.net.Uri
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowContentResolver
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import java.io.File
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingNetworkPolicyTest {
    private val context=ApplicationProvider.getApplicationContext<Application>()
    private val manager=context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private fun <T> io(block:()->T):T {val e=Executors.newSingleThreadExecutor();try{return e.submit<T>{block()}.get()}finally{e.shutdownNow()}}
    @Before fun setup() {
        CallRecordingNetwork.stop()
        CallRecordingRuntime.preferences(context).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(CollectionConsent.KEY,true).commit()
        ServerConfig.init(context)
        io{CallRecordingRuntime.consent(context).grant(DataCollector.deviceId(context),ServerConfig.baseUrl,false,true)}
        // Robolectric resets elapsedRealtime between tests; reset the singleton gate's resume deadline too.
        GameWorkRuntime.setGaming(true);GameWorkRuntime.setGaming(false)
        ImageUploadRuntime.finishInput();ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
    }
    @After fun cleanup(){CallRecordingNetwork.stop()}
    private fun network(wifi:Boolean=true,validated:Boolean=true):NetworkCapabilities {
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if(wifi)ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE,0,true,true))
        shadowOf(manager).setDefaultNetworkActive(true)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(if(wifi)NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if(validated)shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork,caps)
        return caps
    }
    @Test fun `发现任务取消联网限制但仍三十分钟兜底且迁移旧任务`() {
        scheduler.schedule(JobInfo.Builder(CallRecordingJobService.JOB_ID,ComponentName(context,CallRecordingJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(1_800_000).setPersisted(true).build())
        CallRecordingJobService.wake(context)
        val periodic=scheduler.allPendingJobs.single{it.isPeriodic}
        assertEquals(JobInfo.NETWORK_TYPE_NONE,periodic.networkType);assertEquals(1_800_000L,periodic.intervalMillis)
        assertEquals(JobInfo.NETWORK_TYPE_NONE,scheduler.allPendingJobs.single{!it.isPeriodic}.networkType)
    }
    @Test fun `断网时运行仍保留待上传音频且不尝试HTTP`()=io {
        val box=CallRecordingRuntime.outbox(context)
        val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A offline fixture")
        box.enqueue(id,DataCollector.deviceId(context),CallMetadata(destination=ServerConfig.baseUrl,
            recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000))
        shadowOf(manager).setActiveNetworkInfo(null)
        CallRecordingRuntime.runUploads(context){true}
        assertEquals(0,box.tasks().single{it.id==id}.attempts)
        assertTrue(file.exists())
    }
    @Test fun `断网仍发现七天内系统录音并登记稳定性观察`()=io {
        shadowOf(manager).setActiveNetworkInfo(null)
        registerSystemCandidate()
        assertTrue("fixture must be input/game idle",ImageUploadRuntime.isBackgroundWorkAllowed())
        assertNotNull("离线首次发现后仍须预约稳定复查以复制原件",CallRecordingRuntime.runUploadsWithRetry(context){true})
        assertTrue(CallRecordingRuntime.preferences(context).getString("system_scan_phone","")!!.contains("最近7天发现 1"))
        assertTrue(File(context.noBackupFilesDir.canonicalFile,"call_audio/system_index").listFiles().orEmpty().any{it.extension=="json"})
    }
    private fun registerSystemCandidate() {
        shadowOf(context).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
        val provider=object:ContentProvider(){
            override fun onCreate()=true
            override fun getType(uri:Uri)="audio/mp4"
            override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor {
                val values=mapOf<String,Any>("_id" to 1L,"_display_name" to "通话-测试.m4a","_size" to 100L,
                    "date_modified" to System.currentTimeMillis()/1000-60,"_data" to "/storage/emulated/0/Sounds/CallRecord/通话-测试.m4a")
                return MatrixCursor(projection!!).apply{addRow(projection.map{values[it]}.toTypedArray())}
            }
            override fun insert(uri:Uri,values:ContentValues?):Uri?=error("read_only")
            override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int=error("read_only")
            override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int=error("read_only")
        }
        provider.attachInfo(context,ProviderInfo().apply{authority="media";exported=true})
        ShadowContentResolver.registerProviderInternal("media",provider)
    }
    @Test fun `离线首次观察后输入避让仍保留待复制恢复`()=io {
        shadowOf(manager).setActiveNetworkInfo(null)
        registerSystemCandidate()
        assertNotNull(CallRecordingRuntime.runUploadsWithRetry(context){true})
        ImageUploadRuntime.noteKeyActivity()
        assertNotNull("未形成副本的本地待办不能丢失",CallRecordingRuntime.runUploadsWithRetry(context){true})
    }
    @Test fun `有网无上传队列时稳定复查也有界`()=io {
        network(wifi=false)
        registerSystemCandidate()
        val retries=(1..4).map{CallRecordingRuntime.runUploadsWithRetry(context){true}}
        assertEquals(listOf(35_000L,35_000L,null,null),retries)
        assertTrue(CallRecordingRuntime.outbox(context).tasks().isEmpty())
    }
    @Test fun `允许有效WiFi和流量且请求固定到当前网络socket和DNS`() {
        network(wifi=false);assertEquals(manager.activeNetwork,CallRecordingNetwork.network(context))
        network(wifi=false,validated=false);assertNull(CallRecordingNetwork.network(context))
        network(validated=false);assertNull(CallRecordingNetwork.network(context))
        val caps=network();val wifi=manager.activeNetwork!!
        assertEquals(wifi,CallRecordingNetwork.network(context))
        val client=CallRecordingNetwork.client(wifi)
        assertSame(wifi.socketFactory,client.socketFactory)
        assertNotSame(okhttp3.Dns.SYSTEM,client.dns)
        assertFalse(client.followRedirects);assertFalse(client.followSslRedirects)
        shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        assertNull(CallRecordingNetwork.network(context))
    }
    @Test fun `手机流量可上传私有录音但图片仍等待WiFi`() {
        network(wifi=false)
        val mobile=CallRecordingNetwork.network(context)
        assertNotNull(mobile)
        assertFalse(ImageUploadRuntime.canUploadScreenshot(context,ServerConfig.baseUrl))
        val box=CallRecordingRuntime.outbox(context)
        val task=io {
            val(id,file)=box.createAudioFile();file.writeText("private audio copy")
            box.enqueue(id,DataCollector.deviceId(context),CallMetadata(destination=ServerConfig.baseUrl,
                recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000))
        }
        val server=MockWebServer();server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(404))
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"record_not_found"}"""))
            server.enqueue(MockResponse().setBody("""{"stored":true,"record_id":"${task.id}","device_id":"${task.deviceId}","byte_size":${task.metadata.byte_size},"sha256":"${task.metadata.sha256}","stored_at":"2026-10-23T00:00:00Z"}"""))
            val http=OkHttpClient()
            val factory=Call.Factory{r->http.newCall(r.newBuilder().url(server.url(r.url.encodedPath+"?"+(r.url.encodedQuery?:""))).build())}
            val allowed={CallRecordingNetwork.network(context)==mobile}
            io {
                CallRecordingUploader(box,CallRecordingHttpTransport({"a".repeat(64)},allowed,factory),allowed,{ServerConfig.baseUrl}).runOnce()
                assertEquals("saved",box.tasks().single().uploadStatus)
                assertFalse(box.audioFile(task.id).exists())
            }
            assertEquals(3,server.requestCount)
            server.takeRequest();server.takeRequest()
            val uploaded=server.takeRequest()
            assertEquals("PUT",uploaded.method)
            assertEquals("private audio copy",uploaded.body.readUtf8())
        }finally{server.shutdown()}
    }
    @Test fun `流量恢复有效网络也唤醒待上传录音`() {
        val caps=network(wifi=false,validated=false)
        CallRecordingNetwork.observe(context)
        val callback=shadowOf(manager).networkCallbacks.single()
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onCapabilitiesChanged(manager.activeNetwork!!,caps)
        assertTrue(scheduler.allPendingJobs.any{!it.isPeriodic})
    }
    @Test fun `WiFi从未就绪变为有效时只唤醒一次且停止后注销监听`() {
        val caps=network(validated=false)
        CallRecordingNetwork.observe(context);CallRecordingNetwork.observe(context)
        val callback=shadowOf(manager).networkCallbacks.single()
        val wifi=manager.activeNetwork!!
        callback.onCapabilitiesChanged(wifi,caps)
        assertTrue(scheduler.allPendingJobs.none{!it.isPeriodic})
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        callback.onCapabilitiesChanged(wifi,caps)
        val wake=scheduler.allPendingJobs.single{!it.isPeriodic}
        callback.onCapabilitiesChanged(wifi,caps)
        assertSame(wake,scheduler.allPendingJobs.single{!it.isPeriodic})
        CallRecordingNetwork.stop();assertTrue(shadowOf(manager).networkCallbacks.isEmpty())
    }
    @Test fun `网络请求阻塞期间当前网络失效会取消且保留待传文件`() {
        val caps=network();val wifi=manager.activeNetwork!!
        val box=CallRecordingRuntime.outbox(context)
        val task=io{
            val(id,file)=box.createAudioFile();file.writeText("0000ftypM4A switching fixture")
            box.enqueue(id,DataCollector.deviceId(context),CallMetadata(destination=ServerConfig.baseUrl,
                recording_started_at=1000,recording_ended_at=2000,audio_duration_ms=1000))
        }
        val server=MockWebServer();server.start();val executor=Executors.newSingleThreadExecutor()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val http=OkHttpClient();val factory=Call.Factory{r->http.newCall(r.newBuilder().url(server.url(r.url.encodedPath)).build())}
            val allowed={CallRecordingNetwork.network(context)==wifi}
            val transport=CallRecordingHttpTransport({"a".repeat(64)},allowed,factory)
            val upload=executor.submit{CallRecordingUploader(box,transport,allowed,{ServerConfig.baseUrl}).runOnce()}
            assertNotNull(server.takeRequest(3,TimeUnit.SECONDS))
            shadowOf(caps).removeCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            upload.get(3,TimeUnit.SECONDS)
            io{assertTrue(box.audioFile(task.id).exists());assertEquals("paused",box.tasks().single{it.id==task.id}.uploadStatus)}
            assertEquals(1,server.requestCount)
        }finally{executor.shutdownNow();server.shutdown()}
    }
}
