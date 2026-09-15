package com.yuyan.imemodule.prefs

import android.content.SharedPreferences
import androidx.core.content.edit
import com.yuyan.imemodule.keyboard.SogouT9Layout

object SogouT9PreferenceMigration {
    private const val VERSION_KEY = "sogou_t9_layout_version"
    private const val CURRENT_VERSION = 1

    fun migrate(preferences: SharedPreferences) {
        if (preferences.getInt(VERSION_KEY, 0) >= CURRENT_VERSION) return

        preferences.edit {
            putFloat("keyboard_height_ratio", SogouT9Layout.KEYBOARD_HEIGHT_RATIO)
            putInt("candidate_size", SogouT9Layout.CANDIDATE_TEXT_SIZE_PERCENT)
            putInt(VERSION_KEY, CURRENT_VERSION)
        }
    }
}
