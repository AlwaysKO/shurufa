package com.yuyan.imemodule.data.usage

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[23,28,35])
class UsageStoreTest {
    private lateinit var app: Application
    @Before fun setup() { app=ApplicationProvider.getApplicationContext(); app.deleteDatabase(java.io.File(app.noBackupFilesDir,"app_usage.db").absolutePath) }
    @Test fun `checkpoint and queued records survive reopening with independent target acknowledgements`() {
        val r=UsageSessionEngine.reduce(UsageState(cursor=100),listOf(UsageEvent(110,"resume","a","one"),UsageEvent(200,"lock")),300)
        UsageStore(app).use { it.save(r,listOf("local","online")) }
        UsageStore(app).use { store ->
            assertEquals(300L,store.state()!!.cursor)
            assertEquals(1,store.pending("local").size)
            store.acknowledge("local",store.pending("local").map { it.id })
            assertTrue(store.pending("local").isEmpty())
            assertEquals(1,store.pending("online").size)
            store.save(r,listOf("local","online"))
            // replay after an acknowledged upload must not resurrect it
            assertTrue(store.pending("local").isEmpty())
            store.acknowledge("online",store.pending("online").map { it.id })
            assertTrue(store.pending("online").isEmpty())
        }
    }
    @Test fun `a rejected transaction cannot move checkpoint`() {
        UsageStore(app).use { store ->
            store.save(UsageReduction(UsageState(cursor=100),emptyList()),listOf("local"))
            val record=UsageRecord("x","usage","a",null,200,100,"pause")
            try { store.save(UsageReduction(UsageState(cursor=300),listOf(record)),listOf("local")); fail("invalid interval must fail") } catch (_: android.database.sqlite.SQLiteConstraintException) { }
            assertEquals(100L,store.state()!!.cursor)
            assertTrue(store.pending("local").isEmpty())
        }
    }
    @Test fun `locked candidate survives storage but gap clears it`() {
        val first=UsageSessionEngine.reduce(UsageState(cursor=100),listOf(UsageEvent(110,"lock"),UsageEvent(200,"resume","a","one")),250)
        UsageStore(app).use { it.save(first,listOf("local")) }
        UsageStore(app).use {
            assertEquals("a",it.state()!!.candidate!!.packageName)
            val resumed=UsageSessionEngine.reduce(it.state()!!,listOf(UsageEvent(300,"unlock"),UsageEvent(400,"pause","a","one")),2000)
            assertEquals(300L,resumed.records.single().startMs)
            assertNull(UsageSessionEngine.gap(it.state()!!,500,"reboot").state.candidate)
        }
    }
    @Test fun `usage queue cannot transfer to another phone through keyboard database backups`() {
        UsageStore(app).use { store ->
            assertTrue(java.io.File(store.writableDatabase.path).canonicalPath.startsWith(app.noBackupFilesDir.canonicalPath + java.io.File.separator))
        }
    }
    @Test fun `pending intervals have stable wire fields and positive durations`() {
        val r=UsageRecord("id","usage","pkg","应用",10,20,"pause")
        val wire=r.toJson()
        assertEquals(10L,wire.getLong("start_ms"))
        assertEquals(20L,wire.getLong("end_ms"))
        assertEquals(r,usageRecordFromJson(wire))
    }
    @Test fun `online confirmation retention does not expire unconfirmed usage or reset on repeat acknowledgement`() {
        val week=7*86400000L
        UsageStore(app).use { store ->
            val records=listOf("done","pending").map { UsageRecord(it,"usage","pkg",null,1,10,"pause") }
            store.save(UsageReduction(UsageState(cursor=20),records),listOf("local","online"))
            store.acknowledge("online",listOf("done"),"online",100L)
            store.prune(99L,"online")
            assertEquals(2,store.pending("local").size)
            store.acknowledge("online",listOf("done"),"online",week)
            store.prune(101L,"online")
            assertEquals(listOf("pending"),store.pending("local").map{it.id})
            assertEquals(listOf("pending"),store.pending("online").map{it.id})
        }
    }

}
