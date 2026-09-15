package com.yuyan.imemodule.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AppPrefsDefaultsTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        Launcher::class.java.getDeclaredField("context").apply {
            isAccessible = true
            set(Launcher.instance, context)
        }
    }

    @Test
    fun fullDisplayShortcutBarIsDisabledByDefault() {
        val preferences = context.getSharedPreferences("defaults-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        assertFalse(AppPrefs(preferences).internal.fullDisplayKeyboardEnable.getValue())
    }

    @Test
    fun sogouT9DimensionsAreTheDefaults() {
        val preferences = context.getSharedPreferences("sogou-layout-defaults-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()
        val appPrefs = AppPrefs(preferences)

        assertEquals(0.278f, appPrefs.internal.keyboardHeightRatio.getValue(), 0.0001f)
        assertEquals(45, appPrefs.keyboardSetting.candidateTextSize.getValue())
    }

    @Test
    fun aiStickerIsEnabledByDefaultAndPersistsDisabledValue() {
        val preferences = context.getSharedPreferences("ai-sticker-defaults-test", Context.MODE_PRIVATE)
        preferences.edit().clear().commit()

        assertTrue(AppPrefs(preferences).internal.aiStickerEnabled.getValue())

        AppPrefs(preferences).internal.aiStickerEnabled.setValue(false)

        assertFalse(AppPrefs(preferences).internal.aiStickerEnabled.getValue())
    }
}
