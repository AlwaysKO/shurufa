package com.yuyan.imemodule.data.callrecording
import android.content.Context
import android.app.job.JobScheduler
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.ServerConfig
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingRuntimeTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    @Test fun `默认不授权授权分别持久化并绑定目的地设备`() {
        val prefs=context.getSharedPreferences(UUID.randomUUID().toString(),0)
        val consent=CallRecordingConsent(prefs)
        val id=UUID.randomUUID().toString();val target="https://example.test"
        assertFalse(consent.recordingAllowed(id,target));assertFalse(consent.uploadAllowed(id,target))
        consent.grant(id,target,record=true,upload=false)
        assertTrue(CallRecordingConsent(prefs).recordingAllowed(id,target));assertFalse(consent.uploadAllowed(id,target))
        assertFalse(consent.recordingAllowed(UUID.randomUUID().toString(),target))
        assertFalse(consent.recordingAllowed(id,"https://other.test"))
        consent.grant(id,target,record=true,upload=true);assertTrue(consent.uploadAllowed(id,target))
        consent.revokeRecording();assertFalse(consent.recordingAllowed(id,target));assertTrue(consent.uploadAllowed(id,target))
        consent.revokeAll();assertFalse(consent.uploadAllowed(id,target))
    }
    @Test fun `恢复只注册独立持久补传job且不启动麦克风`() {
        val prefs=CallRecordingRuntime.preferences(context);prefs.edit().clear().commit()
        CallRecordingRuntime.restore(context)
        val scheduler=context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        assertNull(scheduler.getPendingJob(CallRecordingJobService.JOB_ID))
        val id=UUID.randomUUID().toString();ServerConfig.init(context)
        CallRecordingConsent(prefs).grant(id,ServerConfig.baseUrl,true,true)
        CallRecordingRuntime.restore(context)
        val job=scheduler.getPendingJob(CallRecordingJobService.JOB_ID)
        assertNotNull(job);assertTrue(job!!.isPersisted)
        CallRecordingConsent(prefs).revokeAll();CallRecordingRuntime.restore(context)
        assertNull(scheduler.getPendingJob(CallRecordingJobService.JOB_ID))
    }

    @Test fun `组合开关首次需确认关闭后记住原绑定且再次打开两项同时启用`() {
        val prefs=context.getSharedPreferences(UUID.randomUUID().toString(),0)
        val consent=CallRecordingConsent(prefs);val id=UUID.randomUUID().toString();val target="https://example.test"
        assertTrue(consent.needsCombinedConfirmation(id,target))
        consent.grantCombined(id,target)
        assertTrue(consent.recordingAllowed(id,target));assertTrue(consent.uploadAllowed(id,target))
        consent.revokeAll()
        assertFalse(consent.wantsRecording);assertFalse(consent.wantsUpload)
        assertFalse(CallRecordingConsent(prefs).needsCombinedConfirmation(id,target))
        assertTrue(consent.needsCombinedConfirmation(id,"https://other.test"))
        assertTrue(consent.needsCombinedConfirmation(UUID.randomUUID().toString(),target))
        consent.grantCombined(id,target)
        assertTrue(consent.recordingAllowed(id,target));assertTrue(consent.uploadAllowed(id,target))
    }
    @Test fun `旧版单项或双项授权不自动升级组合授权`() {
        val consent=CallRecordingConsent(context.getSharedPreferences(UUID.randomUUID().toString(),0))
        val id=UUID.randomUUID().toString();val target="https://example.test"
        consent.grant(id,target,true,false)
        assertTrue(consent.needsCombinedConfirmation(id,target));assertFalse(consent.wantsUpload)
        consent.grant(id,target,true,true)
        assertTrue(consent.needsCombinedConfirmation(id,target))
    }

    @Test fun `较新的撤回使另一实例迟到的启用失效`() {
        val prefs=context.getSharedPreferences(UUID.randomUUID().toString(),0)
        val first=CallRecordingConsent(prefs);val second=CallRecordingConsent(prefs)
        val id=UUID.randomUUID().toString();val target="https://example.test"
        val ticket=first.revision
        second.revokeAll()
        assertFalse(first.grantCombined(id,target,ticket))
        assertFalse(first.wantsRecording);assertFalse(first.wantsUpload)
        assertTrue(first.needsCombinedConfirmation(id,target))
        assertTrue(first.grantCombined(id,target,first.revision))
    }
}
