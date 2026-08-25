package com.yuyan.imemodule.prefs

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SogouT9PreferenceMigrationTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun migratesExistingInstallToSogouDimensionsOnce() {
        val preferences = context.getSharedPreferences("sogou-layout-migration-test", Context.MODE_PRIVATE)
        preferences.edit()
            .clear()
            .putFloat("keyboard_height_ratio", 0.3f)
            .putInt("candidate_size", 55)
            .commit()

        SogouT9PreferenceMigration.migrate(preferences)

        assertEquals(0.278f, preferences.getFloat("keyboard_height_ratio", 0f), 0.0001f)
        assertEquals(45, preferences.getInt("candidate_size", 0))
        assertEquals(1, preferences.getInt("sogou_t9_layout_version", 0))

        preferences.edit().putFloat("keyboard_height_ratio", 0.29f).commit()
        SogouT9PreferenceMigration.migrate(preferences)

        assertEquals(0.29f, preferences.getFloat("keyboard_height_ratio", 0f), 0.0001f)
    }
}
