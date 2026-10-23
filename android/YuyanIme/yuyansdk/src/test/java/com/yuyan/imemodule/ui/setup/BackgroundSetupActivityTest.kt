package com.yuyan.imemodule.ui.setup

import android.app.Application
import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [28])
class BackgroundSetupActivityTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() {
        context.getSharedPreferences("background_setup_v1", 0).edit().clear().commit()
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
    }
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()
    private fun button(activity: BackgroundSetupActivity, text: String) =
        views(activity.window.decorView).filterIsInstance<Button>().first { it.text.toString() == text }
    private fun texts(activity: BackgroundSetupActivity) = views(activity.window.decorView)
        .filterIsInstance<TextView>().map { it.text.toString() }

    @Test fun openingSettingsDoesNotClaimPermissionAndReturningRefreshesRealState() {
        val controller = Robolectric.buildActivity(BackgroundSetupActivity::class.java)
        controller.get().setTheme(R.style.Theme_AppTheme)
        controller.setup()
        val activity = controller.get()
        assertTrue(texts(activity).contains("无障碍：未开启"))
        button(activity, "打开无障碍设置").performClick()
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, shadowOf(activity).nextStartedActivity.action)
        assertFalse(BackgroundSetup.accessibilityEnabled(activity))
        controller.pause()
        Settings.Secure.putString(activity.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            "${activity.packageName}/com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService")
        shadowOf(activity.getSystemService(PowerManager::class.java)).setIgnoringBatteryOptimizations(activity.packageName, true)
        BackgroundSetup.state(activity).markStartupReviewed()
        controller.resume()
        assertTrue(texts(activity).contains("电池优化：已允许后台运行"))
        assertTrue(texts(activity).contains("无障碍：已开启，等待系统连接"))
        assertFalse(button(activity, "允许后台运行").isEnabled)
        button(activity, "完成").performClick()
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }

    @Test fun skipDoesNotGrantPermissionsOrConfirmManufacturerSettings() {
        val controller = Robolectric.buildActivity(BackgroundSetupActivity::class.java)
        controller.get().setTheme(R.style.Theme_AppTheme)
        controller.setup()
        val activity = controller.get()
        button(activity, "稍后设置").performClick()
        assertTrue(activity.isFinishing)
        assertFalse(BackgroundSetup.accessibilityEnabled(activity))
        assertFalse(BackgroundSetup.state(activity).startupReviewed())
        assertNull(shadowOf(activity).nextStartedActivity)
        controller.pause().stop().destroy()
    }
}
