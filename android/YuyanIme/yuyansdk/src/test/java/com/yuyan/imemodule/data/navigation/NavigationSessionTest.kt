package com.yuyan.imemodule.data.navigation

import java.io.File
import java.util.TimeZone
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class NavigationSessionTest {
    @get:Rule val folder = TemporaryFolder()
    private val route = NavigationRoute("amap", "我的位置", "测试车站")
    private val image = byteArrayOf(1, 2, 3)
    private val zone = TimeZone.getTimeZone("Asia/Shanghai")
    private val at = 1790827200000L // 2026-10-01 12:00 上海时间
    private fun state(dir: File) = NavigationSession(NavigationOutbox(dir)) { zone }

    @Test fun selectedOverviewIsPersistedWithoutAnyStartClick() = runBlocking {
        val dir = folder.newFolder()
        assertTrue(state(dir).saveOverview(route, at, { true }) { image })
        val json = JSONObject(dir.listFiles()!!.single { it.extension == "json" }.readText())
        assertEquals(at, json.getLong("overview_at"))
        assertEquals(at, json.getLong("started_at")) // 旧上传协议兼容时间，不表示已经导航。
        assertEquals(route.destination, json.getString("destination"))
    }
    @Test fun sameRouteIsDeduplicatedBeforeScreenshotIncludingProcessRestart() = runBlocking {
        val dir = folder.newFolder(); var captures = 0
        val state = state(dir)
        assertTrue(state.saveOverview(route, at, { true }) { captures++; image })
        assertFalse(state.saveOverview(route, at + 60_000, { true }) { captures++; image })
        assertFalse(state(dir).saveOverview(route, at + 120_000, { true }) { captures++; image })
        assertEquals(1, captures)
        assertEquals(1, NavigationOutbox(dir).count())
    }
    @Test fun returningAfterAnotherRouteAndSuccessfulUploadDoesNotRescreenshot() = runBlocking {
        val dir = folder.newFolder(); val state = state(dir)
        assertTrue(state.saveOverview(route, at, { true }) { image })
        assertTrue(state.saveOverview(route.copy(destination = "测试公园"), at, { true }) { image })
        NavigationOutbox(dir).drain({ true }) { body ->
            val r = JSONObject(body)
            JSONObject().put("ok", true).put("id", r.getString("id")).put("sha256", r.getString("sha256")).toString()
        }
        assertEquals(0, NavigationOutbox(dir).count())
        assertFalse(state(dir).saveOverview(route, at + 60_000, { true }) { fail("已传记录也不能重拍"); image })
    }
    @Test fun nextLocalDayChangedEndpointOrPlatformCanBeRecorded() = runBlocking {
        val dir = folder.newFolder(); val state = state(dir)
        for ((r, time) in listOf(route to at, route to (at + 86_400_000),
            route.copy(origin = "测试广场") to at, route.copy(destination = "测试公园") to at,
            route.copy(platform = "baidu") to at)) {
            assertTrue(state.saveOverview(r, time, { true }) { image })
        }
        assertEquals(5, NavigationOutbox(dir).count())
    }
    @Test fun screenshotFailureRevocationAndPersistenceFailureCanRetry() = runBlocking {
        val dir = folder.newFolder(); val state = state(dir)
        assertFalse(state.saveOverview(route, at, { true }) { null })
        assertFalse(state.saveOverview(route, at, { false }) { fail("撤回同意不能截图"); image })
        var allowed = true
        assertFalse(state.saveOverview(route, at, { allowed }) { allowed = false; image })
        assertEquals(0, NavigationOutbox(dir).count())
        assertTrue(state.saveOverview(route, at, { true }) { image })
        val blocked = folder.newFile()
        assertFalse(state(blocked).saveOverview(route, at, { true }) { image })
        assertTrue(blocked.delete()); assertTrue(blocked.mkdir())
        assertTrue(state(blocked).saveOverview(route, at, { true }) { image })
    }
    @Test fun routeKeyUsesCalendarDayAndUnambiguousEndpointBoundaries() {
        assertEquals(navigationOverviewId(route, at, zone), navigationOverviewId(route, at + 60_000, zone))
        assertNotEquals(navigationOverviewId(route, at, zone), navigationOverviewId(route, at + 43_200_000, zone))
        assertNotEquals(navigationOverviewId(route.copy(origin = "a|b", destination = "c"), at, zone),
            navigationOverviewId(route.copy(origin = "a", destination = "b|c"), at, zone))
    }
    @Test fun runningServiceUsesCurrentPhoneTimezoneAfterTimezoneChange() = runBlocking {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val state = NavigationSession(NavigationOutbox(folder.newFolder()))
            assertTrue(state.saveOverview(route, at, { true }) { image })
            TimeZone.setDefault(TimeZone.getTimeZone("GMT-12:00"))
            assertTrue(state.saveOverview(route, at, { true }) { image })
        } finally { TimeZone.setDefault(original) }
    }
}
