package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.usage.UsageEvidenceCapabilities
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LocationAltitudeDeliveryTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private val payload = """{"latitude":31,"longitude":121,"context":{"version":1,"altitude_m":0,"raw_altitude_m":0,"vertical_accuracy_m":5,"altitude_type":"wgs84_ellipsoid","altitude_quality_reason":"accurate","speed_quality":"trusted"}}"""
    @Test fun `unknown target strips only new altitude fields without mutating durable payload`() {
        MockWebServer().use { server ->
            server.start(); val target=server.url("/").toString().trimEnd('/')
            val name="altitude-${UUID.randomUUID()}.db"; val store=LocalInputStore(app,name)
            try {
                store.enqueueReport(PendingReport("one","location",payload),listOf(target,"other"))
                server.enqueue(MockResponse().setBody("""{"ok":true}"""))
                server.enqueue(MockResponse().setBody("""{"ok":true,"id":"one"}"""))
                assertTrue(EventDelivery(store,OkHttpClient(),"d","{}").flush(target))
                server.takeRequest(); val received=JSONObject(server.takeRequest().body.readUtf8()).getJSONObject("payload").getJSONObject("context")
                assertEquals(setOf("version","altitude_m","speed_quality"),received.keys().asSequence().toSet())
                assertEquals(payload,store.pendingReports("other").single().payload)
            } finally { store.close(); app.deleteDatabase(name) }
        }
    }
    @Test fun `config records location capability independently from usage and advertised collector target`() {
        val target="https://${UUID.randomUUID()}.example"
        UsageEvidenceCapabilities(app).record(target,"""{"collector_base_url":"https://other.example","capabilities":{"location_altitude_evidence":1}}""")
        val stored=JSONObject(app.getSharedPreferences("usage_server_capabilities_v1",0).getString(target,null)!!)
        assertTrue(stored.optBoolean("location_altitude_evidence",false))
        assertFalse(stored.getBoolean("supported"))
    }
    @Test fun `receivers independently discover capabilities even when usage tracking is unused`() {
        MockWebServer().use { old -> MockWebServer().use { modern ->
            old.start(); modern.start()
            val a=old.url("/").toString().trimEnd('/'); val b=modern.url("/").toString().trimEnd('/')
            val cache=UsageEvidenceCapabilities(app)
            val name="altitude-${UUID.randomUUID()}.db"; val store=LocalInputStore(app,name)
            val http=OkHttpClient()
            val gate=com.yuyan.imemodule.data.usage.UsageUploadGate { true }
            try {
                fun dispatcher(supported:Boolean)=object:okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                        if(request.path=="/api/v1/mobile/config") return MockResponse().setBody(if(supported)
                            """{"capabilities":{"location_altitude_evidence":1},"collector_base_url":"$a"}"""
                            else """{"collector_base_url":"https://advertised.example"}""")
                        if(request.path=="/api/v1/mobile/reports") {
                            val body=JSONObject(request.body.readUtf8()); val context=body.getJSONObject("payload").getJSONObject("context")
                            if(!supported && context.keys().asSequence().any { it !in setOf("version","altitude_m","speed_quality") })
                                return MockResponse().setResponseCode(400)
                            if(supported && !context.has("raw_altitude_m")) return MockResponse().setResponseCode(400)
                            return MockResponse().setBody(JSONObject().put("ok",true).put("id",body.getString("id")).toString())
                        }
                        return MockResponse().setBody("""{"ok":true}""")
                    }
                }
                old.dispatcher=dispatcher(false); modern.dispatcher=dispatcher(true)
                // Fixture really rejects new fields, not a permissive success stub.
                val raw=okhttp3.Request.Builder().url("$a/api/v1/mobile/reports")
                    .post(okhttp3.RequestBody.create(null, """{"id":"probe","payload":$payload}""")).build()
                assertEquals(400,http.newCall(raw).execute().use { it.code })
                val delivery=EventDelivery(store,http,"d","{}",locationAltitudeSupported={ target ->
                    cache.ensureAltitudeSupported(http,target,"d",gate)
                })
                repeat(3) { store.enqueueReport(PendingReport("point-$it","location",payload),listOf(a,b)) }
                assertTrue(delivery.drain(a,nowMillis={0L})); assertTrue(delivery.drain(b,nowMillis={0L}))
                assertEquals(false,cache.altitudeSupported(a)); assertEquals(true,cache.altitudeSupported(b))
                assertNull(cache.altitudeSupported("https://advertised.example"))
                assertEquals(6,old.requestCount); assertEquals(5,modern.requestCount)
                assertTrue(store.pendingReports(a).isEmpty()); assertTrue(store.pendingReports(b).isEmpty())
            } finally { store.close();app.deleteDatabase(name) }
        } }
    }
    @Test fun `failed discovery is attempted once per drain and location revoked during GET is not sent`() {
        for(revoke in listOf(false,true)) MockWebServer().use { server ->
            server.start(); val target=server.url("/").toString().trimEnd('/')
            val name="altitude-${UUID.randomUUID()}.db"; val store=LocalInputStore(app,name)
            var allowed=true; var discovered=0
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    if(request.path=="/api/v1/mobile/config") { discovered++; if(revoke) allowed=false; return MockResponse().setResponseCode(503) }
                    if(request.path=="/api/v1/mobile/reports") return MockResponse().setBody(JSONObject()
                        .put("ok",true).put("id",JSONObject(request.body.readUtf8()).getString("id")).toString())
                    return MockResponse().setBody("""{"ok":true}""")
                }
            }
            val cache=UsageEvidenceCapabilities(app); val http=OkHttpClient()
            try {
                repeat(35) { store.enqueueReport(PendingReport("point-$it","location",payload),listOf(target)) }
                val delivery=EventDelivery(store,http,"d","{}",allowed={allowed}, locationAltitudeSupported={
                    cache.ensureAltitudeSupported(http,it,"d",com.yuyan.imemodule.data.usage.UsageUploadGate { allowed })
                })
                assertEquals(!revoke,delivery.drain(target,nowMillis={0L}))
                assertEquals(1,discovered); assertNull(cache.altitudeSupported(target))
                if(revoke) { assertEquals(2,server.requestCount); assertEquals(35,store.readableDatabase.rawQuery("SELECT COUNT(*) FROM report_target WHERE target=?",arrayOf(target)).use { it.moveToFirst();it.getInt(0) }) }
                else assertTrue(store.pendingReports(target).isEmpty())
            } finally {store.close();app.deleteDatabase(name)}
        }
    }
    @Test fun `slow discovery consumes drain budget and changed target eligibility blocks POST`() {
        for(expire in listOf(false,true)) MockWebServer().use { server ->
            server.start(); val target=server.url("/").toString().trimEnd('/')
            val name="altitude-${UUID.randomUUID()}.db"; val store=LocalInputStore(app,name)
            var allowed=true; var now=0L
            try {
                store.enqueueReport(PendingReport("point","location",payload),listOf(target))
                server.enqueue(MockResponse().setBody("""{"ok":true}"""))
                val delivery=EventDelivery(store,OkHttpClient(),"d","{}", locationAltitudeSupported={
                    if(expire) now=5001L else allowed=false
                    true
                },locationTargetAllowed={allowed})
                assertEquals(expire,delivery.drain(target,nowMillis={now}))
                assertEquals(1,server.requestCount); assertEquals(1,store.pendingReports(target).size)
                assertNull(store.locationAltitudeWireVariant(target,"point"))
            } finally {store.close();app.deleteDatabase(name)}
        }
    }
    @Test fun `malformed altitude capability stays unknown and cache expiry is target scoped`() {
        val cache=UsageEvidenceCapabilities(app)
        for(value in listOf("null","true","\"1\"","-1","1.5","[]")) {
            val target="https://${UUID.randomUUID()}.example"
            cache.record(target,"""{"capabilities":{"usage_evidence":1,"location_altitude_evidence":$value}}""",1000)
            assertNull(cache.altitudeSupported(target,1001)); assertNull(cache.supported(target,1001))
        }
        val target="https://${UUID.randomUUID()}.example"
        cache.record(target,"""{"capabilities":{"location_altitude_evidence":1}}""",1000)
        assertEquals(true,cache.altitudeSupported(target,1001))
        assertNull(cache.altitudeSupported(target,7_201_000)); assertNull(cache.altitudeSupported(target,999))
    }

    @Test fun `ACK loss and capability change across restart preserve target specific payload hash`() {
        for(initialSupport in listOf(false,true)) MockWebServer().use { server ->
            server.start(); val target=server.url("/").toString().trimEnd('/')
            val name="altitude-${UUID.randomUUID()}.db"; var store=LocalInputStore(app,name)
            var firstPayload:String?=null; var reports=0; var discoveries=0
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                    if(request.path=="/api/v1/mobile/reports") {
                        reports++; val body=request.body.readUtf8()
                        if(firstPayload==null) { firstPayload=body; return MockResponse().setResponseCode(503) }
                        return if(body==firstPayload) MockResponse().setBody("""{"ok":true,"id":"point"}""")
                            else MockResponse().setResponseCode(409)
                    }
                    return MockResponse().setBody("""{"ok":true}""")
                }
            }
            try {
                store.enqueueReport(PendingReport("point","location",payload),listOf(target,"other"))
                assertFalse(EventDelivery(store,OkHttpClient(),"d","{}",locationAltitudeSupported={discoveries++; initialSupport}).flush(target))
                store.close(); store=LocalInputStore(app,name)
                assertTrue(EventDelivery(store,OkHttpClient(),"d","{}",locationAltitudeSupported={discoveries++; !initialSupport}).flush(target))
                assertEquals(2,reports); assertEquals(1,discoveries)
                assertEquals(payload,store.pendingReports("other").single().payload)
                assertTrue(store.pendingReports(target).isEmpty())
            } finally {store.close();app.deleteDatabase(name)}
        }
    }

    @Test fun `failed wire persistence must not send or acknowledge location`() {
        MockWebServer().use { server ->
            server.start(); val target=server.url("/").toString().trimEnd('/')
            val name="altitude-${UUID.randomUUID()}.db"; val store=LocalInputStore(app,name)
            try {
                store.enqueueReport(PendingReport("point","location",payload),listOf(target))
                store.writableDatabase.execSQL("CREATE TRIGGER fail_pin BEFORE UPDATE OF altitude_evidence ON report_target BEGIN SELECT RAISE(ABORT,'simulated disk failure'); END")
                server.enqueue(MockResponse().setBody("""{"ok":true}"""))
                assertFalse(EventDelivery(store,OkHttpClient(),"d","{}",locationAltitudeSupported={true}).flush(target))
                assertEquals(1,server.requestCount); assertEquals(1,store.pendingReports(target).size)
                assertNull(store.locationAltitudeWireVariant(target,"point"))
            } finally {store.close();app.deleteDatabase(name)}
        }
    }

    @Test fun `many local locations reuse outer health check and production rechecks are pure eligibility`() {
        val source=java.io.File("src/main/java/com/yuyan/imemodule/data/collect/DataCollector.kt").readText()
        val wiring=source.substringAfter("val locationTargetAllowed:").substringBefore("delivery = EventDelivery(")
        assertTrue(wiring.contains("ServerConfig.baseUrl")); assertTrue(wiring.contains(".canAttempt(target)"))
        assertFalse(wiring.contains(".canUpload(")); assertFalse(wiring.contains("collectorTargetGate("))
        assertTrue(source.contains("locationTargetAllowed = locationTargetAllowed"))
        MockWebServer().use { server ->
            server.start(); val target=server.url("/").newBuilder().host("127.0.0.1").build().toString().trimEnd('/')
            val name="altitude-${UUID.randomUUID()}.db"; val store=LocalInputStore(app,name)
            val http=OkHttpClient(); var health=0;var configs=0;var reports=0
            server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse = when(request.path) {
                    "/health" -> {health++;MockResponse().setBody("""{"status":"ok"}""")}
                    "/api/v1/mobile/config" -> {configs++;MockResponse().setBody("""{"capabilities":{"location_altitude_evidence":1}}""")}
                    "/api/v1/mobile/reports" -> { reports++;MockResponse().setBody(JSONObject().put("ok",true)
                        .put("id",JSONObject(request.body.readUtf8()).getString("id")).toString()) }
                    else -> MockResponse().setBody("""{"ok":true}""")
                }
            }
            val gate=CollectorTargetGate("https://online.example",{true},{localCollectorHealthy(http,it)},{false})
            val cache=UsageEvidenceCapabilities(app)
            try {
                repeat(35) {store.enqueueReport(PendingReport("point-$it","location",payload),listOf(target))}
                assertTrue(gate.canUpload(target))
                val delivery=EventDelivery(store,http,"d","{}",locationTargetAllowed={gate.canAttempt(it)},locationAltitudeSupported={
                    cache.ensureAltitudeSupported(http,it,"d",com.yuyan.imemodule.data.usage.UsageUploadGate {gate.canAttempt(target)})
                })
                assertTrue(delivery.drain(target,nowMillis={0L})); assertEquals(35,reports);assertEquals(1,health);assertEquals(1,configs)
            } finally {store.close();app.deleteDatabase(name)}
        }
    }

    @Test fun `real fine or coarse permission revoked during config blocks location and production gate rechecks it`() {
        val application=ApplicationProvider.getApplicationContext<android.app.Application>()
        val shadow=org.robolectric.Shadows.shadowOf(application)
        for(permission in listOf(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION)) {
            shadow.denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION)
            shadow.grantPermissions(permission)
            assertTrue(LocationPermissions.hasForegroundPermission(application))
            MockWebServer().use { server ->
                server.start(); val target=server.url("/").toString().trimEnd('/')
                val name="altitude-permission-${UUID.randomUUID()}.db";val store=LocalInputStore(app,name)
                val http=OkHttpClient();val cache=UsageEvidenceCapabilities(app)
                server.dispatcher=object:okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request:okhttp3.mockwebserver.RecordedRequest):MockResponse {
                        if(request.path=="/api/v1/mobile/config") {
                            shadow.denyPermissions(android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION)
                            return MockResponse().setBody("""{"capabilities":{"location_altitude_evidence":1}}""")
                        }
                        return MockResponse().setBody("""{"ok":true}""")
                    }
                }
                try {
                    store.enqueueReport(PendingReport("point","location",payload),listOf(target))
                    val allowed={LocationPermissions.hasForegroundPermission(application)}
                    val delivery=EventDelivery(store,http,"d","{}",locationTargetAllowed={allowed()},locationAltitudeSupported={
                        cache.ensureAltitudeSupported(http,it,"d",com.yuyan.imemodule.data.usage.UsageUploadGate(allowed))
                    })
                    assertFalse(delivery.drain(target,nowMillis={0L}))
                    assertFalse(allowed());assertEquals(2,server.requestCount)
                    assertEquals(1,store.pendingReports(target).size);assertNull(store.locationAltitudeWireVariant(target,"point"))
                } finally {store.close();app.deleteDatabase(name)}
            }
        }
        val source=java.io.File("src/main/java/com/yuyan/imemodule/data/collect/DataCollector.kt").readText()
        val wiring=source.substringAfter("val locationTargetAllowed:").substringBefore("delivery = EventDelivery(")
        assertTrue("The real production upload callback must recheck the same system permission helper",wiring.contains("hasLocationPermission(context)"))
        assertTrue(source.substringAfter("private fun hasLocationPermission(context: Context)").substringBefore("private fun networkType")
            .contains("LocationPermissions.hasForegroundPermission(context)"))
    }

    @Test fun `revoked location permission leaves location queued while authorized ordinary reports continue`() {
        val application=ApplicationProvider.getApplicationContext<android.app.Application>()
        org.robolectric.Shadows.shadowOf(application).denyPermissions(
            android.Manifest.permission.ACCESS_FINE_LOCATION,android.Manifest.permission.ACCESS_COARSE_LOCATION)
        MockWebServer().use { server ->
            server.start(); val target=server.url("/").toString().trimEnd('/')
            val name="altitude-mixed-${UUID.randomUUID()}.db";val store=LocalInputStore(app,name)
            try {
                store.enqueueReport(PendingReport("location","location",payload),listOf(target))
                store.enqueueReport(PendingReport("ordinary","phrase_use","{}"),listOf(target))
                server.enqueue(MockResponse().setBody("""{"ok":true}"""))
                server.enqueue(MockResponse().setBody("""{"ok":true,"id":"ordinary"}"""))
                val delivery=EventDelivery(store,OkHttpClient(),"d","{}",allowed={kind ->
                    kind!="location" || LocationPermissions.hasForegroundPermission(application)
                },locationAltitudeSupported={error("revoked location must not discover capabilities")})
                assertTrue(delivery.drain(target,nowMillis={0L}))
                assertEquals(2,server.requestCount);server.takeRequest()
                assertEquals("phrase_use",JSONObject(server.takeRequest().body.readUtf8()).getString("kind"))
                assertEquals(listOf("location"),store.pendingReports(target).map {it.id})
            } finally {store.close();app.deleteDatabase(name)}
        }
        val source=java.io.File("src/main/java/com/yuyan/imemodule/data/collect/DataCollector.kt").readText()
        val wiring=source.substringAfter("allowed = { kind ->").substringBefore("beginImageRead =")
        assertTrue("Only the location branch must require system permission before reading its queue",
            wiring.contains("kind != \"location\" || (locationTrackingEnabled && hasLocationPermission(context))"))
    }

}
