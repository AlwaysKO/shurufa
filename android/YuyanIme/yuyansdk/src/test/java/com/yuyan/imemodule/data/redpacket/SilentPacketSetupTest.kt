package com.yuyan.imemodule.data.redpacket

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class SilentPacketSetupTest {
    private val ready = SilentPacketSetupSnapshot(true, true, true, true, true, true)
    @Test fun nextStepRequiresEachPreparationInOrder() {
        assertEquals(SilentPacketSetupStep.SYSTEM_UNSUPPORTED, ready.copy(supported = false).nextStep)
        assertEquals(SilentPacketSetupStep.INSTALL_SHIZUKU, ready.copy(shizukuInstalled = false).nextStep)
        assertEquals(SilentPacketSetupStep.START_SHIZUKU, ready.copy(shizukuRunning = false).nextStep)
        assertEquals(SilentPacketSetupStep.AUTHORIZE_SHIZUKU, ready.copy(shizukuAuthorized = false).nextStep)
        assertEquals(SilentPacketSetupStep.NOTIFICATION, ready.copy(notificationAuthorized = false).nextStep)
        assertEquals(SilentPacketSetupStep.ACCESSIBILITY, ready.copy(accessibilityAuthorized = false).nextStep)
        assertEquals(SilentPacketSetupStep.READY, ready.nextStep)
    }
    @Test fun everyIncompleteStateHasConcreteHumanInstruction() {
        val states = listOf(ready.copy(supported = false), ready.copy(shizukuInstalled = false),
            ready.copy(shizukuRunning = false), ready.copy(shizukuAuthorized = false),
            ready.copy(notificationAuthorized = false), ready.copy(accessibilityAuthorized = false), ready)
        val messages = states.map { it.render() }
        assertEquals(states.size, messages.toSet().size)
        assertTrue(messages.all { it.isNotBlank() })
        assertTrue(messages[0].contains("Android 15"))
        assertTrue(messages[2].contains("重启"))
        assertTrue(messages[4].contains("通知"))
        assertTrue(messages[5].contains("无障碍"))
    }
    @Test fun externalResourcesUseOnlyFixedOfficialHttpsPages() {
        val download = SilentPacketSetup.downloadIntent()
        val guide = SilentPacketSetup.guideIntent()
        assertEquals(Intent.ACTION_VIEW, download.action)
        assertEquals("https://shizuku.rikka.app/zh-hans/download/", download.dataString)
        assertEquals("https://shizuku.rikka.app/zh-hans/guide/setup/", guide.dataString)
    }
    @Test fun systemEntryIntentsOnlyOpenRequestedSettings() {
        assertEquals(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, SilentPacketSetup.developerSettingsIntent().action)
        assertEquals(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, SilentPacketSetup.notificationSettingsIntent().action)
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, SilentPacketSetup.accessibilitySettingsIntent().action)
    }
    @Test fun absentShizukuHasExplicitManualFallback() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertNull(SilentPacketSetup.shizukuIntent(context))
        val message = SilentPacketSetup.open(context, SilentPacketSetupStep.START_SHIZUKU)
        assertNotNull(message)
        assertTrue(message!!.contains("Shizuku"))
    }
    @Test fun missingBrowserDoesNotDownloadOrPretendSuccess() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val message = SilentPacketSetup.open(context, SilentPacketSetupStep.INSTALL_SHIZUKU)
        assertNotNull(message)
        assertTrue(message!!.contains("https://shizuku.rikka.app/zh-hans/download/"))
    }
    @Test fun supportedSystemWithoutManagerShowsInstallInsteadOfAuthorization() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val setup = SilentPacketSetup.collect(context)
        assertTrue(setup.supported)
        assertFalse(setup.shizukuInstalled)
        assertEquals(SilentPacketSetupStep.INSTALL_SHIZUKU, setup.nextStep)
    }
    @Test @Config(sdk = [23]) fun oldAndroidCanCollectWithoutLoadingShizuku() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val setup = SilentPacketSetup.collect(context)
        assertFalse(setup.supported)
        assertFalse(setup.shizukuRunning)
        assertFalse(setup.shizukuAuthorized)
        assertEquals(SilentPacketSetupStep.SYSTEM_UNSUPPORTED, setup.nextStep)
    }
}
