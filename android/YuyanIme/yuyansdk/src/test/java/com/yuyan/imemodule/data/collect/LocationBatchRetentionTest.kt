package com.yuyan.imemodule.data.collect

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.OffsetDateTime
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class LocationBatchRetentionTest {
    private lateinit var app: Application
    private lateinit var store: LocalInputStore
    private lateinit var name: String
    private val start = 1_800_000_000_000L
    private val baselineKey = "collector_last_location_uploaded_at"

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().clear().putBoolean(CollectionConsent.KEY, true).commit()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        name = "location-retention-${UUID.randomUUID()}.db"
        store = LocalInputStore(app, name)
        setField("prefs", prefs)
        setField("appContext", app)
        setField("eventStore", store)
        setField("balancedLocationOwner", true)
        resetJumpFilter()
        ServerConfig.init(app)
    }

    @After fun cleanup() {
        cancelWake()
        setField("eventStore", null)
        setField("appContext", null)
        setField("prefs", null)
        setField("balancedLocationOwner", false)
        store.close()
        app.deleteDatabase(name)
    }

    @Test fun `first known cache neither queues nor poisons same timestamp real fix`() = runBlocking {
        val cached = point(start).apply {
            extras = android.os.Bundle().apply { putString("realProvider", "GNSS_CACHE") }
        }
        DataCollector.persistLocation(app, cached, true) { start }
        cancelWake()
        assertTrue(store.pendingReports(ServerConfig.baseUrl).isEmpty())
        assertFalse(PreferenceManager.getDefaultSharedPreferences(app).contains(baselineKey))
        DataCollector.persistLocation(app, point(start), true) { start }
        cancelWake()
        assertEquals(1, store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `current trusted walking speed retains independent minute points below displacement floor`() = runBlocking {
        for (minute in 0..3) {
            val walking = point(start + minute * 60_000, 23.13 + minute * 5.0 / 111_195).apply {
                accuracy = 5f; speed = 1.17f; speedAccuracyMetersPerSecond = .2f
            }
            DataCollector.persistLocation(app, walking, true) { walking.time }
            cancelWake()
        }
        assertEquals("credible current walking must retain each minute, even for five metre endpoints", 4,
            store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `current trusted movement retains a fresh minute with the same endpoint`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start }
        cancelWake()
        val returned = point(start + 60_000).apply {
            accuracy = 5f; speed = 1.17f; speedAccuracyMetersPerSecond = .2f
        }
        DataCollector.persistLocation(app, returned, true) { returned.time }
        cancelWake()
        assertEquals("current speed is evidence even if a loop ends at the same coordinate", 2,
            store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `failed small movement persistence can retry that same observations own motion evidence`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start }
        cancelWake()
        val walking = point(start + 60_000, 23.13 + 5.0 / 111_195).apply {
            accuracy = 5f; speed = 1.17f; speedAccuracyMetersPerSecond = .2f
        }
        store.writableDatabase.execSQL("CREATE TRIGGER fail_location BEFORE INSERT ON pending_report BEGIN SELECT RAISE(ABORT, 'test disk failure'); END")
        DataCollector.persistLocation(app, walking, true) { walking.time }
        assertEquals(start, PreferenceManager.getDefaultSharedPreferences(app).getLong(baselineKey, -1))
        store.writableDatabase.execSQL("DROP TRIGGER fail_location")
        DataCollector.persistLocation(app, walking, true) { walking.time + 1000 }
        cancelWake()
        assertEquals("a failed write must not erase the exact fix's current movement evidence", 2,
            store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `independent precise slow walking confirms current progress without a speed value`() = runBlocking {
        for (minute in 0..3) {
            val walking = point(start + minute * 60_000, 23.13 + minute * 6.0 / 111_195).apply {
                accuracy = 2f; removeSpeed(); removeSpeedAccuracy()
            }
            DataCollector.persistLocation(app, walking, true) { walking.time }
            cancelWake()
        }
        assertEquals("after three independent observations confirm progress, following minute progress survives", 3,
            store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `an earlier moving fix cannot keep stationary or ambiguous new fixes reporting`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start }
        cancelWake()
        DataCollector.persistLocation(app, point(start + 60_000, 23.131), true) { start + 60_000 }
        cancelWake()
        for (minute in 2..5) {
            val stopped = point(start + minute * 60_000, 23.131).apply {
                accuracy = 5f; speed = 0f; speedAccuracyMetersPerSecond = .2f
            }
            DataCollector.persistLocation(app, stopped, true) { stopped.time }
            cancelWake()
        }
        assertEquals("past motion must not become a permanent stationary heartbeat", 2,
            store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `precise oscillation without speed does not become slow walking evidence`() = runBlocking {
        for ((minute, north) in listOf(0.0, 6.0, 0.0, 6.0, 0.0, 6.0).withIndex()) {
            val wobble = point(start + minute * 60_000, 23.13 + north / 111_195).apply {
                accuracy = 2f; removeSpeed(); removeSpeedAccuracy()
            }
            DataCollector.persistLocation(app, wobble, true) { wobble.time }
            cancelWake()
        }
        assertEquals(1, store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `current speed cannot bypass cadence cache quality or an implausible jump`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start }
        cancelWake()
        val candidates = listOf(
            point(start + 59_999, 23.13001),
            point(start + 60_000, 23.13001).apply { accuracy = 51f },
            point(start + 60_000, 23.13001).apply {
                extras = android.os.Bundle().apply { putString("realProvider", "GNSS_CACHE") }
            },
            point(start + 60_000, 23.23),
        )
        for (candidate in candidates) {
            candidate.speed = 1.17f; candidate.speedAccuracyMetersPerSecond = .2f
            DataCollector.persistLocation(app, candidate, true) { start + 60_000 }
            cancelWake()
        }
        assertEquals(1, store.pendingReports(ServerConfig.baseUrl).size)
        assertEquals(start, PreferenceManager.getDefaultSharedPreferences(app).getLong(baselineKey, -1))
    }

    @Test fun `moving known cache cannot advance a durable location baseline`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start }
        cancelWake()
        val cached = point(start + 60_000, 23.131).apply {
            provider = "network"; accuracy = 5f; speed = 2f; speedAccuracyMetersPerSecond = .2f
            extras = android.os.Bundle().apply { putString("realProvider", "GNSS_CACHE") }
        }
        DataCollector.persistLocation(app, cached, true) { start + 60_000 }
        cancelWake()
        assertEquals(1, store.pendingReports(ServerConfig.baseUrl).size)
        assertEquals(start, PreferenceManager.getDefaultSharedPreferences(app).getLong(baselineKey, -1))
        DataCollector.persistLocation(app, point(start + 60_000, 23.131), true) { start + 60_000 }
        cancelWake()
        assertEquals(2, store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `precise network walking retains minute turns without guessing speed`() = runBlocking {
        val offsets = listOf(0.0 to 0.0, 35.0 to 0.0, 35.0 to 35.0, 0.0 to 35.0, 0.0 to 0.0)
        for ((step, offset) in offsets.withIndex()) {
            val p = point(start + step * 60_000, 23.13 + offset.first / 111_195).apply {
                longitude = 113.3 + offset.second / 102_000
                provider = "network"; accuracy = 3.79f
                speed = 1.17f; speedAccuracyMetersPerSecond = .72583693f
            }
            DataCollector.persistLocation(app, p, true) { p.time + 5_000 }
            cancelWake()
        }
        assertEquals("real walking turns must not be thinned to a straight chord", 5,
            store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `fresh received points survive sleep and retain minute captures in one batch`() = runBlocking {
        for (step in 0..4) {
            val captured = start + step * 60_000
            DataCollector.persistLocation(app, point(captured, 23.13 + step * .001), true,
                receivedAtMs = captured) { start + 300_000 + step }
            cancelWake()
        }
        val pending = store.pendingReports(ServerConfig.baseUrl)
        assertEquals(5, pending.size)
        assertEquals((0..4).map { start + it * 60_000 }, pending.map {
            OffsetDateTime.parse(Json.parseToJsonElement(it.payload).jsonObject.getValue("occurred_at").jsonPrimitive.content)
                .toInstant().toEpochMilli()
        }.sorted())
    }

    @Test fun `fixed selection overhead plus tiny jitter preserves minute captures`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start + 5_002 }
        cancelWake()
        DataCollector.persistLocation(app, point(start + 60_000, 23.131), true) { start + 65_001 }
        cancelWake()
        assertEquals(2, store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `two real service windows preserve movement after restored minute cooldown`() = runBlocking {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(BalancedLocationService.KEY, true).commit()
        val manager = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        shadowOf(manager).setProviderEnabled("gps", true)
        shadowOf(manager).setProviderEnabled("network", true)
        val controller = org.robolectric.Robolectric.buildService(BalancedLocationService::class.java).create()
        val mutex = DataCollector::class.java.getDeclaredField("locationUploadMutex")
            .apply { isAccessible = true }.get(DataCollector) as Mutex
        try {
            controller.get().onStartCommand(android.content.Intent(), 0, 1)
            var now = System.currentTimeMillis()
            shadowOf(manager).getLocationUpdateListeners("gps").single()
                .onLocationChanged(point(now - 5_002))
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            withTimeout(10_000) { while (store.pendingReports(ServerConfig.baseUrl).size < 1) delay(20) }
            mutex.lock(); mutex.unlock()
            cancelWake()
            // Robolectric advances Handler's clock, not java.lang.System wall time.
            // Restore only the durable cooldown times to represent the previous minute;
            // both selections and both SQLite enqueues still execute through the real service.
            now = System.currentTimeMillis()
            prefs.edit().putLong("collector_last_location_time", now - 65_002)
                .putLong(baselineKey, now - 55_500).commit()
            resetJumpFilter() // Restore the runtime filter against that durable previous-minute baseline.
            shadowOf(manager).getLocationUpdateListeners("gps").single()
                .onLocationChanged(point(now - 5_002, 23.131))
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            // Wait for durable state, not an arbitrary sleep or a network request.
            withTimeout(10_000) { while (store.pendingReports(ServerConfig.baseUrl).size < 2) delay(20) }
            mutex.lock(); mutex.unlock()
            cancelWake()
            assertEquals(2, store.pendingReports(ServerConfig.baseUrl).size)
        } finally { controller.destroy(); cancelWake() }
    }

    @Test fun `service bounded window still saves network when no gps arrives`() = runBlocking {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(BalancedLocationService.KEY, true).commit()
        val manager = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        shadowOf(manager).setProviderEnabled("gps", true)
        shadowOf(manager).setProviderEnabled("network", true)
        val controller = org.robolectric.Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(android.content.Intent(), 0, 1)
            val callback = shadowOf(manager).getLocationUpdateListeners("network").single()
            val now = System.currentTimeMillis()
            callback.onLocationChanged(point(now).apply { provider = "network"; accuracy = 30f })
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            val report = withTimeout(10_000) {
                while (store.pendingReports(ServerConfig.baseUrl).isEmpty()) delay(20)
                store.pendingReports(ServerConfig.baseUrl).single()
            }
            val payload = Json.parseToJsonElement(report.payload).jsonObject
            assertEquals("network", payload.getValue("provider").jsonPrimitive.content)
            assertEquals(now / 1000 * 1000,
                OffsetDateTime.parse(payload.getValue("occurred_at").jsonPrimitive.content).toInstant().toEpochMilli())
        } finally { controller.destroy(); cancelWake() }
    }

    @Test fun `service chooses better same time gps before network occupies minute slot`() = runBlocking {
        val prefs = PreferenceManager.getDefaultSharedPreferences(app)
        prefs.edit().putBoolean(BalancedLocationService.KEY, true).commit()
        val manager = app.getSystemService(android.content.Context.LOCATION_SERVICE) as android.location.LocationManager
        shadowOf(manager).setProviderEnabled("gps", true)
        shadowOf(manager).setProviderEnabled("network", true)
        val controller = org.robolectric.Robolectric.buildService(BalancedLocationService::class.java).create()
        try {
            controller.get().onStartCommand(android.content.Intent(), 0, 1)
            val callback = shadowOf(manager).getLocationUpdateListeners("gps").single()
            val now = System.currentTimeMillis()
            callback.onLocationChanged(point(now).apply { provider = "network"; accuracy = 40f })
            // Let the real old asynchronous persistence finish: this is the race under test.
            delay(200)
            callback.onLocationChanged(point(now).apply { accuracy = 5f })
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(5))
            val report = withTimeout(10_000) {
                while (store.pendingReports(ServerConfig.baseUrl).isEmpty()) delay(20)
                store.pendingReports(ServerConfig.baseUrl).single()
            }
            assertEquals("gps", Json.parseToJsonElement(report.payload).jsonObject.getValue("provider").jsonPrimitive.content)
        } finally { controller.destroy(); cancelWake() }
    }

    @Test fun `minute capture cadence survives bounded selection completion jitter`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start + 5_000 }
        cancelWake()
        DataCollector.persistLocation(app, point(start + 60_000, 23.131), true) { start + 64_000 }
        cancelWake()
        assertEquals(2, store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `continuous precise walking retains each minute after independent confirmation`() = runBlocking {
        for (minute in 0..6) {
            val p = point(start + minute * 60_000, 23.13 + minute * 20.0 / 111_195).apply {
                accuracy = 5f; removeSpeed(); removeSpeedAccuracy()
            }
            DataCollector.persistLocation(app, p, true) { p.time }
            cancelWake()
        }
        val pending = store.pendingReports(ServerConfig.baseUrl)
        assertEquals(6, pending.size)
        assertEquals(listOf(0L, 120_000L, 180_000L, 240_000L, 300_000L, 360_000L), pending.map {
            OffsetDateTime.parse(Json.parseToJsonElement(it.payload).jsonObject.getValue("occurred_at").jsonPrimitive.content)
                .toInstant().toEpochMilli() - start
        }.sorted())
    }

    @Test fun `five minute batch retains five distinct moving points and capture times after reopening`() = runBlocking {
        for (minute in 0..4) {
            val capturedAt = start + minute * 60_000
            DataCollector.persistLocation(app, point(capturedAt, 23.13 + minute * .001), true) { capturedAt + 150 }
            cancelWake()
        }
        store.close()
        store = LocalInputStore(app, name)
        setField("eventStore", store)
        // 模拟重新读取持久 prefs，而不是仅检查内存中最后一个点。
        setField("prefs", PreferenceManager.getDefaultSharedPreferences(app))
        val pending = store.pendingReports(ServerConfig.baseUrl)
        assertEquals(5, pending.size)
        assertEquals(5, pending.map { it.id }.distinct().size)
        val capturedTimes = pending.map {
            val payload = Json.parseToJsonElement(it.payload).jsonObject
            OffsetDateTime.parse(payload.getValue("occurred_at").jsonPrimitive.content).toInstant().toEpochMilli()
        }.sorted()
        assertEquals((0..4).map { start + it * 60_000 }, capturedTimes)
        assertEquals(start + 240_150, PreferenceManager.getDefaultSharedPreferences(app).getLong(baselineKey, -1))
        resetJumpFilter()
        DataCollector.persistLocation(app, point(start + 299_999, 23.136), true) { start + 300_149 }
        assertEquals(5, store.pendingReports(ServerConfig.baseUrl).size)
        DataCollector.persistLocation(app, point(start + 300_000, 23.136), true) { start + 300_150 }
        cancelWake()
        assertEquals(6, store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `failed durable enqueue does not advance baseline and same accepted fix can retry`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start }
        cancelWake()
        // 真实 SQLite 写入失败，不模拟 policy 或 enqueue 的返回值。
        store.writableDatabase.execSQL("CREATE TRIGGER fail_location BEFORE INSERT ON pending_report BEGIN SELECT RAISE(ABORT, 'test disk failure'); END")
        val moved = point(start + 60_000, 23.131)
        DataCollector.persistLocation(app, moved, true) { start + 60_000 }
        assertEquals(start, PreferenceManager.getDefaultSharedPreferences(app).getLong(baselineKey, -1))
        assertEquals(1, store.pendingReports(ServerConfig.baseUrl).size)
        store.writableDatabase.execSQL("DROP TRIGGER fail_location")
        DataCollector.persistLocation(app, moved, true) { start + 60_000 }
        cancelWake()
        assertEquals(start + 60_000, PreferenceManager.getDefaultSharedPreferences(app).getLong(baselineKey, -1))
        assertEquals(2, store.pendingReports(ServerConfig.baseUrl).size)
    }

    @Test fun `dense current motion and later stationary fixes do not bypass minute cadence`() = runBlocking {
        DataCollector.persistLocation(app, point(start), true) { start }
        cancelWake()
        DataCollector.persistLocation(app, point(start + 29_999, 23.13001), true) { start + 29_999 }
        DataCollector.persistLocation(app, point(start + 300_000).apply { speed = 0f }, true) { start + 300_000 }
        assertEquals(1, store.pendingReports(ServerConfig.baseUrl).size)
        assertEquals(start, PreferenceManager.getDefaultSharedPreferences(app).getLong(baselineKey, -1))
    }

    @Test fun `callback snapshots mutable location before asynchronous persistence`() = runBlocking {
        val mutex = DataCollector::class.java.getDeclaredField("locationUploadMutex")
            .apply { isAccessible = true }.get(DataCollector) as Mutex
        val capturedAt = System.currentTimeMillis()
        val original = point(capturedAt)
        mutex.lock()
        try {
            DataCollector.reportBalancedLocation(app, original)
            original.latitude = 24.0
            original.time = capturedAt - 1_000
        } finally {
            mutex.unlock()
        }
        val report = withTimeout(10_000) {
            while (store.pendingReports(ServerConfig.baseUrl).isEmpty()) delay(20)
            store.pendingReports(ServerConfig.baseUrl).single()
        }
        // 队列可见时回调仍可能正在保存 prefs；等待其临界区结束再清理单例。
        mutex.lock()
        mutex.unlock()
        cancelWake()
        val payload = Json.parseToJsonElement(report.payload).jsonObject
        assertEquals("23.13", payload.getValue("latitude").jsonPrimitive.content)
        val reportedAt = OffsetDateTime.parse(payload.getValue("occurred_at").jsonPrimitive.content).toInstant().toEpochMilli()
        assertEquals(capturedAt / 1000 * 1000, reportedAt)
    }

    private fun point(timeMs: Long, lat: Double = 23.13) = Location("gps").apply {
        latitude = lat
        longitude = 113.3
        accuracy = 15f
        time = timeMs
        speed = 5f
        speedAccuracyMetersPerSecond = .5f
    }

    private fun resetJumpFilter() {
        val filter = DataCollector::class.java.getDeclaredField("locationJumpFilter")
            .apply { isAccessible = true }.get(DataCollector)
        for (name in listOf("anchor", "lastAccepted", "latest", "recentPrecise")) {
            LocationJumpFilter::class.java.getDeclaredField(name).apply { isAccessible = true }.set(filter, null)
        }
        val pending = LocationJumpFilter::class.java.getDeclaredField("pending").apply { isAccessible = true }.get(filter)
        (pending as MutableMap<*, *>).clear()
    }

    private fun cancelWake() {
        val field = DataCollector::class.java.getDeclaredField("wakeJob").apply { isAccessible = true }
        (field.get(DataCollector) as? Job)?.cancel()
        field.set(DataCollector, null)
    }

    private fun setField(name: String, value: Any?) {
        DataCollector::class.java.getDeclaredField(name).apply { isAccessible = true }.set(DataCollector, value)
    }
}
