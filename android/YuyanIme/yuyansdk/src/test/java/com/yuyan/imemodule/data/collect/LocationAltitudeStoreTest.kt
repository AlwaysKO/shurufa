package com.yuyan.imemodule.data.collect

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class LocationAltitudeStoreTest {
    @Test fun `real version13 queue upgrades without altering existing report payload or target ownership`() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="altitude-migration-${UUID.randomUUID()}.db"
        var store=LocalInputStore(app,name)
        try {
            store.enqueueReport(PendingReport("legacy","location","""{"latitude":31,"longitude":121,"context":{"version":1,"altitude_m":0}}"""),listOf("one","two"))
            val db=store.writableDatabase
            // Construct the actual v13 report_target schema, retaining all other real queue tables.
            db.execSQL("CREATE TABLE v13_report_target (report_id TEXT NOT NULL,target TEXT NOT NULL,attempted_at INTEGER NOT NULL DEFAULT 0,PRIMARY KEY(report_id,target))")
            db.execSQL("INSERT INTO v13_report_target SELECT report_id,target,attempted_at FROM report_target")
            db.execSQL("DROP TABLE report_target")
            db.execSQL("ALTER TABLE v13_report_target RENAME TO report_target")
            db.execSQL("CREATE INDEX report_target_url ON report_target(target,attempted_at)")
            db.version=13; store.close(); store=LocalInputStore(app,name)
            val columns=store.readableDatabase.rawQuery("PRAGMA table_info(report_target)",null).use { c ->
                buildSet { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
            }
            assertTrue(columns.contains("altitude_evidence"))
            assertEquals(14,store.readableDatabase.version)
            assertEquals(store.pendingReports("one"),store.pendingReports("two"))
            assertFalse(store.pendingReports("one").single().payload.contains("raw_altitude"))
            store.readableDatabase.rawQuery("SELECT altitude_evidence FROM report_target",null).use { c ->
                var count=0; while(c.moveToNext()) { count++;assertTrue(c.isNull(0)) };assertEquals(2,count)
            }
        } finally { store.close();app.deleteDatabase(name) }
    }
    @Test fun `wire choice is immutable per independent target and survives endpoint replacement`() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="altitude-pin-${UUID.randomUUID()}.db";val store=LocalInputStore(app,name)
        try {
            store.enqueueReport(PendingReport("point","location","{}"),listOf("one","two"))
            assertFalse(store.pinLocationAltitudeWireVariant("one","point",false))
            assertFalse(store.pinLocationAltitudeWireVariant("one","point",true))
            assertTrue(store.pinLocationAltitudeWireVariant("two","point",true))
            store.replaceTarget("two","three")
            assertEquals(true,store.locationAltitudeWireVariant("three","point"))
            assertFalse(store.locationAltitudeWireVariant("one","point")!!)
            store.acknowledgeReports("one",listOf("point"))
            assertNull(store.locationAltitudeWireVariant("one","point"))
            assertEquals(1,store.pendingReports("three").size)
        } finally {store.close();app.deleteDatabase(name)}
    }

    @Test fun `target replacement fills missing wire choice but preserves destination prior choice`() {
        val app=ApplicationProvider.getApplicationContext<Context>()
        val name="altitude-move-${UUID.randomUUID()}.db";val store=LocalInputStore(app,name)
        try {
            for(id in listOf("unset","set")) store.enqueueReport(PendingReport(id,"location","{}"),listOf("old","new"))
            store.pinLocationAltitudeWireVariant("old","unset",false)
            store.pinLocationAltitudeWireVariant("old","set",false)
            store.pinLocationAltitudeWireVariant("new","set",true)
            store.replaceTarget("old","new")
            assertEquals(false,store.locationAltitudeWireVariant("new","unset"))
            assertEquals(true,store.locationAltitudeWireVariant("new","set"))
        } finally {store.close();app.deleteDatabase(name)}
    }

}
