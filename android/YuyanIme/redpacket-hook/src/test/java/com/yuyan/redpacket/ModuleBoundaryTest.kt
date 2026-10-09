package com.yuyan.redpacket

import android.content.Context
import android.os.Bundle
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ModuleBoundaryTest {
    @Test fun callerMustBeSameUserAndHostOrModule() {
        assertTrue(CallerPolicy.allowed(12810001, 12820002, setOf("com.tencent.mm"), "com.yuyan.redpacket"))
        assertFalse(CallerPolicy.allowed(10001, 12820002, setOf("com.tencent.mm"), "com.yuyan.redpacket"))
        assertFalse(CallerPolicy.allowed(12810001, 12820002, setOf("other"), "com.yuyan.redpacket"))
        assertTrue(CallerPolicy.allowed(12820002, 12820002, emptySet(), "com.yuyan.redpacket"))
    }
    @Test fun providerDefaultsClosedAndRejectsMissingJournalKey() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("packet", 0).edit().clear().commit()
        val provider = Robolectric.buildContentProvider(PacketProvider::class.java).create().get()
        val result = provider.call("config", null, Bundle())!!
        assertEquals("OFF", result.getString("mode"))
        assertFalse(result.getBoolean("allowed"))
        assertFalse(provider.call("reserve", null, Bundle())!!.getBoolean("reserved"))
    }
    @Test fun settingsScreenStartsWithAutoDisabled() {
        val activity = Robolectric.buildActivity(PacketActivity::class.java).setup().get()
        assertNotNull(activity)
        assertEquals(Mode.OFF, ConfigStore(activity.getSharedPreferences("packet", 0)).load().mode)
    }
    @Test fun reflectionRejectsUnknownClassesInsteadOfGuessing() {
        assertNull(Wechat8078.resolve(javaClass.classLoader!!))
    }
    @Test fun parserAcceptsPacketInAndroidTestEnvironment() {
        val xml = "<msg><appmsg><wcpayinfo><nativeurl><![CDATA[wxpay://c2cbizmessagehandler/hongbao/receivehongbao?sendid=abc&msgtype=1&channelid=1]]></nativeurl></wcpayinfo></appmsg></msg>"
        assertNotNull(PacketParser.parse(StoredMessage(436207665, 0, "g@chatroom", xml, 1000), 1, 1000))
    }
}
