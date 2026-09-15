package com.yuyan.imemodule.prefs

import android.content.SharedPreferences
import androidx.core.content.edit

object KeyboardNightModeMigration {
    private const val MIGRATED_KEY = "keyboard_night_mode_independent_v1"

    fun migrate(preferences: SharedPreferences) {
        if (preferences.getBoolean(MIGRATED_KEY, false)) return

        preferences.edit {
            // 没有手动选定皮肤的旧安装，沿用原来的白天皮肤，而非夜间自动套用的皮肤。
            if (preferences.getBoolean("follow_system_dark_mode", true)
                && !preferences.contains("normal_mode_theme")
            ) {
                preferences.getString("light_mode_theme", null)?.let {
                    putString("normal_mode_theme", it)
                }
            }
            putBoolean("follow_system_dark_mode", false)
            putBoolean(MIGRATED_KEY, true)
        }
    }
}
