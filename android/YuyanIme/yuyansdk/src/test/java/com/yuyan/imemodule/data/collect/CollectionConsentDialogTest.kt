package com.yuyan.imemodule.data.collect

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.os.Looper
import androidx.appcompat.app.AlertDialog
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], shadows = [CollectionConsentDialogTest.ConsentCollectorShadow::class])
class CollectionConsentDialogTest {
    // 弹窗测试保留真实授权存储，但不启动定位、联网及进程级后台任务。
    @Implements(value = DataCollector::class, isInAndroidSdk = false)
    class ConsentCollectorShadow {
        @Implementation
        fun setCollectionEnabled(context: Context, enabled: Boolean) {
            CollectionConsent.setEnabled(context, enabled)
        }
    }

    @Test fun `同意后关闭弹窗并保存授权`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        CollectionConsent.setEnabled(activity, false)
        var enabled = false
        try {
            CollectionConsentDialog.show(activity) { enabled = true }
            val first = ShadowDialog.getLatestDialog()
            CollectionConsentDialog.show(activity)
            val dialog = ShadowDialog.getLatestDialog() as AlertDialog
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse("点击同意后不能留下底层弹窗", first.isShowing)
            assertFalse("点击同意后弹窗应关闭", dialog.isShowing)
            assertTrue(CollectionConsent.enabled(activity))
            assertTrue(enabled)
        } finally {
            DataCollector.setCollectionEnabled(activity, false)
            controller.pause().stop().destroy()
        }
    }

    @Test fun `重复请求展示不能叠加弹窗`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        activity.setTheme(androidx.appcompat.R.style.Theme_AppCompat)
        try {
            CollectionConsentDialog.show(activity)
            val first = ShadowDialog.getLatestDialog()
            CollectionConsentDialog.show(activity)
            val second = ShadowDialog.getLatestDialog() as AlertDialog
            assertSame("恢复页面时不能叠加第二个授权弹窗", first, second)
            second.getButton(DialogInterface.BUTTON_NEGATIVE).performClick()
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(first.isShowing)
            assertFalse(CollectionConsent.enabled(activity))

            CollectionConsentDialog.show(activity)
            val reopened = ShadowDialog.getLatestDialog()
            assertNotSame("关闭后应允许用户重新打开授权弹窗", first, reopened)
            assertTrue(reopened.isShowing)
        } finally {
            ShadowDialog.getShownDialogs().forEach { it.dismiss() }
            controller.pause().stop().destroy()
        }
    }
}
