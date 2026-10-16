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
