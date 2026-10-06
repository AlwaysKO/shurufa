package com.yuyan.imemodule.data.collect

internal object BackgroundRefreshPolicy {
    fun intervalMillis(wifi: Boolean): Long = if (wifi) 30 * 60_000L else 2 * 60 * 60_000L
    fun due(now: Long, lastAttempt: Long?, connected: Boolean, wifi: Boolean, userInitiated: Boolean = false): Boolean =
        connected && (userInitiated || lastAttempt == null || now - lastAttempt !in 0 until intervalMillis(wifi))
}
