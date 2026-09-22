package com.yuyan.imemodule.data.collect

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

internal object LocationPermissions {
    // Android 12+ 必须一起申请，系统仍允许用户只授予大致位置。
    fun foregroundRequest(): Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun hasForegroundPermission(context: Context): Boolean = foregroundRequest().any {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}
