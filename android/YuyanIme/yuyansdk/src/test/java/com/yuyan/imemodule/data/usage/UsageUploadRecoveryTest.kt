package com.yuyan.imemodule.data.usage

import android.app.Application
import android.app.AppOpsManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import androidx.preference.PreferenceManager
import androidx.test.core.app.ApplicationProvider
import com.yuyan.imemodule.data.collect.CollectionConsent
import com.yuyan.imemodule.data.collect.GameWorkRuntime
import com.yuyan.imemodule.data.collect.ImageUploadRuntime
import com.yuyan.imemodule.data.collect.ServerConfig
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowNetworkCapabilities
import org.robolectric.shadows.ShadowNetworkInfo
import org.robolectric.shadows.ShadowSystemClock
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UsageUploadRecoveryTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val manager=app.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val ops=app.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
    @Before fun setup()=runBlocking {
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,true)
            .putBoolean(AppUsageTracker.KEY,true).commit()
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_ALLOWED)
        GameWorkRuntime.setGaming(true);GameWorkRuntime.setGaming(false)
        ImageUploadRuntime.finishInput()
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        network(true)
        // An empty successful sync still consumes the automatic cadence; no HTTP is issued.
        AppUsageTracker.sync(app)
    }
    private fun network(wifi:Boolean) {
        shadowOf(manager).setActiveNetworkInfo(ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,
            if(wifi)ConnectivityManager.TYPE_WIFI else ConnectivityManager.TYPE_MOBILE,0,true,true))
        shadowOf(manager).setDefaultNetworkActive(true)
        val caps=ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(if(wifi)NetworkCapabilities.TRANSPORT_WIFI else NetworkCapabilities.TRANSPORT_CELLULAR)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        shadowOf(caps).addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        shadowOf(manager).setNetworkCapabilities(manager.activeNetwork,caps)
    }
    private fun pending(target:String=ServerConfig.baseUrl,duration:Long=10_000L) {
        UsageStore(app).use { store->
            val now=System.currentTimeMillis()
            store.save(UsageReduction(store.state()?:UsageState(cursor=now),listOf(
                UsageRecord("pending","usage","com.example.reader",null,now-duration,now,"lock")
            )),listOf(target))
        }
    }
    @Test fun `screen off pending usage schedules remaining wifi cadence instead of thirty minute fallback`()=runBlocking {
        pending();ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        assertEquals(240_000L,AppUsageTracker.nextUploadDelay(app))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(240))
        assertEquals(30_000L,AppUsageTracker.nextUploadDelay(app))
        UsageStore(app).use { assertEquals(1,it.pending(ServerConfig.baseUrl).size) }
    }
    @Test fun `mobile recovery retains fifteen minute cadence`()=runBlocking {
        pending();network(false);ShadowSystemClock.advanceBy(Duration.ofSeconds(60))
        assertEquals(840_000L,AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `empty acknowledged and suppressed queues do not poll`()=runBlocking {
        assertNull(AppUsageTracker.nextUploadDelay(app))
        pending(duration=1000L)
        assertNull(AppUsageTracker.nextUploadDelay(app))
        UsageStore(app).use { it.acknowledge(ServerConfig.baseUrl,listOf("pending")) }
        assertNull(AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `offline permission loss and opt out cannot schedule usage recovery`()=runBlocking {
        pending();shadowOf(manager).setActiveNetworkInfo(null)
        assertNull(AppUsageTracker.nextUploadDelay(app))
        network(true)
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_IGNORED)
        assertNull(AppUsageTracker.nextUploadDelay(app))
        shadowOf(ops).setMode(AppOpsManager.OPSTR_GET_USAGE_STATS,android.os.Process.myUid(),app.packageName,AppOpsManager.MODE_ALLOWED)
        PreferenceManager.getDefaultSharedPreferences(app).edit().putBoolean(CollectionConsent.KEY,false).commit()
        assertNull(AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `input and game pause do not inspect storage or cause short polling`()=runBlocking {
        UsageStore(app).use { it.writableDatabase.execSQL("DROP TABLE delivery") }
        ImageUploadRuntime.noteKeyActivity()
        assertNull(AppUsageTracker.nextUploadDelay(app))
        ShadowSystemClock.advanceBy(Duration.ofSeconds(4))
        GameWorkRuntime.setGaming(true)
        assertNull(AppUsageTracker.nextUploadDelay(app))
    }
    @Test fun `unreachable local queue alone does not schedule online retry`()=runBlocking {
        pending("http://127.0.0.1:65534")
        assertNull(AppUsageTracker.nextUploadDelay(app))
    }
}
