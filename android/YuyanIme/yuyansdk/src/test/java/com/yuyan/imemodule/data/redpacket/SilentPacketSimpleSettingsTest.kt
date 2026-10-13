package com.yuyan.imemodule.data.redpacket

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.RadioButton
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SilentPacketSimpleSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit() }
    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (i in 0 until view.childCount) yieldAll(descendants(view.getChildAt(i)))
    }
    @Test fun freshInstallOffersAutomaticRoutingWithoutManualGroupEntryAndStaysOff() {
        val controller = Robolectric.buildActivity(SilentPacketSettingsActivity::class.java).setup()
        try {
            val views = descendants(controller.get().findViewById(android.R.id.content)).toList()
            assertTrue(views.filterIsInstance<CheckBox>().single { it.tag == "auto_user" }.isChecked)
            assertTrue(views.filterIsInstance<CheckBox>().single { it.tag == "auto_groups" }.isChecked)
            assertEquals(View.GONE, views.filterIsInstance<EditText>().single().visibility)
            assertTrue(views.filterIsInstance<RadioButton>().single { it.tag == "OFF" }.isChecked)
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun existingManualAccountAndWhitelistAreNotExpandedWhenOpeningSettings() {
        SilentPacketSettings.setUserId(context, 0)
        SilentPacketSettings.setGroups(context, "原指定群")
        val controller = Robolectric.buildActivity(SilentPacketSettingsActivity::class.java).setup()
        try {
            val views = descendants(controller.get().findViewById(android.R.id.content)).toList()
            assertFalse(views.filterIsInstance<CheckBox>().single { it.tag == "auto_user" }.isChecked)
            assertFalse(views.filterIsInstance<CheckBox>().single { it.tag == "auto_groups" }.isChecked)
            assertEquals("原指定群", views.filterIsInstance<EditText>().single().text.toString())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun preparationHasAnOfficialDownloadEntryAndTechnicalDetailsAreHiddenInitially() {
        val controller = Robolectric.buildActivity(SilentPacketSettingsActivity::class.java).setup()
        try {
            val views = descendants(controller.get().findViewById(android.R.id.content)).toList()
            assertTrue(views.filterIsInstance<Button>().any { it.text == "下载 Shizuku（官方）" })
            assertTrue(views.filterIsInstance<Button>().any { it.text == "查看技术诊断" })
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun enablingAutoBeforePreparationExplainsTheMissingStep() {
        val controller = Robolectric.buildActivity(SilentPacketSettingsActivity::class.java).setup()
        try {
            val views = descendants(controller.get().findViewById(android.R.id.content)).toList()
            views.filterIsInstance<RadioButton>().single { it.tag == "AUTO" }.performClick()
            views.filterIsInstance<Button>().single { it.text == "保存设置" }.performClick()
            assertEquals("OFF", SilentPacketSettings.mode(context))
            assertTrue(ShadowToast.getTextOfLatestToast().toString().contains("安装 Shizuku"))
        } finally { controller.pause().stop().destroy() }
    }
}
