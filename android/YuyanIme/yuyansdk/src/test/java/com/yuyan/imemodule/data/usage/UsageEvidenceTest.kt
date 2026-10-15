package com.yuyan.imemodule.data.usage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class UsageEvidenceTest {
    private val app=ApplicationProvider.getApplicationContext<Application>()
    private val time=1_792_000_000_000L
    @Before fun setup() { app.deleteDatabase(java.io.File(app.noBackupFilesDir,"app_usage.db").absolutePath) }
    @Test fun `database supports bounded evidence without requiring usage records`() {
        UsageStore(app).use { store ->
            store.save(UsageReduction(UsageState(cursor=time),emptyList()),listOf("online"))
            store.readableDatabase.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name='evidence'",null).use {
                assertTrue("raw evidence must be durable independently of usage segments",it.moveToFirst())
            }
        }
    }
    @Test fun `raw off and lock remain separate from earlier pause and empty polls keep version`() {
        val events=listOf(UsageEvent(time+1_000,"resume","reader"),UsageEvent(time+8_000,"pause","reader"),UsageEvent(time+10_000,"off"),UsageEvent(time+11_000,"lock"))
        UsageStore(app).use { store ->
            store.save(UsageReduction(UsageState(cursor=time),emptyList()),listOf("online"))
            val r=UsageSessionEngine.reduce(store.state()!!,events,time+12_000)
            assertEquals(time+8_000,r.records.single().endMs)
            assertTrue(store.save(r,listOf("online"),events))
            val e=store.pendingEvidence("online")!!
            assertEquals(time+8_000,e.appActivity!!.atMs)
            assertEquals(time+10_000,e.screenOff!!.atMs)
            assertEquals(time+11_000,e.lock!!.atMs)
            assertFalse(store.save(UsageReduction(r.state.copy(cursor=time+20_000),emptyList()),listOf("online")))
            assertEquals(e,store.pendingEvidence("online"))
        }
    }
    @Test fun `snapshot only survives restart and target confirmations are independent exact versions`() {
        UsageStore(app).use { store ->
            store.save(UsageReduction(UsageState(cursor=time),emptyList()),listOf("a","b"))
            store.save(UsageReduction(UsageState(cursor=time+2_000),emptyList()),listOf("a","b"),listOf(UsageEvent(time+1_000,"off")))
        }
        UsageStore(app).use { store ->
            val first=store.pendingEvidence("a")!!
            assertEquals(setOf("a","b"),store.targets().toSet())
            assertTrue(store.pending("a").isEmpty())
            store.acknowledgeEvidence("a",first.version,"saved")
            assertNull(store.pendingEvidence("a")); assertNotNull(store.pendingEvidence("b"))
            store.save(UsageReduction(UsageState(cursor=time+4_000),emptyList()),listOf("a","b"),listOf(UsageEvent(time+3_000,"lock")))
            store.acknowledgeEvidence("b",first.version,"saved")
            assertNotNull(store.pendingEvidence("b"))
            val current=store.pendingEvidence("a")!!
            assertNotEquals(first.version,current.version)
            store.acknowledgeEvidence("a",current.version,"discarded")
            assertNull(store.pendingEvidence("a"))
            store.readableDatabase.rawQuery("SELECT disposition FROM evidence_delivery WHERE target='a'",null).use { assertTrue(it.moveToFirst());assertEquals("discarded",it.getString(0)) }
            store.save(UsageReduction(UsageState(cursor=time+5_000),emptyList()),listOf("a","c"))
            assertNull(store.pendingEvidence("a"))
            assertEquals(current,store.pendingEvidence("c"))
        }
    }
    @Test fun `failed snapshot write rolls back cursor and records together`() {
        UsageStore(app).use { store ->
            store.save(UsageReduction(UsageState(cursor=time),emptyList()),listOf("a"))
            store.writableDatabase.execSQL("CREATE TRIGGER reject_evidence BEFORE INSERT ON evidence BEGIN SELECT RAISE(ABORT,'test write failure'); END")
            val row=UsageRecord("r","usage","reader",null,time,time+1_000,"off")
            try {
                store.save(UsageReduction(UsageState(cursor=time+2_000),listOf(row)),listOf("a"),listOf(UsageEvent(time+1_000,"off")))
                fail("snapshot write must fail")
            } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(time,store.state()!!.cursor)
            assertTrue(store.pending("a").isEmpty());assertNull(store.pendingEvidence("a"))
        }
    }
    @Test fun `out of window and unrelated events cannot fabricate evidence`() {
        UsageStore(app).use { store ->
            store.save(UsageReduction(UsageState(cursor=time),emptyList()),listOf("a"))
            assertFalse(store.save(UsageReduction(UsageState(cursor=time+10_000),emptyList()),listOf("a"),listOf(
                UsageEvent(time-1,"off"),UsageEvent(time+10_000,"lock"),UsageEvent(time+1,"stop"),UsageEvent(time+2,"on")
            )))
            assertNull(store.pendingEvidence("a"))
        }
    }
    @Test fun `v2 migration preserves pending records without inventing historical evidence`() {
        UsageStore(app).use { store ->
            store.save(UsageReduction(UsageState(cursor=time),listOf(UsageRecord("old","usage","reader",null,time-10_000,time,"off"))),listOf("a"))
            store.writableDatabase.execSQL("DROP TABLE evidence_delivery")
            store.writableDatabase.execSQL("DROP TABLE evidence")
            store.writableDatabase.version=2
        }
        UsageStore(app).use { store ->
            assertEquals(3,store.readableDatabase.version)
            assertEquals(time,store.state()!!.cursor)
            assertEquals("old",store.pending("a").single().id)
            assertNull(store.pendingEvidence("a"))
        }
    }
    @Test fun `returning target gets latest authorized version even without another new event`() {
        UsageStore(app).use { store ->
            store.save(UsageReduction(UsageState(cursor=time),emptyList()),listOf("a"))
            store.save(UsageReduction(UsageState(cursor=time+2),emptyList()),listOf("a"),listOf(UsageEvent(time+1,"off")))
            val old=store.pendingEvidence("a")!!
            store.acknowledgeEvidence("a",old.version,"saved")
            store.save(UsageReduction(UsageState(cursor=time+4),emptyList()),listOf("b"),listOf(UsageEvent(time+3,"lock")))
            val latest=store.pendingEvidence("b")!!
            assertTrue(store.save(UsageReduction(UsageState(cursor=time+5),emptyList()),listOf("a")))
            assertEquals(latest,store.pendingEvidence("a"))
            store.acknowledgeEvidence("a",old.version,"saved")
            assertEquals(latest,store.pendingEvidence("a"))
        }
    }
}
