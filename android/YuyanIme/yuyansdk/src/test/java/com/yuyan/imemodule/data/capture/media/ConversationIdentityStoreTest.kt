package com.yuyan.imemodule.data.capture.media
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class ConversationIdentityStoreTest {
    @Test fun truncatedIdentityIsNotPersistedOrReusedAcrossStoreAndTrackerRestart() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("test-truncated-identities", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val first=WechatTitleStabilizer(PreferenceConversationIdentityStore(prefs)).observe("订单…门店","a".repeat(64),1000)
        val restarted=WechatTitleStabilizer(PreferenceConversationIdentityStore(prefs)).observe("订单…门店","a".repeat(64),400000)
        assertNotEquals(first.externalKey,restarted.externalKey)
        assertTrue(prefs.all.isEmpty())
        assertEquals("truncated",restarted.status)
        assertEquals(.55,restarted.confidence,0.0)
    }
    @Test fun preferencesRestoreConfirmedIdentityWithScopeIsolation() {
        val prefs=RuntimeEnvironment.getApplication().getSharedPreferences("test-identities", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val identity=StoredConversationIdentity("capture-v3:"+"b".repeat(64),"名字","direct")
        PreferenceConversationIdentityStore(prefs).save("wechat|a","a".repeat(64),identity)
        assertEquals(identity,PreferenceConversationIdentityStore(prefs).find("wechat|a","a".repeat(64)))
        assertNull(PreferenceConversationIdentityStore(prefs).find("qq|a","a".repeat(64)))
    }
}
