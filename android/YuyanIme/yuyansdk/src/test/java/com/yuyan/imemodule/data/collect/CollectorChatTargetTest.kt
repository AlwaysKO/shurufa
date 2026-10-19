package com.yuyan.imemodule.data.collect

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.os.BatteryManager
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class CollectorChatTargetTest {
    private lateinit var app: Application
    private lateinit var store: LocalInputStore
    private lateinit var databaseName: String
    private val local = "http://127.0.0.1:3000"
    private fun field(name: String) = DataCollector::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear()
            .putBoolean(CollectionConsent.KEY, true).commit()
        ServerConfig.init(app)
        databaseName = "chat-target-${UUID.randomUUID()}.db"
        store = LocalInputStore(app, databaseName)
        field("eventStore").set(null, store)
        field("delivery").set(null, null)
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_USB))
        val manager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED, ConnectivityManager.TYPE_WIFI, 0, true, true))
        val capabilities = ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(capabilities).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork, capabilities)
        assertTrue(isUsbDataLink(app))
        assertTrue(ImageUploadRuntime.hasValidatedWifi(app))
    }

    @After fun cleanup() {
        field("eventStore").set(null, null)
        store.close()
        app.deleteDatabase(databaseName)
        app.sendStickyBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED)
            .putExtra(BatteryManager.EXTRA_PLUGGED, 0))
    }

    private fun enqueue(id: String, kind: String, target: String = local) {
        store.enqueueReport(PendingReport(id, kind, "{}"), listOf(target))
    }

    @Suppress("UNCHECKED_CAST")
    private fun eligible(): Pair<Set<String>, Boolean> = DataCollector::class.java
        .getDeclaredMethod("eligiblePending", Context::class.java).apply { isAccessible = true }
        .invoke(DataCollector, app) as Pair<Set<String>, Boolean>

    @Test fun localChatDoesNotBecomeDeliverableWhileUsbAndWifiAreConnected() {
        enqueue("old-image", "chat_asset")
        enqueue("old-message", "chat_messages")
        assertEquals(emptySet<String>(), eligible().first)
        assertEquals(setOf("chat_asset", "chat_messages"), store.pendingKinds(listOf(local)))
    }

    @Test fun ordinaryLocalReportsRemainEligibleAlongsideIgnoredLocalChat() {
        enqueue("old-message", "chat_messages")
        enqueue("local-choice", "personal_choice")
        assertEquals(setOf("personal_choice"), eligible().first)
        assertEquals(setOf("chat_messages", "personal_choice"), store.pendingKinds(listOf(local)))
    }

    @Test fun localChatCannotConsumeFirstBatchOfLaterOnlineChat() {
        var now = 0L
        val plan = CollectorUploadPlan { now }
        enqueue("old-message", "chat_messages")
        val before = eligible()
        plan.select(true, true, before.first, before.second, true)
        now = 1_000L
        enqueue("new-online-message", "chat_messages", ServerConfig.baseUrl)
        val after = eligible()
        assertTrue("A local-only chat must not spend the online chat batch",
            plan.select(true, true, after.first, after.second, true).chatText)
    }
}
