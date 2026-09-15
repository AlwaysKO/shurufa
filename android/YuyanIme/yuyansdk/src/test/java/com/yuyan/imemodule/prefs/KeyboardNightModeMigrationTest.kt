package com.yuyan.imemodule.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class KeyboardNightModeMigrationTest {
    private val preferences = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("keyboard-night-mode-test", Context.MODE_PRIVATE)

    @Before
    fun clear() { preferences.edit().clear().commit() }

    @Test
    fun freshInstallDoesNotFollowSystem() {
        KeyboardNightModeMigration.migrate(preferences)
        assertFalse(preferences.getBoolean("follow_system_dark_mode", true))
    }

    @Test
    fun upgradeDisablesFollowingWithoutLosingSelectedSkin() {
        preferences.edit().putBoolean("follow_system_dark_mode", true)
            .putString("normal_mode_theme", "WechatLayout").commit()
        KeyboardNightModeMigration.migrate(preferences)
        assertFalse(preferences.getBoolean("follow_system_dark_mode", true))
        assertEquals("WechatLayout", preferences.getString("normal_mode_theme", null))
    }

    @Test
    fun upgradeKeepsDaytimeSkinWhenNoNormalSkinWasSaved() {
        preferences.edit().putBoolean("follow_system_dark_mode", true)
            .putString("light_mode_theme", "SogouBlue").commit()
        KeyboardNightModeMigration.migrate(preferences)
        assertEquals("SogouBlue", preferences.getString("normal_mode_theme", null))
    }

    @Test
    fun subsequentLaunchDoesNotOverwriteManualPreferences() {
        KeyboardNightModeMigration.migrate(preferences)
        preferences.edit().putBoolean("follow_system_dark_mode", true)
            .putString("normal_mode_theme", "SogouHuawei").commit()
        KeyboardNightModeMigration.migrate(preferences)
        assertTrue(preferences.getBoolean("follow_system_dark_mode", false))
        assertEquals("SogouHuawei", preferences.getString("normal_mode_theme", null))
    }
}
