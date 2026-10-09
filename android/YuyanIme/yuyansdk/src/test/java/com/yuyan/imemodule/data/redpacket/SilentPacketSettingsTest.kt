package com.yuyan.imemodule.data.redpacket

import android.content.Context
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class SilentPacketSettingsTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun reset() { PreferenceManager.getDefaultSharedPreferences(context).edit().clear().commit() }
    @Test fun defaultsNeverChooseAnAccountOrEnableAutomation() {
        assertEquals("OFF", SilentPacketSettings.mode(context))
        assertEquals(-1, SilentPacketSettings.userId(context))
        assertTrue(SilentPacketSettings.groups(context).isEmpty())
    }
    @Test fun enablingSilentModeDisablesVisibleAssistantWithoutReenablingOnOff() {
        PacketSettings.setEnabled(context, true)
        SilentPacketSettings.setMode(context, "PROBE")
        assertFalse(PacketSettings.enabled(context))
        assertEquals("PROBE", SilentPacketSettings.mode(context))
        SilentPacketSettings.setMode(context, "AUTO")
        assertEquals("AUTO", SilentPacketSettings.mode(context))
        SilentPacketSettings.setMode(context, "OFF")
        assertFalse(PacketSettings.enabled(context))
    }
    @Test fun whitelistUsesExactTrimmedLinesNotSubstringOrCommaSplitting() {
        SilentPacketSettings.setGroups(context, " 测试群 \n\n测试群\r\n测试群二\n甲,乙")
        assertEquals(setOf("测试群", "测试群二", "甲,乙"), SilentPacketSettings.groups(context))
        assertFalse("测试" in SilentPacketSettings.groups(context))
    }
    @Test fun accountSelectionPersistsAndCanBeCleared() {
        SilentPacketSettings.setUserId(context, 128)
        assertEquals(128, SilentPacketSettings.userId(context))
        SilentPacketSettings.setUserId(context, -1)
        assertEquals(-1, SilentPacketSettings.userId(context))
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsUnknownMode() {
        SilentPacketSettings.setMode(context, "anything")
    }
    @Test(expected = IllegalArgumentException::class) fun rejectsInvalidUserId() {
        SilentPacketSettings.setUserId(context, -2)
    }
}
