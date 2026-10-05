package com.yuyan.imemodule.data.collect

import android.location.Location

internal fun selectInitialLocation(nowMs: Long, locations: List<Location>): Location? {
    val eligible = locations.filter { location ->
        location.hasAccuracy() && LocationUploadPolicy.shouldUpload(nowMs, LocationCandidate(
            location.latitude, location.longitude, location.accuracy, location.time), null)
    }
    val newest = eligible.maxOfOrNull { it.time } ?: return null
    // 精度择优只在相近时间的真实定位中比较，不能用很旧的GPS遮盖刚发生的移动。
    return eligible.filter { newest - it.time <= 15_000 }
        .minWithOrNull(compareBy<Location> { it.accuracy }.thenByDescending { it.time })
}
