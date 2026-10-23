package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.telephony.TelephonyManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.DataCollector
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingPhoneReceiverTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val receiver=CallRecordingPhoneReceiver()
    private val scheduler get()=app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
    private fun state(value:String)=receiver.onReceive(app,Intent(TelephonyManager.ACTION_PHONE_STATE_CHANGED)
        .putExtra(TelephonyManager.EXTRA_STATE,value))
    @Before fun setup() {
        CallRecordingJobService.cancel(app)
        CallRecordingRuntime.preferences(app).edit().clear().commit()
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,true).commit()
        ServerConfig.init(app)
        CallRecordingRuntime.consent(app).grant(DataCollector.deviceId(app),ServerConfig.baseUrl,false,true)
        shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        ImageUploadRuntime.noteKeyActivity() // 只测结束广播接线，不执行扫描或网络。
    }
    @After fun cleanup(){CallRecordingJobService.cancel(app);ImageUploadRuntime.finishInput()}
    @Test fun `电话接通结束立即预约且重复广播不重复触发`() {
        state(TelephonyManager.EXTRA_STATE_OFFHOOK)
        assertNull(WechatCallUpload.recentPhoneEnd(app))
        state(TelephonyManager.EXTRA_STATE_IDLE)
        assertNotNull(WechatCallUpload.recentPhoneEnd(app))
        val wake=scheduler.allPendingJobs.single{!it.isPeriodic}
        assertEquals(0L,wake.minLatencyMillis)
        val revision=WechatCallUpload.importRevision()
        state(TelephonyManager.EXTRA_STATE_IDLE)
        assertEquals(revision,WechatCallUpload.importRevision())
        assertFalse(CallRecordingService.isRunning)
    }
    @Test fun `未接来电或未授权不触发录音复制`() {
        state(TelephonyManager.EXTRA_STATE_RINGING);state(TelephonyManager.EXTRA_STATE_IDLE)
        assertNull(WechatCallUpload.recentPhoneEnd(app))
        state(TelephonyManager.EXTRA_STATE_OFFHOOK)
        CallRecordingRuntime.consent(app).revokeUpload()
        state(TelephonyManager.EXTRA_STATE_IDLE)
        assertNull(WechatCallUpload.recentPhoneEnd(app))
        assertTrue(scheduler.allPendingJobs.none{!it.isPeriodic})
    }
    @Test fun `结束广播早于音频模式恢复时仍预约但恢复前不缩短稳定期`() {
        val audio=app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audio.mode=AudioManager.MODE_IN_CALL
        state(TelephonyManager.EXTRA_STATE_OFFHOOK);state(TelephonyManager.EXTRA_STATE_IDLE)
        assertTrue(scheduler.allPendingJobs.any{!it.isPeriodic})
        assertNull(WechatCallUpload.recentPhoneEnd(app))
        audio.mode=AudioManager.MODE_NORMAL
        assertNotNull(WechatCallUpload.recentPhoneEnd(app))
    }
}
