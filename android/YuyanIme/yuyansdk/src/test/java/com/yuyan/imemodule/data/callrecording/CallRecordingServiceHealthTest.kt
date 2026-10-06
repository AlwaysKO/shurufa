package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.app.Application
import android.content.Context
import android.media.MediaRecorder
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
}
