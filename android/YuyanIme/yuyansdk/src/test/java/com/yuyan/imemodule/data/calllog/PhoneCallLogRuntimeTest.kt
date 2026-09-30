package com.yuyan.imemodule.data.calllog

import android.Manifest
import android.app.Application
import android.app.job.JobScheduler
import android.content.Context
import android.content.ContextWrapper
import android.content.ContentResolver
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.callrecording.CallRecordingRuntime
import com.yuyan.imemodule.data.callrecording.CallRecordingJobService
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class PhoneCallLogRuntimeTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    @Test fun `通话记录独立持久恢复不要求录音开启也不启动服务`() {
        val scheduler=app.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        CallRecordingRuntime.consent(app).revokeAll()
        PhoneCallLogRuntime.preferences(app).edit().clear().commit()
        CallRecordingRuntime.restore(app)
        assertNull(scheduler.getPendingJob(PhoneCallLogJobService.JOB_ID))
        val consent=PhoneCallLogRuntime.consent(app)
        consent.grant(UUID.randomUUID().toString(),"https://example.test",consent.revision)
        CallRecordingRuntime.restore(app)
        assertTrue(scheduler.getPendingJob(PhoneCallLogJobService.JOB_ID)!!.isPersisted)
        assertNull(scheduler.getPendingJob(CallRecordingJobService.JOB_ID))
        assertNull(Shadows.shadowOf(app).nextStartedService)
        consent.revoke();PhoneCallLogRuntime.restore(app)
        assertNull(scheduler.getPendingJob(PhoneCallLogJobService.JOB_ID))
    }
    @Test fun `关闭或正在输入时不触碰设备身份磁盘和系统通话提供者`() {
        val guarded=object:ContextWrapper(app){
            override fun getApplicationContext():Context=this
            override fun getNoBackupFilesDir():File=throw AssertionError("不应读取设备身份")
            override fun getContentResolver():ContentResolver=throw AssertionError("不应查询通话记录")
        }
        PhoneCallLogRuntime.preferences(app).edit().clear().commit()
        PhoneCallLogRuntime.run(guarded){true}
        CollectionConsent.setEnabled(app,true)
        val consent=PhoneCallLogRuntime.consent(app)
        consent.grant(UUID.randomUUID().toString(),"https://example.test",consent.revision)
        ImageUploadRuntime.noteKeyActivity()
        PhoneCallLogRuntime.run(guarded){true}
    }
    @Test fun `读取权限独立于电话状态和麦克风权限`() {
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALL_LOG)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_PHONE_STATE,Manifest.permission.RECORD_AUDIO)
        assertFalse(PhoneCallLogRuntime.hasPermission(app))
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.READ_CALL_LOG)
        assertTrue(PhoneCallLogRuntime.hasPermission(app))
        Shadows.shadowOf(app).denyPermissions(Manifest.permission.READ_CALL_LOG)
        assertFalse(PhoneCallLogRuntime.hasPermission(app))
    }
}
