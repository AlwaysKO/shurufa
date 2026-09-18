package com.yuyan.imemodule.expression.send

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExpressionDeliveryPolicyTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("delivery-policy-test", 0)
    private fun fixture(name: String = "default") = javaClass.classLoader!!.getResourceAsStream("expression/delivery-$name.json")!!.bufferedReader().use { it.readText() }
    private fun updated(revision: Long = 7) = JSONObject(fixture("updated")).put("revision", revision).toString()
    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun backendDefaultExactlyMatchesAndroidBuiltInRules() {
        assertEquals(ExpressionDeliveryPolicy.defaults(), ExpressionDeliveryPolicy.parse(fixture()))
        val policy = ExpressionDeliveryPolicy.parse(fixture("updated"))!!
        assertEquals(7L, policy.revision)
        assertEquals("8.0.79", policy.rules.first().versionName)
        assertFalse(policy.rules[1].enabled)
    }
    @Test fun matchingSeparatesAppsVersionsMimeAndAndroidCapabilities() {
        val policy = ExpressionDeliveryPolicy.defaults()
        assertNotNull(policy.match("com.tencent.mm", "image/gif", "8.0.78", 3180, 26))
        assertNull(policy.match("com.tencent.mm", "image/gif", "8.0.79", 3181, 26))
        assertNull(policy.match("com.tencent.mm", "image/gif", "8.0.78", 3180, 25))
        assertEquals("commit_content", policy.match("com.tencent.mobileqq", "image/gif", null, null, 25)!!.method)
        assertNull(policy.match("unknown.app", "image/gif", "1", 1, 35))
        val rule = policy.rules[0].copy(versionName = null, minVersionCode = 20, maxVersionCode = 30)
        assertFalse(rule.matches("com.tencent.mm", "image/gif", "anything", null, 35))
        assertFalse(rule.matches("com.tencent.mm", "image/gif", "anything", 19, 35))
        assertTrue(rule.matches("com.tencent.mm", "image/gif", "anything", 20, 35))
        assertTrue(rule.matches("com.tencent.mm", "image/gif", "anything", 30, 35))
        assertFalse(rule.matches("com.tencent.mm", "image/gif", "anything", 31, 35))
    }
    @Test fun standardDefaultsPreserveLegacyMimeNegotiationOnSupportedApi23And24() {
        val policy = ExpressionDeliveryPolicy.defaults()
        for (sdk in listOf(23, 24)) {
            assertNotNull(policy.match("com.tencent.mobileqq", "image/gif", null, null, sdk))
            assertNotNull(policy.match("com.ss.android.ugc.aweme", "image/png", null, null, sdk))
            assertNotNull(policy.match("com.tencent.mm", "image/webp", null, null, sdk))
        }
        assertNull(policy.match("com.tencent.mobileqq", "image/gif", null, null, 22))
        assertNull(policy.match("com.tencent.mm", "image/gif", "8.0.78", 1, 25))
    }

    @Test fun firstDisabledMatchingRuleIsNotSkipped() {
        val policy = ExpressionDeliveryPolicy.defaults()
        val denied = policy.rules.first().copy(enabled = false)
        assertFalse(policy.copy(rules = listOf(denied) + policy.rules).match("com.tencent.mm", "image/gif", "8.0.78", 1, 35)!!.enabled)
    }
    @Test fun knownLossyWechatStandardGifRouteAndUnversionedOverrideAreRejected() {
        for (version in listOf<String?>(null, "8.0.78")) {
            val document = JSONObject(fixture())
            document.getJSONArray("rules").getJSONObject(0).apply {
                put("method", "commit_content"); put("action", JSONObject.NULL); put("uriKey", JSONObject.NULL)
                put("versionName", version ?: JSONObject.NULL)
            }
            assertNull(ExpressionDeliveryPolicy.parse(document.toString()))
        }
        val document = JSONObject(fixture())
        document.getJSONArray("rules").getJSONObject(0).apply {
            put("method", "commit_content"); put("action", JSONObject.NULL); put("uriKey", JSONObject.NULL)
            put("versionName", "8.0.79")
        }
        assertNotNull(ExpressionDeliveryPolicy.parse(document.toString()))
    }

    @Test fun malformedOrUnsupportedConfigurationIsRejectedAsAWhole() {
        val mutations: List<(JSONObject) -> Unit> = listOf(
            { it.put("schemaVersion", 2) }, { it.put("revision", -1) }, { it.put("revision", "3") },
            { it.put("revision", 1.2) }, { it.put("revision", 9007199254740992L) },
            { it.put("script", "execute") },
            { it.getJSONArray("rules").getJSONObject(0).put("packageName", "arbitrary.app") },
            { it.getJSONArray("rules").getJSONObject(0).put("method", "share_intent") },
            { it.getJSONArray("rules").getJSONObject(0).put("enabled", "true") },
            { it.getJSONArray("rules").getJSONObject(0).put("uriKey", "content://unsafe") },
            { it.getJSONArray("rules").getJSONObject(0).put("action", "action;script") },
            { it.getJSONArray("rules").getJSONObject(0).put("minSdk", 25) },
            { it.getJSONArray("rules").getJSONObject(0).put("maxVersionCode", -1) },
            { it.getJSONArray("rules").getJSONObject(0).put("requiredEditorExtras", JSONObject().put("x", 2147483648L)) },
            { it.getJSONArray("rules").getJSONObject(1).put("id", "wechat-gif") },
        )
        mutations.forEachIndexed { index, mutate ->
            val json = JSONObject(fixture()); mutate(json)
            assertNull("mutation $index", ExpressionDeliveryPolicy.parse(json.toString()))
        }
        assertNull(ExpressionDeliveryPolicy.parse(" ".repeat(65_537)))
    }
    @Test fun validSnapshotPersistsAndInvalidLateOrOtherAuthorityCannotReplaceIt() {
        val store = ExpressionDeliveryStore(prefs, "https://one.example")
        assertEquals(0L, store.current().revision)
        assertTrue(store.accept(updated()))
        assertEquals(7L, ExpressionDeliveryStore(prefs, "https://one.example").current().revision)
        assertFalse(store.accept("not-json"))
        assertFalse(store.accept(updated(6)))
        assertFalse(store.accept(JSONObject(updated()).put("rules", org.json.JSONArray()).toString()))
        assertEquals(7L, store.current().revision)
        assertEquals(0L, ExpressionDeliveryStore(prefs, "https://two.example").current().revision)
        assertTrue(store.accept(updated(8))) // 后台回滚发新revision，而不是接纳迟到旧revision。
    }
    @Test fun corruptDiskUsesSafeDefaultsInsteadOfGuessingNewVersions() {
        prefs.edit().putString("config:https://one.example", "broken").commit()
        assertEquals(ExpressionDeliveryPolicy.defaults(), ExpressionDeliveryStore(prefs, "https://one.example").current())
    }
    @Test fun mobileFetchSendsDeviceHeaderAndKeepsLastGoodOnFailureOversizeOrRedirect() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val store = ExpressionDeliveryStore(prefs, server.url("/").toString().trimEnd('/'))
            val client = OkHttpClient.Builder().followRedirects(false).build()
            server.enqueue(MockResponse().setBody(updated()))
            assertTrue(store.fetch(client, "device-test"))
            val request = server.takeRequest()
            assertEquals("/api/v1/mobile/expression-delivery", request.path)
            assertEquals("device-test", request.getHeader("X-Device-Id"))
            for (response in listOf(MockResponse().setResponseCode(500), MockResponse().setBody("broken"),
                MockResponse().setChunkedBody("x".repeat(65_537), 4096),
                MockResponse().setResponseCode(302).setHeader("Location", "https://other.example/config"))) {
                server.enqueue(response)
                assertFalse(store.fetch(client, "device-test"))
                assertEquals(7L, store.current().revision)
                server.takeRequest()
            }
        } finally { server.shutdown() }
    }
}
