package com.yuyan.imemodule.data.usage

import android.content.Context
import android.content.Intent

/** Only completed usage is filtered; diagnostic gaps keep their original meaning. */
internal fun shouldReportUsage(record: UsageRecord, homePackages: Set<String>): Boolean =
    record.kind != "usage" || (record.endMs - record.startMs > 3000L && record.packageName !in homePackages)

/** Query HOME handlers rather than guessing launcher names or enumerating installed apps. */
@Suppress("DEPRECATION")
internal fun usageHomePackages(context: Context): Set<String> = context.packageManager
    .queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
    // Settings exposes a boot fallback HOME activity; Settings itself is not a launcher.
    .filterNot { it.activityInfo?.name == "com.android.settings.FallbackHome" }
    .mapNotNull { it.activityInfo?.packageName }.toSet()
