package com.yuyan.imemodule.data.navigation

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

internal data class NavigationRoute(val platform: String, val origin: String, val destination: String)
internal data class NavigationRecord(val id: String, val route: NavigationRoute, val image: ByteArray, val overviewAt: Long, val startedAt: Long)

/** 同一天同一地图的相同起终点只记录一次；交通/时间标签变化不改变身份。 */
internal fun navigationOverviewId(route: NavigationRoute, at: Long, zone: TimeZone = TimeZone.getDefault()): String {
    val day = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).apply { timeZone = zone }.format(java.util.Date(at))
    val key = listOf("route-overview-v1", day, route.platform, route.origin, route.destination)
        .joinToString("") { "${it.length}:$it" }
    return UUID.nameUUIDFromBytes(key.toByteArray(Charsets.UTF_8)).toString()
}

/** 持久去重先于截图，失败不记成功。由 NavigationCapture 的单消费者串行调用。 */
internal class NavigationSession(private val outbox: NavigationOutbox, private val zone: () -> TimeZone = TimeZone::getDefault) {
    suspend fun saveOverview(route: NavigationRoute, at: Long, allowed: () -> Boolean, capture: suspend () -> ByteArray?): Boolean {
        if (!allowed()) return false
        val id = navigationOverviewId(route, at, zone())
        if (outbox.contains(id)) return false
        val bytes = capture() ?: return false
        if (!allowed()) return false
        // started_at 为旧服务端必填兼容字段；新记录使用截图时刻，不能据此断言已开始导航。
        return outbox.enqueue(NavigationRecord(id, route, bytes, at, at), allowed)
    }
}
