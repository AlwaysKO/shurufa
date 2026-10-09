package com.yuyan.imemodule.data.redpacket

import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.RadioButton
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [23])
class SilentPacketSettingsActivityTest {
    @Test fun unsupportedPhoneCanOpenSettingsAndSaveOffWithoutLoadingSilentRuntime() {
        val controller = Robolectric.buildActivity(SilentPacketSettingsActivity::class.java).setup()
        val activity = controller.get()
        val views = descendants(activity.findViewById(android.R.id.content)).toList()
        val auto = views.filterIsInstance<RadioButton>().single { it.tag == "AUTO" }
        assertFalse(auto.isEnabled)
        assertFalse(views.filterIsInstance<Button>().single { it.text == "连接 Shizuku / 请求授权" }.isEnabled)
        views.filterIsInstance<Button>().single { it.text == "保存设置" }.performClick()
        assertEquals("OFF", SilentPacketSettings.mode(activity))
        controller.pause().stop().destroy()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
