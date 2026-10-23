package com.yuyan.imemodule.ui.setup

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackgroundSetupTest {
    private val context get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = context.getSharedPreferences("background-setup-test", 0)
    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun onlyShowAfterPrivacyAndKeyboardSetupAndOncePerIncompleteInstall() {
        val state = BackgroundSetupState(prefs)
        assertFalse(state.claimAutoShow(false, true, 100, false, false))
        assertFalse(state.claimAutoShow(true, false, 100, false, false))
        assertTrue(state.claimAutoShow(true, true, 100, false, false))
        assertFalse(state.claimAutoShow(true, true, 100, false, false))
        assertFalse(BackgroundSetupState(prefs).claimAutoShow(true, true, 100, false, false))
        assertTrue(BackgroundSetupState(prefs).claimAutoShow(true, true, 200, false, false))
    }

    @Test fun completedSettingsNeverPromptAgainOnUpgrade() {
        val state = BackgroundSetupState(prefs)
        state.markStartupReviewed()
        assertFalse(state.claimAutoShow(true, true, 100, true, true))
        assertFalse(BackgroundSetupState(prefs).claimAutoShow(true, true, 200, true, true))
        assertTrue(BackgroundSetupState(prefs).claimAutoShow(true, true, 300, false, true))
    }

    @Test fun noAutomaticShowDoesNotMeanSystemSettingsWereCompleted() {
        val state = BackgroundSetupState(prefs)
        state.claimAutoShow(true, true, 100, true, true)
        assertFalse(state.startupReviewed())
        state.markStartupReviewed()
        assertTrue(BackgroundSetupState(prefs).startupReviewed())
    }

    @Test fun accessibilityPermissionIsReadAgainInsteadOfRememberingOldGrant() {
        val component = "${context.packageName}/com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService"
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, component)
        assertTrue(BackgroundSetup.accessibilityEnabled(context))
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "$component.other")
        assertFalse(BackgroundSetup.accessibilityEnabled(context))
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
        assertFalse(BackgroundSetup.accessibilityEnabled(context))
    }

    @Test fun vendorPermissionDenialFallsBackWithoutClaimingSuccess() {
        val attempted = mutableListOf<Intent>()
        val intents = BackgroundSetup.startupIntents("HONOR", context.packageName)
        assertTrue(BackgroundSetup.openFirst(intents) {
            attempted.add(it)
            if (it.component != null) throw SecurityException("START_MODULE_UI")
        })
        assertTrue(attempted.size > 1)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempted.last().action)
        assertFalse(BackgroundSetupState(prefs).startupReviewed())
    }

    @Test fun unknownBrandAndUnavailableSettingsFailSafely() {
        val intents = BackgroundSetup.startupIntents("unknown", context.packageName)
        assertNull(intents.first().component)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intents.first().action)
        assertFalse(BackgroundSetup.openFirst(intents) { throw ActivityNotFoundException() })
    }
}
