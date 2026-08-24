package com.yuyan.imemodule.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.application.Launcher
import org.junit.Assert.assertFalse
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
}
