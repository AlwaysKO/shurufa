package com.yuyan.imemodule.data.navigation

import android.content.Context
import android.os.Build
import androidx.preference.PreferenceManager
import com.yuyan.imemodule.data.collect.CollectionConsent
import java.util.concurrent.atomic.AtomicLong

object NavigationSettings {
    const val KEY = "navigation_recording_v1"
    internal val generation = AtomicLong()
    fun selected(context: Context): Boolean = PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, false)
    fun enabled(context: Context): Boolean = Build.VERSION.SDK_INT >= 30 && selected(context) && CollectionConsent.enabled(context)
    internal fun uploadAllowed(context: Context, version: Long) = generation.get() == version && enabled(context)
    fun setEnabled(context: Context, enabled: Boolean) {
        check(PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, enabled).commit())
        generation.incrementAndGet()
        if (!enabled) NavigationSync.cancel()
    }
}
