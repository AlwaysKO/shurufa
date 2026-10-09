package com.yuyan.imemodule.data.collect

import android.content.Context
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.accessibility.AccessibilityEvent
import androidx.preference.PreferenceManager

/** Stores evidence time, never server receipt, wake-up, screen state or window mutations. */
object HumanInteractionRuntime {
    private const val AT = "last_human_interaction_at_v1"
    private const val SOURCE = "last_human_interaction_source_v1"
    private val automation = HumanInteractionAutomationGuard()
    private var automationHistory: HumanInteractionAutomationHistory? = null
    private var lastWake = Long.MIN_VALUE

    @Synchronized fun suppressAutomatedGesture(context: Context) {
        if (!CollectionConsent.enabled(context)) return
        val history = loadAutomationHistory(context)
        val now = System.currentTimeMillis()
        automation.suppress(SystemClock.uptimeMillis(), 1_000L)
        context.applicationContext.getSharedPreferences("human_interaction_automation_v1", Context.MODE_PRIVATE)
            .edit().putString("windows", history.record(now)).apply()
    }

    @Synchronized private fun loadAutomationHistory(context: Context): HumanInteractionAutomationHistory =
        automationHistory ?: HumanInteractionAutomationHistory(
            context.applicationContext.getSharedPreferences("human_interaction_automation_v1", Context.MODE_PRIVATE)
                .getString("windows", "").orEmpty(), System.currentTimeMillis()
        ).also { automationHistory = it }

    /** Called only from the already authorized usage-query window, never pre-consent history. */
    internal fun usageInteraction(context: Context, eventWallTime: Long) {
        if (!CollectionConsent.enabled(context)) return
        val history = loadAutomationHistory(context)
        if (eventWallTime <= 0 || eventWallTime > System.currentTimeMillis() || history.suppressed(eventWallTime)) return
        save(context, HumanInteraction(eventWallTime, "usage_interaction"))
    }

    fun accessibility(context: Context, event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_TOUCH_INTERACTION_START &&
            event.eventType != AccessibilityEvent.TYPE_TOUCH_INTERACTION_END) return
        recordUptime(context, event.eventTime, "touch")
    }

    fun keyboardTouch(context: Context, event: MotionEvent) {
        if (event.deviceId < 0 || !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN) ||
            event.actionMasked !in setOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) return
        recordUptime(context, event.eventTime, "ime_input")
    }

    fun hardwareKey(context: Context, event: KeyEvent) {
        if (event.deviceId < 0 || event.isCanceled || !event.isFromSource(InputDevice.SOURCE_KEYBOARD)) return
        recordUptime(context, event.eventTime, "key")
    }

    private fun recordUptime(context: Context, eventUptime: Long, source: String) {
        if (!CollectionConsent.enabled(context) || automation.suppressed(eventUptime)) return
        val evidence = humanInteractionAt(eventUptime, source, System.currentTimeMillis(), SystemClock.uptimeMillis()) ?: return
        save(context, evidence)
    }

    @Synchronized private fun save(context: Context, evidence: HumanInteraction) {
        if (!CollectionConsent.enabled(context)) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val previous = prefs.getLong(AT, 0)
        if (previous <= System.currentTimeMillis() && evidence.at <= previous) return
        prefs.edit().putLong(AT, evidence.at).putString(SOURCE, evidence.source).apply()
        // Wake existing ordinary-data scheduler; do not start a request per tap or bypass input/game gates.
        val now = SystemClock.elapsedRealtime()
        if (lastWake == Long.MIN_VALUE || now - lastWake >= 30_000L) {
            lastWake = now
            DataCollector.requestSync()
        }
    }

    internal fun snapshot(context: Context): HumanInteraction? {
        if (!CollectionConsent.enabled(context)) return null
        val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
        val at = prefs.getLong(AT, 0)
        val source = prefs.getString(SOURCE, null)
        return if (at > 0 && at <= System.currentTimeMillis() && source in setOf("touch", "key", "ime_input", "usage_interaction")) HumanInteraction(at, requireNotNull(source)) else null
    }
}
