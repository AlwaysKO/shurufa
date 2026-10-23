package com.yuyan.imemodule.ui.setup

import android.content.Context
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import com.yuyan.imemodule.service.capture.PassiveChatAccessibilityService

internal class BackgroundSetupState(private val prefs: SharedPreferences) {
    fun claimAutoShow(privacyAccepted: Boolean, keyboardReady: Boolean, installTime: Long,
        batteryExempt: Boolean, accessibilityEnabled: Boolean): Boolean {
        if (!privacyAccepted || !keyboardReady || installTime <= 0) return false
        if (batteryExempt && accessibilityEnabled && startupReviewed()) return false
        if (prefs.getLong("prompted_install", -1) == installTime) return false
        return prefs.edit().putLong("prompted_install", installTime).commit()
    }
    fun startupReviewed(): Boolean = prefs.getBoolean("startup_reviewed", false)
    fun markStartupReviewed() { prefs.edit().putBoolean("startup_reviewed", true).apply() }
}

internal object BackgroundSetup {
    fun state(context: Context) = BackgroundSetupState(context.getSharedPreferences("background_setup_v1", Context.MODE_PRIVATE))
    fun batteryExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun accessibilityEnabled(context: Context): Boolean {
        val expected = ComponentName(context, PassiveChatAccessibilityService::class.java)
        return Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty().split(':').any { ComponentName.unflattenFromString(it) == expected }
    }

    fun startupIntents(brand: String, packageName: String): List<Intent> = buildList {
        if (brand.equals("honor", true)) add(Intent().setComponent(ComponentName(
            "com.hihonor.systemmanager", "com.hihonor.systemmanager.appcontrol.activity.StartupAppControlActivity")))
        if (brand.equals("huawei", true)) add(Intent().setComponent(ComponentName(
            "com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")))
        add(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        add(Intent(Settings.ACTION_SETTINGS))
    }

    fun openFirst(intents: List<Intent>, start: (Intent) -> Unit): Boolean {
        for (intent in intents) {
            try { start(intent); return true }
            catch (_: ActivityNotFoundException) { /* 尝试系统公开入口。 */ }
            catch (_: SecurityException) { /* 部分厂商页面仅允许系统应用直接打开。 */ }
        }
        return false
    }
}
