package com.yuyan.imemodule.data.collect

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.LocationManager

/** Normal boot happens after credential storage unlock; never read preferences during locked boot. */
class LocationRecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED,
                LocationManager.MODE_CHANGED_ACTION)) return
        BalancedLocationService.restore(context)
    }
}
