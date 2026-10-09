package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.MediaRecorder
import android.media.AudioManager
import android.os.Handler
import android.os.SystemClock
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ServerConfig
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ServiceController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSubscriptionManager.SubscriptionInfoBuilder
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@LooperMode(LooperMode.Mode.PAUSED)
class CallRecordingServiceHealthTest {
    private lateinit var app:Application
    private lateinit var controller:ServiceController<CallRecordingService>
    private lateinit var service:CallRecordingService
    private lateinit var worker:Handler
    private lateinit var health:Runnable
    private var destroyed=false
    private fun io(block:()->Unit){val e=Executors.newSingleThreadExecutor();try{e.submit(block).get()}finally{e.shutdownNow()}}
    @Before fun setup() {
        app=ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,true).commit()
        CallRecordingRuntime.preferences(app).edit().clear().commit();ServerConfig.init(app)
        val device=UUID.randomUUID().toString()
        CallRecordingRuntime.consent(app).grant(device,ServerConfig.baseUrl,true,true)
        controller=Robolectric.buildService(CallRecordingService::class.java).create();service=controller.get()
        worker=ReflectionHelpers.getField(service,"worker");health=ReflectionHelpers.getField(service,"health")
        shadowOf(worker.looper).pause()
        ReflectionHelpers.setField(service,"device",device)
        ReflectionHelpers.setField(service,"observedSubscriptions",setOf(1))
        ReflectionHelpers.setField(service,"policy",MultiSimIncomingCallPolicy(setOf(1)))
        shadowOf(app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager)
            .setActiveSubscriptionInfos(SubscriptionInfoBuilder.newBuilder().setId(1).buildSubscriptionInfo())
    }
    @After fun cleanup() {
        if(!destroyed){controller.destroy();shadowOf(worker.looper).idle()}
    }
    private fun nextDelay()=shadowOf(worker.looper).nextScheduledTaskTime.toMillis()-SystemClock.uptimeMillis()
    private fun phone(state:Int)=ReflectionHelpers.callInstanceMethod<Unit>(service,"phoneState",
        ClassParameter.from(Int::class.javaPrimitiveType!!,1),ClassParameter.from(Int::class.javaPrimitiveType!!,state))
    private fun answerIncoming()=io {
        phone(TelephonyManager.CALL_STATE_IDLE);phone(TelephonyManager.CALL_STATE_RINGING);phone(TelephonyManager.CALL_STATE_OFFHOOK)
    }
    private fun wechat(active:Boolean=true,source:String="wechat_voice") {
        val method=service.javaClass.declaredMethods.firstOrNull{it.name=="wechatSignal"}
        assertNotNull("服务必须接入微信通话信号",method)
        method!!.isAccessible=true;method.invoke(service,active,source)
    }
    private fun expanded() {CallRecordingRuntime.preferences(app).edit().putString("recording_scope","phone_wechat_v2").commit()}
    private fun communication(active:Boolean=true) {
        (app.getSystemService(Context.AUDIO_SERVICE) as AudioManager).mode=if(active)AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
    }
    @Test fun `未录音健康检查以三十分钟兜底而不是每秒唤醒`() {
        io{health.run()}
        assertEquals(1_800_000L,nextDelay())
        shadowOf(worker.looper).idleFor(Duration.ofSeconds(1799))
        assertEquals(1000L,nextDelay())
        shadowOf(worker.looper).idleFor(Duration.ofSeconds(1))
        assertEquals(1_800_000L,nextDelay())
    }
    @Test fun `来电回调立即开始并把原三十分钟检查改为每秒`() {
        worker.postDelayed(health,1_800_000)
        answerIncoming()
        assertNotNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        assertEquals(1000L,nextDelay())
        shadowOf(worker.looper).idleFor(Duration.ofSeconds(1))
        assertEquals(1000L,nextDelay())
        assertFalse(shadowOf(service).isStoppedBySelf)
    }
    @Test fun `挂断立即切回空闲检查而不保留录音高频检查`() {
        answerIncoming()
        io{phone(TelephonyManager.CALL_STATE_IDLE)}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        assertEquals(1_800_000L,nextDelay())
    }
    @Test fun `两次健康检查之间撤销麦克风权限也不能因来电启动录音`() {
        worker.postDelayed(health,1_800_000)
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO)
        answerIncoming()
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        assertNull(CallRecordingService.activeId)
    }
    @Test fun `销毁服务移除延迟健康检查不再唤醒`() {
        worker.postDelayed(health,1_800_000)
        controller.destroy();destroyed=true
        shadowOf(worker.looper).idle()
        assertEquals(Duration.ZERO,shadowOf(worker.looper).nextScheduledTaskTime)
        assertNull(CallRecordingService.activeId)
    }
    @Test fun `新版授权允许呼出尝试但不能伪造已接通时间`() {
        CallRecordingRuntime.preferences(app).edit().putString("recording_scope","phone_wechat_v2").commit()
        io{phone(0);phone(2)}
        assertNotNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        val task=ReflectionHelpers.getField<CallTask>(service,"task")
        assertNull(task.metadata.call_started_at)
        assertTrue(task.metadata.call_duration_estimated)
    }
    @Test fun `旧版授权不自动扩大到呼出`() {
        io{phone(0);phone(2)}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
    }
    @Test fun `系统静音后保留失败原因且下一通可重新尝试`() {
        answerIncoming()
        io{ReflectionHelpers.callInstanceMethod<Unit>(service,"restrict",ClassParameter.from(String::class.java,"system_silenced"))}
        assertFalse(shadowOf(service).isStoppedBySelf)
        assertEquals("system_silenced",CallRecordingRuntime.preferences(app).getString("last_failure",null))
        io{phone(2)}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        answerIncoming()
        assertNotNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
    }
    @Test fun `历史能力阻断不能永久取消新通话的尝试`() {
        CallRecordingRuntime.preferences(app).edit().putBoolean("capability_blocked",true).commit()
        answerIncoming()
        assertNotNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
    }
    @Test fun `微信状态须有新版授权和通信音频模式才录音`() {
        io{phone(0);wechat()}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        expanded()
        io{wechat()}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        communication()
        io{health.run()}
        assertNotNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        val metadata=ReflectionHelpers.getField<CallTask>(service,"task").metadata
        assertEquals("wechat",metadata.platform)
        assertNull(metadata.call_started_at)
        io{wechat(false)}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        assertEquals(1_800_000L,nextDelay())
    }
    @Test fun `微信通知等待音频模式只持续三十秒`() {
        expanded();io{phone(0);wechat()}
        assertEquals(1000L,nextDelay())
        shadowOf(worker.looper).idleFor(Duration.ofSeconds(30))
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        assertEquals(1_800_000L,nextDelay())
        communication();io{wechat()}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
    }
    @Test fun `微信同一通失败不反复抢麦下一通可重新尝试`() {
        expanded();communication();io{phone(0);wechat()}
        assertNotNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        io{ReflectionHelpers.callInstanceMethod<Unit>(service,"restrict",ClassParameter.from(String::class.java,"system_silenced"));wechat()}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        io{wechat(false);wechat(true,"wechat_video")}
        assertEquals("video",ReflectionHelpers.getField<CallTask>(service,"task").metadata.call_type)
    }
    @Test fun `微信录音通信模式结束即停止且电话不能混入微信文件`() {
        expanded();communication();io{phone(0);wechat()}
        communication(false);io{health.run()}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        communication();io{wechat();phone(1)}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        io{phone(2)}
        assertEquals("phone",ReflectionHelpers.getField<CallTask>(service,"task").metadata.platform)
    }
    @Test fun `没有SIM卡时仍能录已确认微信通话`() {
        expanded();communication()
        ReflectionHelpers.setField(service,"observedSubscriptions",emptySet<Int>())
        shadowOf(app.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager).setActiveSubscriptionInfos()
        io{wechat()}
        assertNotNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
    }
    @Test fun `电话接替微信时微信结束通知不覆盖电话录音状态`() {
        expanded();communication();io{phone(0);wechat();phone(1);phone(2);wechat(false)}
        assertEquals("phone",ReflectionHelpers.getField<CallTask>(service,"task").metadata.platform)
        assertEquals("recording_unverified",CallRecordingRuntime.preferences(app).getString("state",null))
    }
    @Test fun `呼出录音期间降回旧授权应当停止扩大范围的录音`() {
        expanded();io{phone(0);phone(2)}
        CallRecordingRuntime.preferences(app).edit().remove("recording_scope").commit()
        io{health.run()}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
    }
    @Test fun `手动微信视频同样要求新授权通信模式和电话空闲`() {
        fun manual()=ReflectionHelpers.callInstanceMethod<Unit>(service,"manualWechat",ClassParameter.from(Boolean::class.javaPrimitiveType!!,true))
        io{phone(0);manual()}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        expanded();io{manual()}
        assertNull(ReflectionHelpers.getField<MediaRecorder?>(service,"recorder"))
        communication();io{manual()}
        assertEquals("video",ReflectionHelpers.getField<CallTask>(service,"task").metadata.call_type)
    }
    @Test fun `销毁与监听注册交错时工作线程最终关闭新注册的监听`() {
        val entered=CountDownLatch(1);val finishRegistration=CountDownLatch(1)
        val closed=AtomicBoolean(false)
        val executor=Executors.newSingleThreadExecutor()
        worker.post{
            entered.countDown();check(finishRegistration.await(5,TimeUnit.SECONDS))
            ReflectionHelpers.setField(service,"wechatConnection",AutoCloseable{closed.set(true)})
        }
        val draining=executor.submit{shadowOf(worker.looper).idle()}
        try{
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            controller.destroy();destroyed=true
            finishRegistration.countDown();draining.get(5,TimeUnit.SECONDS)
            assertTrue("退出线程前必须关闭晚到的监听注册",closed.get())
        }finally{finishRegistration.countDown();executor.shutdownNow()}
    }
}
