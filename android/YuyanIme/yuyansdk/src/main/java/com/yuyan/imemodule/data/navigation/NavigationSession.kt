package com.yuyan.imemodule.data.navigation

import java.util.UUID

internal data class NavigationRoute(val platform: String, val origin: String, val destination: String)
internal data class NavigationRecord(val id: String, val route: NavigationRoute, val image: ByteArray, val overviewAt: Long, val startedAt: Long)
internal class NavigationSession {
    private var pending: NavigationRecord? = null
    private var clickedAt: Long? = null
    private var confirmed: NavigationRecord? = null

    fun preview(route: NavigationRoute, image: ByteArray, now: Long, startClickAt: Long? = null) {
        if (image.isEmpty()) return
        val old = pending
        pending = NavigationRecord(if (old?.route == route) old.id else UUID.randomUUID().toString(), route, image, now, 0)
        if (old?.route != route) clickedAt = null
        if (startClickAt != null) clickedAt = startClickAt
        confirmed = null
    }

    fun startClicked(platform: String, now: Long) {
        if (pending?.route?.platform == platform) clickedAt = now
    }

    /** 只在调用方已经确认导航中页面时调用。失败入队仍保留同一记录ID。 */
    fun confirm(platform: String, destination: String?, now: Long): NavigationRecord? {
        val candidate = pending ?: return null
        if (candidate.route.platform != platform || now - candidate.overviewAt !in 0..300_000) return null
        if (destination != null && candidate.route.destination != destination) return null
        val clickMatches = clickedAt?.let { now - it in 0..15_000 } == true
        if (!clickMatches && destination != candidate.route.destination) return null
        return confirmed ?: candidate.copy(startedAt = now).also { confirmed = it }
    }

    fun persisted(id: String) { if (pending?.id == id) abandon() }
    fun expire(now: Long) { pending?.let { if (now - it.overviewAt !in 0..300_000) abandon() } }
    fun transition(now: Long) { if (clickedAt?.let { now - it in 0..15_000 } != true) abandon() }
    fun hasPreview(): Boolean = pending != null
    fun overviewChanged(route: NavigationRoute? = null): Long? {
        val sameRouteClick = if (pending?.route == route) clickedAt else null
        abandon()
        return sameRouteClick
    }
    fun abandon() { pending = null; clickedAt = null; confirmed = null }
}
