package com.yuyan.imemodule.data.callrecording

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class CallRecordingExpandedConsentTest {
    private val context=ApplicationProvider.getApplicationContext<Context>()
    private val prefs=context.getSharedPreferences(UUID.randomUUID().toString(),0)
    private val consent=CallRecordingConsent(prefs)
    private val device=UUID.randomUUID().toString()
    private val target="https://example.test"

    private fun expanded(device:String=this.device,target:String=this.target):Boolean {
        val method=CallRecordingConsent::class.java.methods.firstOrNull{it.name=="expandedRecordingAllowed"}
        assertNotNull("电话呼出和微信必须具有独立的明确扩展授权判断",method)
        return method!!.invoke(consent,device,target) as Boolean
    }

    @Test fun `旧版组合授权继续支持来电上传但必须重新确认扩展范围`() {
        consent.grant(device,target,true,true)
        prefs.edit().putBoolean("combined_accepted",true).commit()
        assertTrue(consent.recordingAllowed(device,target))
        assertTrue(consent.uploadAllowed(device,target))
        assertTrue("旧版组合授权没有包含呼出及微信",consent.needsCombinedConfirmation(device,target))
        assertFalse(expanded())
    }

    @Test fun `只有明确的新组合确认才持久允许呼出与微信`() {
        consent.grant(device,target,true,true)
        assertFalse(expanded())
        assertTrue(consent.grantCombined(device,target))
        assertEquals("phone_wechat_v2",prefs.getString("recording_scope",null))
        assertTrue(expanded())
        assertFalse(consent.needsCombinedConfirmation(device,target))
        assertFalse(expanded(UUID.randomUUID().toString()))
        assertFalse(expanded(target="https://other.test"))
    }

    @Test fun `撤回立即阻断扩展自录而上传可独立继续`() {
        consent.grantCombined(device,target)
        consent.revokeRecording()
        assertFalse(expanded())
        assertTrue(consent.uploadAllowed(device,target))
    }

    @Test fun `旧授权入口不能继承以前扩展范围`() {
        consent.grantCombined(device,target)
        consent.grant(device,target,true,true)
        assertFalse(expanded())
        assertTrue(consent.needsCombinedConfirmation(device,target))
    }

    @Test fun `迟到的同意不得覆盖较新的撤回`() {
        val revision=consent.revision
        consent.revokeAll()
        assertFalse(consent.grantCombined(device,target,revision))
        assertFalse(expanded())
    }
}
