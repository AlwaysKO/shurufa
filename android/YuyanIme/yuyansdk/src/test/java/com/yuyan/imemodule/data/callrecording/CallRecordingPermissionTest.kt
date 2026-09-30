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
    @Test fun `通知未允许时显示具体阻断且不能当作权限齐全`() {
        val context=ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE)
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(false)
        assertEquals("notifications_required",CallRecordingService.permissionBlock(context))
        assertFalse(CallRecordingService.permissions(context))
        shadowOf(manager).setNotificationsEnabled(true)
        assertNull(CallRecordingService.permissionBlock(context))
    }

    @Test fun `通知通道关闭时引导到对应通道而非假装可运行`() {
        val context=ApplicationProvider.getApplicationContext<android.app.Application>()
        shadowOf(context).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE)
        val manager=context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(manager).setNotificationsEnabled(true)
        manager.createNotificationChannel(android.app.NotificationChannel("consented_call_audio_v1","录音",NotificationManager.IMPORTANCE_NONE))
        assertEquals("notification_channel_required",CallRecordingService.permissionBlock(context))
        val intent=CallRecordingService.notificationSettingsIntent(context)
        assertEquals(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS,intent.action)
        assertEquals(context.packageName,intent.getStringExtra(android.provider.Settings.EXTRA_APP_PACKAGE))
        assertEquals("consented_call_audio_v1",intent.getStringExtra(android.provider.Settings.EXTRA_CHANNEL_ID))
    }
}
