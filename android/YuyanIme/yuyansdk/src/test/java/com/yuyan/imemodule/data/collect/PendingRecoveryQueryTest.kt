package com.yuyan.imemodule.data.collect

import android.app.Application
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PendingRecoveryQueryTest {
    private lateinit var app: Application
    private lateinit var store: LocalInputStore
    private lateinit var name: String
    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().putBoolean(CollectionConsent.KEY, true).commit()
        ServerConfig.init(app)
        name = "pending-recovery-${UUID.randomUUID()}.db"
        store = LocalInputStore(app, name)
        set("eventStore", store)
    }
    @After fun cleanup() { set("eventStore", null); store.close(); app.deleteDatabase(name) }
    private fun set(name: String, value: Any?) = DataCollector::class.java.getDeclaredField(name).apply { isAccessible = true }.set(DataCollector, value)
    private fun enqueue(kind: String) = store.enqueueReport(PendingReport(UUID.randomUUID().toString(), kind, "{}"), listOf(ServerConfig.baseUrl))

    private fun network(wifi: Boolean) {
        val manager = app.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val shadow = org.robolectric.Shadows.shadowOf(manager)
        shadow.setActiveNetworkInfo(org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,
            if (wifi) android.net.ConnectivityManager.TYPE_WIFI else android.net.ConnectivityManager.TYPE_MOBILE, 0, true, true))
        val caps = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        org.robolectric.Shadows.shadowOf(caps).apply {
            addTransportType(if (wifi) android.net.NetworkCapabilities.TRANSPORT_WIFI else android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
            addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        shadow.setNetworkCapabilities(manager.activeNetwork, caps)
    }
    @Test fun readyScreenshotConfirmationUsesImageRecoveryAndLeavesTextCadenceAlone() {
        network(true)
        store.enqueueReport(PendingReport("confirmation", "chat_messages",
            """{"messages":[{"message_type":"image","asset_sha256":["${"a".repeat(64)}"]}]}"""), listOf(ServerConfig.baseUrl))
        val pending = requireNotNull(DataCollector.pendingRecovery(app))
        assertEquals(6_000L, pending.delayMillis)
        assertFalse("Acknowledged image metadata may still use the ordinary mobile batch", pending.wifiOnly)
    }

    @Test fun screenshotMetadataRespectsTypingPauseAndMobileBatching() {
        store.enqueueReport(PendingReport("confirmation", "chat_messages",
            """{"messages":[{"message_type":"image","asset_sha256":["${"b".repeat(64)}"]}]}"""), listOf(ServerConfig.baseUrl))
        network(true)
        resetImageInputForTest()
        try {
            ImageUploadRuntime.noteKeyActivity()
            assertEquals(300_000L, requireNotNull(DataCollector.pendingRecovery(app)).delayMillis)
            assertFalse(store.hasPendingImages(ServerConfig.baseUrl, includeMessages = false))
        } finally { resetImageInputForTest() }
        network(false)
        val mobile = requireNotNull(DataCollector.pendingRecovery(app))
        assertEquals(300_000L, mobile.delayMillis)
        assertFalse(mobile.wifiOnly)
    }

    @Test fun wifiImagesAreNotDelayedByOrdinaryPendingReports() {
        network(true)
        enqueue("chat_asset"); enqueue("location")
        val pending = requireNotNull(DataCollector.pendingRecovery(app))
        assertEquals(6_000L, pending.delayMillis)
        assertFalse(pending.wifiOnly)
        val plan = CollectorUploadPlan { 0L }
        assertTrue(plan.select(true, true, setOf("location"), false, false).location)
        assertFalse("Image recovery must not override ordinary cadence",
            plan.select(true, true, setOf("location", "chat_asset"), false, true).location)
    }
    @Test fun imageRecoveryHonorsFailedTargetRetryInsteadOfPollingEverySixSeconds() {
        network(true)
        enqueue("chat_asset")
        val gate = DataCollector::class.java.getDeclaredField("retryGate").apply { isAccessible = true }
            .get(null) as ReportRetryGate
        val now = android.os.SystemClock.elapsedRealtime()
        gate.record(ServerConfig.baseUrl, now + 120_000L, failed = true)
        try {
            assertEquals(120_000L, requireNotNull(DataCollector.pendingRecovery(app)).delayMillis)
        } finally { gate.record(ServerConfig.baseUrl, 0L, failed = false) }
    }
    @Test fun mixedQueueOnMobileDoesNotCreateSixSecondImageWakeups() {
        network(false)
        enqueue("chat_asset"); enqueue("location")
        val pending = requireNotNull(DataCollector.pendingRecovery(app))
        assertEquals(300_000L, pending.delayMillis)
        assertFalse(pending.wifiOnly)
    }
    @Test fun emptyPersistentQueueHasNoRecovery() { assertNull(DataCollector.pendingRecovery(app)) }
    @Test fun offlineLocationIsPreservedForAnAnyNetworkBatch() {
        enqueue("location")
        val pending = requireNotNull(DataCollector.pendingRecovery(app))
        assertFalse(pending.wifiOnly); assertEquals(300_000L, pending.delayMillis)
    }
    @Test fun onlyImagesWaitForWifiAndMixedTextUsesAnyNetwork() {
        enqueue("chat_asset")
        assertTrue(requireNotNull(DataCollector.pendingRecovery(app)).wifiOnly)
        enqueue("chat_messages")
        assertFalse(requireNotNull(DataCollector.pendingRecovery(app)).wifiOnly)
    }
    @Test fun disabledLocationAndWithdrawnConsentDoNotSchedulePending() {
        enqueue("location")
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean("location_tracking_enable", false).commit()
        assertNull(DataCollector.pendingRecovery(app))
        enqueue("chat_messages")
        CollectionConsent.setEnabled(app, false)
        assertNull(DataCollector.pendingRecovery(app))
    }
}
