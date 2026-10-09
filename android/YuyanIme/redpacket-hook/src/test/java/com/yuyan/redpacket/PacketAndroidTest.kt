package com.yuyan.redpacket

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PacketAndroidTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    @Before fun clear() { context.getSharedPreferences("packet", 0).edit().clear().commit() }
    @Test fun journalSurvivesRestartAndNeverEvictsUnknownAttempts() {
        val journal = PacketJournal(context.getSharedPreferences("packet", 0), 2)
        assertTrue(journal.reserve("a".repeat(64)))
        assertFalse(PacketJournal(context.getSharedPreferences("packet", 0), 2).reserve("a".repeat(64)))
        assertTrue(journal.reserve("b".repeat(64)))
        assertFalse(journal.reserve("c".repeat(64)))
        assertFalse(journal.reserve("credentials"))
    }
    @Test fun protectionRejectsMissingServiceUnknownForegroundTypingAndGaming() {
        val gate = ProtectionGate()
        assertFalse(gate.allow(0, false, false, false, false))
        assertFalse(gate.allow(0, true, false, false, false))
        assertFalse(gate.allow(0, true, true, false, false))
        assertTrue(gate.allow(3000, true, true, false, false))
        assertFalse(gate.allow(3010, true, true, true, false))
        assertFalse(gate.allow(3011, true, true, false, false))
        assertFalse(gate.allow(6010, true, true, false, false))
        assertTrue(gate.allow(6011, true, true, false, false))
        assertFalse(gate.allow(7000, true, true, false, true))
        assertFalse(gate.allow(7001, true, true, false, false))
    }
    @Test fun receiveRequiresExplicitAvailableStatusAndNoChallenge() {
        val ok = JSONObject("""{"hbStatus":2,"receiveStatus":0,"hbType":1,"timingIdentifier":"token"}""")
        assertTrue(ResponsePolicy.receiveAllowed(0, ok))
        assertFalse(ResponsePolicy.receiveAllowed(1, ok))
        for (field in listOf("hbStatus", "receiveStatus", "hbType", "timingIdentifier")) {
            val copy = JSONObject(ok.toString()); copy.remove(field)
            assertFalse(ResponsePolicy.receiveAllowed(0, copy))
        }
        for (status in listOf(1, 4, 5, 99)) {
            val copy = JSONObject(ok.toString()).put("hbStatus", status)
            assertFalse(ResponsePolicy.receiveAllowed(0, copy))
        }
        assertFalse(ResponsePolicy.receiveAllowed(0, JSONObject(ok.toString()).put("receiveStatus", 1)))
        assertFalse(ResponsePolicy.receiveAllowed(0, JSONObject(ok.toString()).put("real_name_info", JSONObject())))
    }
    @Test fun openRequiresExplicitAmountStatusAndNoInterception() {
        val ok = JSONObject("""{"amount":5,"receiveStatus":2,"hbStatus":2}""")
        assertTrue(ResponsePolicy.claimed(0, ok))
        assertFalse(ResponsePolicy.claimed(1, ok))
        assertFalse(ResponsePolicy.claimed(0, JSONObject("{}")))
        assertFalse(ResponsePolicy.claimed(0, JSONObject(ok.toString()).put("amount", 0)))
        assertFalse(ResponsePolicy.claimed(0, JSONObject(ok.toString()).put("receiveStatus", 0)))
        for (field in listOf("real_name_info", "intercept_win", "intercept_win_after", "showmess")) {
            assertFalse(ResponsePolicy.claimed(0, JSONObject(ok.toString()).put(field, JSONObject())))
        }
    }
    @Test fun configurationDefaultsOffAndDoesNotEnableOnInvalidScope() {
        val prefs = context.getSharedPreferences("packet", 0)
        assertEquals(Mode.OFF, ConfigStore(prefs).load().mode)
        ConfigStore(prefs).save(PacketConfig(Mode.PROBE, 128, setOf("g@chatroom")), false)
        assertEquals(128, ConfigStore(prefs).load().user)
        ConfigStore(prefs).save(PacketConfig(Mode.AUTO, 128, setOf("g@chatroom")), false)
        assertEquals(Mode.PROBE, ConfigStore(prefs).load().mode)
        ConfigStore(prefs).save(PacketConfig(Mode.AUTO, 128, setOf("g@chatroom")), true)
        assertEquals(Mode.AUTO, ConfigStore(prefs).load().mode)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReviewedBoundariesTest {
    @Test fun screenOffCannotInventKnownForegroundAndOverlaysCannotReleaseGame() {
        val protection = ForegroundProtection()
        assertFalse(protection.allow(0, false, Foreground.UNKNOWN, false))
        assertFalse(protection.allow(5000, false, Foreground.UNKNOWN, false))
        assertFalse(protection.allow(5001, true, Foreground.GAME, false))
        assertFalse(protection.allow(10000, true, Foreground.OVERLAY, false))
        assertFalse(protection.allow(15000, false, Foreground.UNKNOWN, false))
        assertFalse(protection.allow(15001, true, Foreground.APP, false))
        assertTrue(protection.allow(18001, true, Foreground.APP, false))
    }
    @Test fun screenOffCannotReleaseAnUnknownOrGameBoundary() {
        val p = ForegroundProtection()
        assertTrue(p.allow(0, true, Foreground.APP, false))
        assertFalse(p.allow(1, true, Foreground.UNKNOWN, false))
        assertFalse(p.allow(10000, false, Foreground.UNKNOWN, false))
        assertFalse(p.allow(20000, false, Foreground.UNKNOWN, false))
        assertFalse(p.allow(20001, true, Foreground.GAME, false))
        assertFalse(p.allow(30000, false, Foreground.APP, false))
        assertFalse(p.allow(35000, false, Foreground.APP, false))
    }
    @Test fun independentJournalInstancesReserveSameKeyOnlyOnce() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("concurrent-journal", 0)
        prefs.edit().clear().commit()
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(8)
        try {
            val futures = (1..32).map {
                pool.submit<Boolean> { start.await(); PacketJournal(prefs).reserve("c".repeat(64)) }
            }
            start.countDown()
            assertEquals(1, futures.count { it.get() })
        } finally { pool.shutdownNow() }
    }
    @Test fun offBypassesInvalidFormAndRevokesAuto() {
        val activity = org.robolectric.Robolectric.buildActivity(PacketActivity::class.java).setup().get()
        val prefs = activity.getSharedPreferences("packet", 0)
        ConfigStore(prefs).save(PacketConfig(Mode.AUTO, 0, setOf("g@chatroom")), true)
        val groupsField = PacketActivity::class.java.getDeclaredField("groups").apply { isAccessible = true }
        (groupsField.get(activity) as android.widget.EditText).setText("invalid")
        val save = PacketActivity::class.java.getDeclaredMethod("save", Mode::class.java, Boolean::class.javaPrimitiveType).apply { isAccessible = true }
        save.invoke(activity, Mode.OFF, false)
        assertEquals(Mode.OFF, ConfigStore(prefs).load().mode)
        assertFalse(prefs.getBoolean("validated", true))
    }
}
