package com.yuyan.imemodule.data.callrecording
import android.Manifest
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingPermissionTest {
    @Test fun `通知未允许不阻断已授权录音`() {
        val context=ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE)
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(false)
        assertNull(CallRecordingService.permissionBlock(context))
        assertTrue(CallRecordingService.permissions(context))
        shadowOf(manager).setNotificationsEnabled(true)
        assertNull(CallRecordingService.permissionBlock(context))
    }

    @Test fun `通知通道关闭不阻断录音且仍可打开通道设置`() {
        val context=ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE)
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(android.app.NotificationChannel("consented_call_audio_v1","录音",NotificationManager.IMPORTANCE_NONE))
        assertNull(CallRecordingService.permissionBlock(context))
        assertTrue(CallRecordingService.permissions(context))
        val intent=CallRecordingService.notificationSettingsIntent(context)
        assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,intent.action)
        assertEquals(context.packageName,intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
        assertEquals("consented_call_audio_v1",intent.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
    }

    @Test @Config(sdk=[35]) fun `拒绝通知运行时权限不阻断录音`() {
        val context=ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE)
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertNull(CallRecordingService.permissionBlock(context))
        assertTrue(CallRecordingService.permissions(context))
    }

    @Test fun `没有麦克风或电话权限仍阻断录音`() {
        val context=ApplicationProvider.getApplicationContext<android.app.Application>()
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(false)
        shadowOf(context).grantPermissions(Manifest.permission.READ_PHONE_STATE)
        shadowOf(context).denyPermissions(Manifest.permission.RECORD_AUDIO)
        assertEquals("audio_phone_permissions_required",CallRecordingService.permissionBlock(context))
        assertFalse(CallRecordingService.permissions(context))
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO)
        shadowOf(context).denyPermissions(Manifest.permission.READ_PHONE_STATE)
        assertEquals("audio_phone_permissions_required",CallRecordingService.permissionBlock(context))
        assertFalse(CallRecordingService.permissions(context))
    }
}
