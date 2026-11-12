package com.yuyan.imemodule.data.callrecording

import android.Manifest
import android.app.Application
import android.content.Context
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.ui.activity.CallRecordingSettingsActivity
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[30])
class CallRecordingTrashAuthorizationTest {
    private fun descendants(view:View):Sequence<View> = sequence {
        yield(view)
        if(view is ViewGroup)for(i in 0 until view.childCount)yieldAll(descendants(view.getChildAt(i)))
    }
    private fun until(label:String="condition",condition:()->Boolean) {
        val deadline=System.nanoTime()+5_000_000_000L
        while(!condition() && System.nanoTime()<deadline){shadowOf(Looper.getMainLooper()).idle();Thread.sleep(10)}
        assertTrue(label+" toast="+org.robolectric.shadows.ShadowToast.getTextOfLatestToast(),condition())
    }
    @Test fun `原授权入口串联已有音频权限和所有文件权限取消返回不重复弹出`() {
        ShadowBuild.setManufacturer("HONOR")
        val app=ApplicationProvider.getApplicationContext<Application>()
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,true).commit()
        CallRecordingRuntime.preferences(app).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO,Manifest.permission.READ_PHONE_STATE,Manifest.permission.READ_EXTERNAL_STORAGE)
        val controller=Robolectric.buildActivity(CallRecordingSettingsActivity::class.java)
        val activity=controller.get();activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        controller.create().start().resume().visible()
        try {
            descendants(activity.findViewById(android.R.id.content)).filterIsInstance<Button>().single{it.text=="授权并立即查找"}.performClick()
            until{ShadowDialog.getLatestDialog()?.isShowing==true}
            val dialog=ShadowDialog.getLatestDialog() as AlertDialog
            assertTrue(dialog.findViewById<android.widget.TextView>(android.R.id.message)!!.text.contains("最近30天荣耀回收站录音"))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            until("scope"){CallRecordingRuntime.consent(app).hasTrashScope}
            until("intent files="+SystemRecordingTrash.hasFileAccess()){shadowOf(activity).peekNextStartedActivityForResult()!=null}
            val launched=shadowOf(activity).nextStartedActivityForResult
            val intent=launched.intent
            assertEquals(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,intent.action)
            assertEquals("package:${activity.packageName}",intent.dataString)
            activity.activityResultRegistry.dispatchResult(launched.requestCode,android.app.Activity.RESULT_CANCELED,null)
            controller.pause().resume();shadowOf(Looper.getMainLooper()).idle()
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            assertFalse(SystemRecordingTrash.hasFileAccess())
        }finally{CallRecordingRuntime.consent(app).revokeAll();CallRecordingJobService.cancel(app);controller.pause().stop().destroy()}
    }
}
