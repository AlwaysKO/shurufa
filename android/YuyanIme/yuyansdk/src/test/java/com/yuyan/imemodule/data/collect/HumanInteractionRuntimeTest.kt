package com.yuyan.imemodule.data.collect

import android.content.Context
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class HumanInteractionRuntimeTest {
    @Test fun `only touch evidence under consent persists and background events do not advance it`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().putBoolean(CollectionConsent.KEY, false).commit()
        val touch = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_TOUCH_INTERACTION_START).apply {
            eventTime = SystemClock.uptimeMillis()
        }
        HumanInteractionRuntime.accessibility(context, touch)
        assertNull(HumanInteractionRuntime.snapshot(context))
        prefs.edit().putBoolean(CollectionConsent.KEY, true).commit()
        HumanInteractionRuntime.accessibility(context, touch)
        val actual = HumanInteractionRuntime.snapshot(context)
        assertNotNull(actual)
        val background = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED).apply {
            eventTime = SystemClock.uptimeMillis()
        }
        HumanInteractionRuntime.accessibility(context, background)
        assertEquals(actual, HumanInteractionRuntime.snapshot(context))
        assertEquals(actual!!.at, prefs.getLong("last_human_interaction_at_v1", 0))
        prefs.edit().putBoolean(CollectionConsent.KEY, false).commit()
        assertNull(HumanInteractionRuntime.snapshot(context))
        touch.recycle(); background.recycle()
    }
    @Test fun `usage evidence uses system timestamp and rejects future and synthetic actions`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().putBoolean(CollectionConsent.KEY, true).commit()
        val original = System.currentTimeMillis() - 5_000
        HumanInteractionRuntime.usageInteraction(context, original)
        assertEquals(HumanInteraction(original, "usage_interaction"), HumanInteractionRuntime.snapshot(context))
        HumanInteractionRuntime.usageInteraction(context, System.currentTimeMillis() + 60_000)
        HumanInteractionRuntime.usageInteraction(context, original - 1)
        assertEquals(original, HumanInteractionRuntime.snapshot(context)!!.at)
        HumanInteractionRuntime.suppressAutomatedGesture(context)
        HumanInteractionRuntime.usageInteraction(context, System.currentTimeMillis())
        assertEquals(original, HumanInteractionRuntime.snapshot(context)!!.at)
    }

    @Test fun `future cached clock does not block subsequent valid user evidence`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val now = System.currentTimeMillis()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        prefs.edit().clear().putBoolean(CollectionConsent.KEY, true)
            .putLong("last_human_interaction_at_v1", now + 3_600_000)
            .putString("last_human_interaction_source_v1", "touch").commit()
        assertNull(HumanInteractionRuntime.snapshot(context))
        HumanInteractionRuntime.usageInteraction(context, now - 100)
        assertEquals(HumanInteraction(now - 100, "usage_interaction"), HumanInteractionRuntime.snapshot(context))
    }

}
